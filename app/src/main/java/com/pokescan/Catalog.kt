package com.pokescan

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Resultado de busca no catálogo. [card] vem preenchido quando a fonte já devolve a carta completa (Poke API Brasil). */
data class CatalogHit(
    val id: String,
    val name: String,
    val number: String,
    val thumb: String?,
    val lang: String,
    val card: ApiCard? = null
)
data class CatalogResult(val hits: List<CatalogHit>, val note: String?)

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

/** Condições de carta (padrão de mercado) e quanto cada uma vale em relação à Near Mint. */
object Cond {
    val all = listOf("NM", "SP", "MP", "HP", "D")
    private val mult = mapOf("NM" to 1.0, "SP" to 0.85, "MP" to 0.70, "HP" to 0.50, "D" to 0.30)
    private val labels = mapOf(
        "NM" to "Quase perfeita", "SP" to "Leve uso", "MP" to "Uso moderado",
        "HP" to "Muito usada", "D" to "Danificada"
    )

    fun factor(c: String): Double = mult[c] ?: 1.0
    fun label(c: String): String = labels[c] ?: c

    /** Recebe 4 notas (0 = ótimo ... 3 = ruim) de cantos, bordas, superfície e centralização. */
    fun fromScores(l: List<Int>): String {
        val worst = l.maxOrNull() ?: 0
        val sum = l.sum()
        if (sum >= 10 || (worst == 3 && sum >= 8)) return "D"
        val idx = Math.round(0.6 * worst + 0.4 * (sum / 4.0)).toInt().coerceIn(0, 3)
        return all[idx]
    }
}

/**
 * TCGdex (https://tcgdex.dev): API gratuita, sem chave, com catálogo em português.
 * Usada no Buscar/Mercado e como base reserva quando o pokemontcg.io está fora do ar.
 */
object TcgDex {
    private const val BASE = "https://api.tcgdex.net/v2"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val cache = ConcurrentHashMap<String, String>()

    /** GET com 3 tentativas para erros 5xx/rede. Devolve null em 404. */
    private fun get(url: String): String? {
        cache[url]?.let { return it }
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.code == 404) return null
                    if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                    val body = r.body!!.string()
                    cache[url] = body
                    return body
                }
            } catch (e: Exception) {
                last = e
                if (attempt < 2) Thread.sleep(800L shl attempt)
            }
        }
        throw last ?: RuntimeException("Erro desconhecido")
    }

    /** Busca por nome (contém). A API devolve do mais antigo ao mais novo; invertemos para mostrar os novos primeiro. */
    fun search(lang: String, q: String): List<CatalogHit> {
        val url = "$BASE/$lang/cards".toHttpUrl().newBuilder().addQueryParameter("name", q).build().toString()
        val body = get(url) ?: return emptyList()
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CatalogHit(o.str("id"), o.str("name"), o.str("localId"), o.str("image").ifBlank { null }?.let { "$it/low.webp" }, lang)
        }.reversed().take(60)
    }

    fun detail(lang: String, id: String, eurPerUsd: Double): ApiCard? {
        val body = get("$BASE/$lang/cards/$id") ?: return null
        val o = JSONObject(body)
        val set = o.optJSONObject("set")
        val img = o.str("image")
        val symbol = set?.str("symbol").orEmpty()
        val cc = set?.optJSONObject("cardCount")
        return ApiCard(
            id = o.str("id"),
            name = o.str("name"),
            number = o.str("localId"),
            setId = set?.str("id").orEmpty(),
            setName = set?.str("name").orEmpty(),
            setSymbol = if (symbol.isNotBlank()) "$symbol.png" else "",
            printedTotal = cc?.optInt("official", cc.optInt("total", 0)) ?: 0,
            rarity = o.str("rarity").ifBlank { null },
            imageSmall = if (img.isNotBlank()) "$img/low.webp" else "",
            imageLarge = if (img.isNotBlank()) "$img/high.webp" else "",
            price = priceUsd(o.optJSONObject("pricing"), eurPerUsd)
        )
    }

    /** Preço de referência em dólar: TCGplayer (USD) ou, se não houver, Cardmarket (EUR) convertido. */
    private fun priceUsd(pricing: JSONObject?, eurPerUsd: Double): Double? {
        if (pricing == null) return null
        val tp = pricing.optJSONObject("tcgplayer")
        if (tp != null) {
            val keys = tp.keys()
            while (keys.hasNext()) {
                val v = tp.optJSONObject(keys.next()) ?: continue
                for (f in listOf("marketPrice", "midPrice")) {
                    val x = v.optDouble(f, Double.NaN)
                    if (!x.isNaN() && x > 0) return x
                }
            }
        }
        val cm = pricing.optJSONObject("cardmarket")
        if (cm != null) {
            for (f in listOf("trend", "avg", "avg7", "low")) {
                val x = cm.optDouble(f, Double.NaN)
                if (!x.isNaN() && x > 0) return if (eurPerUsd > 0) x / eurPerUsd else x
            }
        }
        return null
    }

    private data class TdSet(val id: String, val official: Int, val total: Int)
    private var setsCache: List<TdSet>? = null

    private fun sets(): List<TdSet> {
        setsCache?.let { return it }
        val body = get("$BASE/en/sets") ?: return emptyList()
        val arr = JSONArray(body)
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val cc = o.optJSONObject("cardCount")
            TdSet(o.str("id"), cc?.optInt("official", 0) ?: 0, cc?.optInt("total", 0) ?: 0)
        }
        setsCache = list
        return list
    }

    /** Identifica pelo número impresso (ex: 25/165): acha os sets com 165 cartas e consulta cada um. */
    fun findByNumber(number: String, total: Int, eurPerUsd: Double): List<ApiCard> {
        val out = mutableListOf<ApiCard>()
        val candidates = sets().filter { it.official == total }.take(8)
        for (s in candidates) {
            val ids = listOf(number, number.padStart(3, '0')).distinct()
            for (lid in ids) {
                val d = detail("pt-br", "${s.id}-$lid", eurPerUsd)
                    ?: detail("pt", "${s.id}-$lid", eurPerUsd)
                    ?: detail("en", "${s.id}-$lid", eurPerUsd)
                if (d != null) {
                    out += d
                    break
                }
            }
        }
        return out
    }
}


