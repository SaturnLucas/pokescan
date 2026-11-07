package com.pokescan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** Área da moldura na tela, em frações da visualização da câmera. */
data class Guide(val lf: Float, val tf: Float, val wf: Float, val hf: Float, val aspect: Float)

class VisualEntry(val id: String, val name: String, val number: String, val imageBase: String)
data class VisualHit(val entry: VisualEntry, val score: Float)

class VisualData(
    val n: Int,
    val din: Int,
    val d: Int,
    val thr: Float,
    val mode: Int,
    val modelSha: ByteArray,
    val mean: FloatArray,
    val proj: FloatArray,
    val vecs: ByteArray,
    val meta: List<VisualEntry>
)

/** Índice de cartas (impressões digitais de imagem) gerado por tools/build_index.py. */
object VisualIndex {
    @Volatile
    var data: VisualData? = null

    fun file(ctx: Context) = File(ctx.filesDir, "cardindex.bin")

    fun load(ctx: Context): Boolean {
        val f = file(ctx)
        if (!f.exists()) return false
        return try {
            val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            bb.get(magic)
            if (String(magic) != "PSIX") return false
            val version = bb.int
            val n = bb.int
            val din = bb.int
            val d = bb.int
            val thr = bb.float
            if ((version != 1 && version != 2) || n <= 0 || din <= 0 || d <= 0) return false
            val sha = ByteArray(32)
            bb.get(sha)
            val mode = if (version >= 2) bb.int else 1
            val mean = FloatArray(din) { bb.float }
            val proj = FloatArray(din * d) { bb.float }
            val vecs = ByteArray(n * d)
            bb.get(vecs)
            val metaLen = bb.int
            val mb = ByteArray(metaLen)
            bb.get(mb)
            val meta = String(mb, Charsets.UTF_8).split('\n').map { line ->
                val p = line.split('\t')
                VisualEntry(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
            }
            if (meta.size != n) return false
            data = VisualData(n, din, d, thr, mode, sha, mean, proj, vecs, meta)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Projeta o vetor do modelo (1024) para o espaço reduzido do índice e normaliza. */
    fun project(v: VisualData, e: FloatArray): FloatArray {
        val z = FloatArray(v.d)
        for (i in 0 until v.din) {
            val x = e[i] - v.mean[i]
            val base = i * v.d
            for (j in 0 until v.d) z[j] += x * v.proj[base + j]
        }
        var s = 0f
        for (x in z) s += x * x
        val norm = sqrt(s)
        if (norm > 0f) for (j in z.indices) z[j] /= norm
        return z
    }

    /** As [k] cartas mais parecidas (similaridade de cosseno). */
    fun search(v: VisualData, q: FloatArray, k: Int): List<VisualHit> {
        val topIdx = IntArray(k) { -1 }
        val topSc = FloatArray(k) { -2f }
        for (r in 0 until v.n) {
            var acc = 0f
            val base = r * v.d
            for (j in 0 until v.d) acc += v.vecs[base + j] * q[j]
            acc /= 127f
            if (acc > topSc[k - 1]) {
                var p = k - 1
                while (p > 0 && topSc[p - 1] < acc) {
                    topSc[p] = topSc[p - 1]
                    topIdx[p] = topIdx[p - 1]
                    p--
                }
                topSc[p] = acc
                topIdx[p] = r
            }
        }
        return (0 until k).filter { topIdx[it] >= 0 }.map { VisualHit(v.meta[topIdx[it]], topSc[it]) }
    }
}

/** MobileNetV3 (MediaPipe ImageEmbedder): transforma a foto da carta num vetor. */
class VisualEmbedder(ctx: Context) {
    private val emb: ImageEmbedder

    init {
        val expected = VisualIndex.data?.modelSha
        val md = MessageDigest.getInstance("SHA-256")
        ctx.assets.open(MODEL_ASSET).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        if (expected != null && !md.digest().contentEquals(expected)) {
            throw IllegalStateException("O modelo do app não combina com o índice. Atualize o app e o índice.")
        }
        val opts = ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .build()
        emb = ImageEmbedder.createFromOptions(ctx, opts)
    }

    fun embed(bmp: Bitmap): FloatArray? {
        val r = emb.embed(BitmapImageBuilder(bmp).build())
        val e = r.embeddingResult().embeddings().firstOrNull() ?: return null
        val v = e.floatEmbedding()
        var s = 0f
        for (x in v) s += x * x
        val norm = sqrt(s)
        return if (norm > 0f) FloatArray(v.size) { v[it] / norm } else v
    }

    /** Vetor da carta inteira (modo bit 1), da arte (bit 2) ou os dois concatenados, como no índice. */
    fun embedViews(bmp: Bitmap, mode: Int): FloatArray? {
        val parts = ArrayList<FloatArray>()
        if ((mode and 1) != 0) parts.add(embed(bmp) ?: return null)
        if ((mode and 2) != 0) parts.add(embed(artCrop(bmp)) ?: return null)
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) {
            p.copyInto(out, o)
            o += p.size
        }
        return out
    }

    fun close() {
        try { emb.close() } catch (_: Throwable) { }
    }

    companion object {
        const val MODEL_ASSET = "mobilenet_v3_small.tflite"

        /** Recorte da arte: x 0.08..0.92, y 0.12..0.56 da carta (igual ao tools/build_index.py). */
        fun artCrop(bmp: Bitmap): Bitmap {
            val w = bmp.width
            val h = bmp.height
            val x = (w * 0.08f).toInt()
            val y = (h * 0.12f).toInt()
            return Bitmap.createBitmap(bmp, x, y, (w * 0.84f).toInt().coerceAtLeast(1), (h * 0.44f).toInt().coerceAtLeast(1))
        }

        /** Recorta do quadro da câmera a região que está dentro da moldura (já girado para a vertical). */
        fun cropGuide(image: ImageProxy, g: Guide): Bitmap {
            var bmp = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            if (rot != 0) {
                val m = Matrix()
                m.postRotate(rot.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val iw = bmp.width.toFloat()
            val ih = bmp.height.toFloat()
            val visW: Float
            val visH: Float
            if (g.aspect > iw / ih) {
                visW = iw
                visH = iw / g.aspect
            } else {
                visH = ih
                visW = ih * g.aspect
            }
            val offX = (iw - visW) / 2f
            val offY = (ih - visH) / 2f
            var x = offX + g.lf * visW
            var y = offY + g.tf * visH
            var w = g.wf * visW
            var h = g.hf * visH
            x += w * 0.03f; y += h * 0.03f; w *= 0.94f; h *= 0.94f
            x = x.coerceIn(0f, iw - 2f)
            y = y.coerceIn(0f, ih - 2f)
            w = w.coerceAtMost(iw - x).coerceAtLeast(1f)
            h = h.coerceAtMost(ih - y).coerceAtLeast(1f)
            return Bitmap.createBitmap(bmp, x.toInt(), y.toInt(), w.toInt(), h.toInt())
        }
    }
}

/** Baixa o índice publicado na release "index" do GitHub. */
object IndexUpdater {
    class Remote(val url: String, val stamp: String, val sha256: String?, val size: Long)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun remote(): Remote? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${Config.REPO}/releases/tags/index")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val o = JSONObject(r.body!!.string())
            val assets = o.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == "cardindex.bin") {
                    val d = a.optString("digest")
                    return Remote(
                        a.getString("browser_download_url"),
                        a.optString("updated_at"),
                        if (d.startsWith("sha256:")) d.removePrefix("sha256:") else null,
                        a.optLong("size")
                    )
                }
            }
            return null
        }
    }

    fun download(ctx: Context, r: Remote): Boolean {
        if (!r.url.startsWith("https://github.com/${Config.REPO}/releases/download/index/")) return false
        val tmp = File(ctx.cacheDir, "cardindex.tmp")
        val sha = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(r.url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body!!.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                    }
                }
            }
        }
        val hex = sha.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256 != null && !hex.equals(r.sha256, ignoreCase = true)) {
            tmp.delete()
            throw RuntimeException("Índice corrompido (checksum não confere)")
        }
        val dest = VisualIndex.file(ctx)
        if (dest.exists()) dest.delete()
        return tmp.renameTo(dest)
    }
}
