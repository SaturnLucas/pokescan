package com.pokescan

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object Config {
    const val REPO = "SaturnLucas/pokescan"
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("pokescan", Context.MODE_PRIVATE)

    var currency: String
        get() = sp.getString("currency", "BRL") ?: "BRL"
        set(v) { sp.edit().putString("currency", v).apply() }

    var autoScan: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) { sp.edit().putBoolean("auto", v).apply() }

    var ratesJson: String?
        get() = sp.getString("rates", null)
        set(v) { sp.edit().putString("rates", v).apply() }

    var ratesTime: Long
        get() = sp.getLong("ratesTime", 0L)
        set(v) { sp.edit().putLong("ratesTime", v).apply() }

    var lastProfile: Long
        get() = sp.getLong("lastProfile", -1L)
        set(v) { sp.edit().putLong("lastProfile", v).apply() }

    var bioProfile: Long
        get() = sp.getLong("bioProfile", -1L)
        set(v) { sp.edit().putLong("bioProfile", v).apply() }

    /** Segundos em segundo plano antes de bloquear; -1 = nunca. */
    var lockDelay: Int
        get() = sp.getInt("lockDelay", 60)
        set(v) { sp.edit().putInt("lockDelay", v).apply() }

    fun getInt(k: String): Int = sp.getInt(k, 0)
    fun putInt(k: String, v: Int) { sp.edit().putInt(k, v).apply() }
    fun getLong(k: String): Long = sp.getLong(k, 0L)
    fun putLong(k: String, v: Long) { sp.edit().putLong(k, v).apply() }
}

object Currencies {
    val list = listOf(
        "BRL" to "Real (R$)",
        "USD" to "Dólar (US$)",
        "EUR" to "Euro (€)",
        "GBP" to "Libra (£)",
        "JPY" to "Iene (¥)",
        "CAD" to "Dólar canadense",
        "AUD" to "Dólar australiano",
        "MXN" to "Peso mexicano",
        "CHF" to "Franco suíço",
        "CNY" to "Yuan",
        "KRW" to "Won"
    )

    val fallback = mapOf(
        "USD" to 1.0, "BRL" to 5.5, "EUR" to 0.92, "GBP" to 0.78, "JPY" to 150.0,
        "CAD" to 1.37, "AUD" to 1.52, "MXN" to 18.0, "CHF" to 0.88, "CNY" to 7.2, "KRW" to 1350.0
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Cotações do dia (base dólar) via frankfurter.dev, sem chave. */
    fun fetchRates(): Map<String, Double> {
        val urls = listOf(
            "https://api.frankfurter.dev/v1/latest?base=USD",
            "https://api.frankfurter.app/latest?from=USD"
        )
        var last: Exception? = null
        for (u in urls) {
            try {
                http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                    val rates = JSONObject(r.body!!.string()).getJSONObject("rates")
                    val m = mutableMapOf("USD" to 1.0)
                    val it = rates.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        m[k] = rates.getDouble(k)
                    }
                    return m
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: RuntimeException("sem cotações")
    }
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    val sha256: String? = null
)

/** Atualização automática: consulta a última release do GitHub e instala o APK novo. */
object Updater {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun latest(): UpdateInfo? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${Config.REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val o = JSONObject(r.body!!.string())
            val code = o.getString("tag_name").filter { it.isDigit() }.toIntOrNull() ?: return null
            val assets = o.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    val d = a.optString("digest")
                    val sha = if (d.startsWith("sha256:")) d.removePrefix("sha256:") else null
                    return UpdateInfo(code, "1.$code", a.getString("browser_download_url"), o.optString("body"), sha)
                }
            }
            return null
        }
    }

    fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        if (!info.url.startsWith("https://github.com/${Config.REPO}/releases/download/")) {
            throw RuntimeException("Endereço de atualização não confiável")
        }
        val sha = MessageDigest.getInstance("SHA-256")
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "PokeScan-${info.versionCode}.apk")
        http.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val body = r.body!!
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
        }
        val hex = sha.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256 != null && !hex.equals(info.sha256, ignoreCase = true)) {
            file.delete()
            throw RuntimeException("Arquivo corrompido (checksum não confere)")
        }
        return file
    }

    /** Só recusa se a assinatura do APK for DIFERENTE da do app instalado (o Android também confere ao instalar). */
    fun signatureOk(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT < 28) return true
        return try {
            val pm = ctx.packageManager
            val archive = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return true
            if (archive.packageName != ctx.packageName) return false
            val a = archive.signingInfo?.apkContentsSigners ?: return true
            val own = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val b = own.signingInfo?.apkContentsSigners ?: return true
            a.map { it.toCharsString() }.toSet() == b.map { it.toCharsString() }.toSet()
        } catch (e: Exception) {
            true
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