/**
 * Poke API Brasil (https://pokeapi.com.br): sem chave. GET /api/cards/search?q=...&limit=...
 * Resposta: { success, count, data: [ { id, name, set_name, rarity, image_url (relativa), raw_data } ] }.
 * Boa para achar cartas por nome em português; as imagens vêm do próprio serviço.
 */
object PokeApiBr {
    private const val HOST = "https://pokeapi.com.br"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val cache = ConcurrentHashMap<String, List<ApiCard>>()

    fun searchCards(q: String, limit: Int = 30): List<ApiCard> {
        val key = "$q|$limit"
        cache[key]?.let { return it }
        val url = "$HOST/api/cards/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("limit", "$limit")
            .build()
        var last: Exception? = null
        for (attempt in 0 until 2) {
            try {
                client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                    if (r.code == 404) return emptyList()
                    if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                    val root = JSONObject(r.body!!.string())
                    if (root.has("success") && !root.optBoolean("success", true)) return emptyList()
                    val arr = root.optJSONArray("data") ?: return emptyList()
                    val list = (0 until arr.length()).mapNotNull { i -> toCard(arr.getJSONObject(i)) }
                    if (list.isNotEmpty()) cache[key] = list
                    return list
                }
            } catch (e: Exception) {
                last = e
                if (attempt < 1) Thread.sleep(800)
            }
        }
        throw last ?: RuntimeException("Erro desconhecido")
    }

    fun search(q: String): List<CatalogHit> =
        searchCards(q).map { CatalogHit(it.id, it.name, it.number, it.imageSmall.ifBlank { null }, "pokeapibr", it) }

    private fun abs(u: String): String = if (u.startsWith("https://")) u else if (u.startsWith("/")) HOST + u else ""

    private fun toCard(o: JSONObject): ApiCard? {
        val id = o.str("id")
        if (id.isBlank()) return null
        val raw = o.optJSONObject("raw_data")
        val set = raw?.optJSONObject("set")
        val number = raw?.str("number").orEmpty().ifBlank { raw?.str("localId").orEmpty() }
            .ifBlank { id.substringAfterLast('-', "") }
        val cc = set?.optJSONObject("cardCount")
        val total = set?.optInt("printedTotal", 0)?.takeIf { it > 0 } ?: cc?.optInt("official", 0) ?: 0
        val low = o.str("image_url")
        val high = low.replace("size=low", "size=high")
        var price: Double? = null
        val prices = raw?.optJSONObject("tcgplayer")?.optJSONObject("prices")
        if (prices != null) {
            val keys = prices.keys()
            while (keys.hasNext()) {
                val m = prices.optJSONObject(keys.next())?.optDouble("market", Double.NaN) ?: Double.NaN
                if (!m.isNaN() && m > 0) { price = m; break }
            }
        }
        return ApiCard(
            id = id,
            name = o.str("name"),
            number = number,
            setId = set?.str("id").orEmpty().ifBlank { id.substringBeforeLast('-', "") },
            setName = o.str("set_name").ifBlank { set?.str("name").orEmpty() },
            setSymbol = "",
            printedTotal = total,
            rarity = o.str("rarity").ifBlank { null },
            imageSmall = abs(low),
            imageLarge = abs(high).ifBlank { abs(low) },
            price = price
        )
    }
}
