package com.pokescan

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

sealed interface ScanState {
    object Idle : ScanState
    object Loading : ScanState
    data class Choose(val cards: List<ApiCard>) : ScanState
}

data class Added(val card: OwnedCard, val isNew: Boolean)

class MainVM(private val app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    private val prefs = Prefs(app)

    val cards = dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- escaneamento ----
    var state by mutableStateOf<ScanState>(ScanState.Idle)
        private set
    var message by mutableStateOf<String?>(null)
    var added by mutableStateOf<Added?>(null)
        private set
    var addedTick by mutableIntStateOf(0)
        private set

    @Volatile
    private var forceNext = false
    private var candKey: String? = null
    private var candCount = 0
    private var lockedKey: String? = null
    private var lastSeen = 0L
    private var retryAt = 0L

    val wantsFrames: Boolean
        get() = state == ScanState.Idle && (autoScan || forceNext)

    // ---- ajustes ----
    var currency by mutableStateOf(prefs.currency)
        private set
    var autoScan by mutableStateOf(prefs.autoScan)
        private set
    var rates by mutableStateOf(loadRates())
        private set
    var ratesTime by mutableStateOf(prefs.ratesTime)
        private set

    // ---- atualização ----
    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    var updateStatus by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    private val pi = app.packageManager.getPackageInfo(app.packageName, 0)
    val versionName: String = pi.versionName ?: "?"
    val versionCode: Int = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode

    init {
        if (System.currentTimeMillis() - prefs.ratesTime > 12L * 3600 * 1000) {
            viewModelScope.launch {
                try {
                    val m = withContext(Dispatchers.IO) { Currencies.fetchRates() }
                    prefs.ratesJson = JSONObject(m as Map<*, *>).toString()
                    prefs.ratesTime = System.currentTimeMillis()
                    rates = m
                    ratesTime = prefs.ratesTime
                } catch (e: Exception) {
                    // mantém as últimas cotações salvas
                }
            }
        }
    }

    private fun loadRates(): Map<String, Double> {
        val js = prefs.ratesJson ?: return Currencies.fallback
        return try {
            val o = JSONObject(js)
            val m = mutableMapOf<String, Double>()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                m[k] = o.getDouble(k)
            }
            m
        } catch (e: Exception) {
            Currencies.fallback
        }
    }

    fun money(usd: Double?): String {
        if (usd == null) return "—"
        val rate = rates[currency] ?: 1.0
        val nf = NumberFormat.getCurrencyInstance(
            if (currency == "BRL") Locale("pt", "BR") else Locale.getDefault()
        )
        nf.currency = Currency.getInstance(currency)
        return nf.format(usd * rate)
    }

    fun changeCurrency(code: String) {
        currency = code
        prefs.currency = code
    }

    fun changeAutoScan(on: Boolean) {
        autoScan = on
        prefs.autoScan = on
    }

    // ---- fluxo de escaneamento ----
    fun dismiss() { state = ScanState.Idle }
    fun clearAdded() { added = null }
    fun scanNow() { forceNext = true; message = null }

    fun fail(msg: String) {
        state = ScanState.Idle
        message = msg
    }

    /** Chamado a cada quadro lido pela câmera. Confirma a carta quando o mesmo número aparece 2x seguidas. */
    fun onFrame(p: Parsed) {
        if (state != ScanState.Idle) return
        val now = SystemClock.elapsedRealtime()
        if (now < retryAt) return
        if (forceNext) {
            forceNext = false
            onOcr(p)
            return
        }
        val key = if (p.number != null && p.total != null) "${p.number}/${p.total}" else null
        if (key == null) {
            if (now - lastSeen > 1500) lockedKey = null
            candKey = null
            candCount = 0
            return
        }
        lastSeen = now
        if (key == lockedKey) return
        if (key == candKey) candCount++ else { candKey = key; candCount = 1 }
        if (candCount >= 2) {
            lockedKey = key
            candKey = null
            candCount = 0
            onOcr(p)
        }
    }

    fun onOcr(p: Parsed) {
        if (p.number == null && p.names.isEmpty()) {
            fail("Não consegui ler a carta. Tente com mais luz e a carta bem enquadrada.")
            return
        }
        state = ScanState.Loading
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { find(p) }
                when {
                    found.isEmpty() -> fail("Carta não encontrada. Afaste e aproxime de novo, ou busque pelo nome.")
                    found.size == 1 -> add(found[0])
                    else -> {
                        val byName = found.filter { c -> p.raw.contains(c.name, ignoreCase = true) }
                        if (byName.size == 1) add(byName[0])
                        else state = ScanState.Choose(if (byName.isNotEmpty()) byName else found)
                    }
                }
            } catch (e: Exception) {
                apiFailed(e)
            }
        }
    }

    private fun apiFailed(e: Exception) {
        retryAt = SystemClock.elapsedRealtime() + 5000
        lockedKey = null
        candKey = null
        candCount = 0
        val code = e.message ?: ""
        fail(
            if (code.startsWith("HTTP 5") || e is java.io.IOException)
                "A API de cartas está instável agora ($code). Tentando de novo em instantes…"
            else "Erro ao consultar a API: $code"
        )
    }

    private fun find(p: Parsed): List<ApiCard> {
        if (p.number != null && p.total != null) {
            val r = PokeApi.search("number:${p.number} set.printedTotal:${p.total}")
            if (r.isNotEmpty()) return r
        }
        for (n in p.names) {
            val r = PokeApi.search(nameQuery(n) + (p.number?.let { " number:$it" } ?: ""))
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    private fun nameQuery(raw: String): String {
        val n = raw.replace("\"", "").trim()
        return if (n.contains(' ')) "name:\"$n\"" else "name:$n*"
    }

    fun searchByText(q: String) {
        state = ScanState.Loading
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { PokeApi.search(nameQuery(q)) }
                if (r.isEmpty()) fail("Nada encontrado para \"$q\".") else state = ScanState.Choose(r)
            } catch (e: Exception) {
                apiFailed(e)
            }
        }
    }

    fun add(c: ApiCard) {
        viewModelScope.launch {
            val ex = dao.byId(c.id)
            val saved: OwnedCard
            if (ex != null) {
                saved = ex.copy(quantity = ex.quantity + 1, price = c.price ?: ex.price)
            } else {
                saved = OwnedCard(
                    id = c.id, name = c.name, setId = c.setId, setName = c.setName, setSymbol = c.setSymbol,
                    number = c.number, printedTotal = c.printedTotal, rarity = c.rarity,
                    imageSmall = c.imageSmall, imageLarge = c.imageLarge, price = c.price
                )
            }
            dao.upsert(saved)
            added = Added(saved, ex == null)
            addedTick++
            message = null
            state = ScanState.Idle
        }
    }

    fun setQty(c: OwnedCard, q: Int) {
        viewModelScope.launch {
            if (q <= 0) dao.delete(c.id) else dao.upsert(c.copy(quantity = q))
        }
    }

    fun remove(c: OwnedCard) {
        viewModelScope.launch { dao.delete(c.id) }
    }

    // ---- atualização automática ----
    fun checkUpdate(manual: Boolean) {
        if (checking) return
        checking = true
        if (manual) updateStatus = "Verificando…"
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Updater.latest() }
                if (info != null && info.versionCode > versionCode) {
                    update = info
                    updateStatus = "Nova versão ${info.versionName} disponível"
                } else if (manual) {
                    updateStatus = "Você já está na versão mais recente ✓"
                }
            } catch (e: Exception) {
                if (manual) updateStatus = "Não consegui verificar: ${e.message}"
            }
            checking = false
        }
    }

    fun dismissUpdate() {
        if (updateProgress == null) update = null
    }

    fun installUpdate() {
        val info = update ?: return
        if (!Updater.canInstall(app)) {
            Updater.openInstallSettings(app)
            updateStatus = "Permita instalar apps do PokéScan, volte e toque em Atualizar de novo."
            return
        }
        updateProgress = 0f
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { Updater.download(app, info) { p -> updateProgress = p } }
                updateProgress = null
                update = null
                Updater.install(app, f)
            } catch (e: Exception) {
                updateProgress = null
                updateStatus = "Falha no download: ${e.message}"
            }
        }
    }
}
