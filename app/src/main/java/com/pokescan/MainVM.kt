package com.pokescan

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

sealed interface ScanState {
    object Idle : ScanState
    object Loading : ScanState
    data class Choose(val cards: List<ApiCard>) : ScanState
}

data class Added(val card: OwnedCard, val isNew: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class MainVM(private val app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    private val prefs = Prefs(app)

    // ---- perfis / sessão ----
    val profiles = dao.profiles().stateIn<List<Profile>?>(viewModelScope, SharingStarted.Eagerly, null)
    var current by mutableStateOf<Profile?>(null)
        private set
    var authError by mutableStateOf<String?>(null)
    var authBusy by mutableStateOf(false)
        private set
    var info by mutableStateOf<String?>(null)
    var bioProfileId by mutableStateOf(prefs.bioProfile)
        private set
    var keepSignedIn by mutableStateOf(prefs.keepSignedIn)
        private set
    /** true enquanto o app reabre a sessão salva (evita piscar a tela de login). */
    var restoring by mutableStateOf(prefs.keepSignedIn && prefs.sessionProfile >= 0)
        private set
    var lockDelay by mutableIntStateOf(prefs.lockDelay)
        private set
    val lastProfileId: Long get() = prefs.lastProfile
    private var holdUntil = 0L

    private val profileFlow = MutableStateFlow(-1L)
    val cards = profileFlow.flatMapLatest { dao.all(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val snapshots = profileFlow.flatMapLatest { dao.snapshots(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        get() = current != null && state == ScanState.Idle && (autoScan || forceNext)

    // ---- reconhecimento por imagem ----
    var visualOn by mutableStateOf(prefs.visualOn)
        private set
    var visualBroken by mutableStateOf(false)
        private set
    var visualError by mutableStateOf<String?>(null)
        private set
    var indexCount by mutableIntStateOf(0)
        private set
    var indexStatus by mutableStateOf<String?>(null)
        private set
    var indexBusy by mutableStateOf(false)
        private set

    /** Área da moldura na tela (atualizada pela tela de scan). */
    @Volatile
    var guide: Guide? = null

    @Volatile
    private var forceVisual = false
    private var visStable: String? = null
    private var visCount = 0
    private var lockedVisual: String? = null
    private var ocrMiss = 0

    val visualReady: Boolean get() = visualOn && indexCount > 0 && !visualBroken
    val wantsVisual: Boolean
        get() = current != null && visualReady && state == ScanState.Idle && guide != null && (autoScan || forceVisual)

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

    init {
        val sid = prefs.sessionProfile
        if (prefs.keepSignedIn && sid >= 0) {
            viewModelScope.launch {
                try {
                    dao.profile(sid)?.let { startSession(it) }
                } catch (e: Exception) {
                    // segue para a tela de login
                }
                restoring = false
            }
        }
    }

    init {
        // Guarda uma foto do valor da coleção por dia (alimenta o gráfico de evolução).
        viewModelScope.launch {
            cards.collect { list ->
                if (list.isNotEmpty()) {
                    val pid = list.first().profileId
                    val day = System.currentTimeMillis() / 86_400_000L
                    dao.upsertSnapshot(Snapshot(pid, day, list.sumOf { valueUsd(it) }))
                }
            }
        }
    }

    init {
        updateIndex(false)
    }

    // ================================================================ perfis e login

    private fun lockLeftMs(id: Long): Long = max(0L, prefs.getLong("lockUntil_$id") - System.currentTimeMillis())

    private fun startSession(p: Profile) {
        current = p
        profileFlow.value = p.id
        prefs.lastProfile = p.id
        prefs.sessionProfile = if (prefs.keepSignedIn) p.id else -1L
        authError = null
        state = ScanState.Idle
        added = null
        message = null
        lockedKey = null
        candKey = null
        candCount = 0
    }

    fun clearAuthError() { authError = null }

    fun createProfile(name: String, avatar: String, pass: String, pass2: String) {
        val n = name.trim()
        authError = when {
            n.isEmpty() -> "Digite um nome."
            n.length > 20 -> "Nome muito longo (máx. 20 letras)."
            pass.length < 4 -> "A senha precisa ter pelo menos 4 caracteres."
            pass != pass2 -> "As senhas não conferem."
            (profiles.value ?: emptyList()).any { it.name.equals(n, ignoreCase = true) } ->
                "Já existe um perfil com esse nome."
            else -> null
        }
        if (authError != null) return
        authBusy = true
        viewModelScope.launch {
            try {
                val salt = Crypto.randomBytes(16)
                val hash = withContext(Dispatchers.Default) { Crypto.hashPassword(pass, salt) }
                val id = dao.insertProfile(Profile(name = n, salt = Crypto.b64(salt), hash = hash, avatar = avatar))
                dao.adoptLegacy(id)
                dao.profile(id)?.let { startSession(it) }
            } catch (e: Exception) {
                authError = "Não consegui criar o perfil: ${e.message}"
            }
            authBusy = false
        }
    }

    fun login(p: Profile, password: String) {
        if (authBusy) return
        val left = lockLeftMs(p.id)
        if (left > 0) {
            authError = "Muitas tentativas. Tente de novo em ${left / 1000 + 1}s."
            return
        }
        authBusy = true
        authError = null
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { Crypto.verify(password, p.salt, p.hash) }
            if (ok) {
                prefs.putInt("fails_${p.id}", 0)
                startSession(p)
            } else {
                val f = prefs.getInt("fails_${p.id}") + 1
                prefs.putInt("fails_${p.id}", f)
                if (f % 5 == 0) {
                    val secs = min(900L, 30L shl (f / 5 - 1).coerceAtMost(5))
                    prefs.putLong("lockUntil_${p.id}", System.currentTimeMillis() + secs * 1000)
                    authError = "Senha incorreta. Bloqueado por ${secs}s."
                } else {
                    authError = "Senha incorreta (${5 - f % 5} tentativas antes do bloqueio)."
                }
            }
            authBusy = false
        }
    }

    fun bioEnabledFor(p: Profile): Boolean = bioProfileId == p.id

    fun loginBiometric(p: Profile) {
        if (bioProfileId == p.id) {
            prefs.putInt("fails_${p.id}", 0)
            startSession(p)
        }
    }

    fun changeBiometric(on: Boolean) {
        val p = current ?: return
        val v = if (on) p.id else -1L
        prefs.bioProfile = v
        bioProfileId = v
    }

    fun changeKeepSignedIn(on: Boolean) {
        keepSignedIn = on
        prefs.keepSignedIn = on
        prefs.sessionProfile = if (on) (current?.id ?: -1L) else -1L
    }

    fun logout() {
        prefs.sessionProfile = -1L
        current = null
        profileFlow.value = -1L
        state = ScanState.Idle
        added = null
        message = null
        info = null
    }

    /** Mantém o app destravado enquanto uma tela externa (ex: seletor de arquivos) está aberta. */
    fun holdLock() { holdUntil = SystemClock.elapsedRealtime() + 180_000 }

    fun autoLockCheck(awayMs: Long) {
        val d = lockDelay
        if (current == null || d < 0) return
        if (SystemClock.elapsedRealtime() < holdUntil) return
        if (awayMs >= d * 1000L) logout()
    }

    fun changeLockDelay(seconds: Int) {
        lockDelay = seconds
        prefs.lockDelay = seconds
    }

    fun changePassword(oldPass: String, newPass: String, newPass2: String, done: (String?) -> Unit) {
        val p = current ?: return
        if (newPass.length < 4) { done("A nova senha precisa ter pelo menos 4 caracteres."); return }
        if (newPass != newPass2) { done("As senhas não conferem."); return }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { Crypto.verify(oldPass, p.salt, p.hash) }
            if (!ok) { done("Senha atual incorreta."); return@launch }
            val salt = Crypto.randomBytes(16)
            val hash = withContext(Dispatchers.Default) { Crypto.hashPassword(newPass, salt) }
            dao.changePassword(p.id, Crypto.b64(salt), hash)
            current = p.copy(salt = Crypto.b64(salt), hash = hash)
            done(null)
        }
    }

    fun updateProfile(name: String, avatar: String): String? {
        val p = current ?: return null
        val n = name.trim()
        if (n.isEmpty()) return "Digite um nome."
        if (n.length > 20) return "Nome muito longo (máx. 20 letras)."
        if ((profiles.value ?: emptyList()).any { it.id != p.id && it.name.equals(n, ignoreCase = true) }) {
            return "Já existe um perfil com esse nome."
        }
        viewModelScope.launch {
            dao.updateProfile(p.id, n, avatar)
            current = p.copy(name = n, avatar = avatar)
        }
        return null
    }

    fun deleteCurrentProfile(password: String, done: (String?) -> Unit) {
        val p = current ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { Crypto.verify(password, p.salt, p.hash) }
            if (!ok) { done("Senha incorreta."); return@launch }
            dao.clear(p.id)
            dao.deleteProfile(p.id)
            if (prefs.bioProfile == p.id) { prefs.bioProfile = -1L; bioProfileId = -1L }
            logout()
            done(null)
        }
    }

    // ================================================================ backup criptografado

    private fun cardToJson(c: OwnedCard) = JSONObject().apply {
        put("id", c.id); put("name", c.name); put("setId", c.setId); put("setName", c.setName)
        put("setSymbol", c.setSymbol); put("number", c.number); put("printedTotal", c.printedTotal)
        put("rarity", c.rarity ?: JSONObject.NULL); put("imageSmall", c.imageSmall)
        put("imageLarge", c.imageLarge); put("price", c.price ?: JSONObject.NULL)
        put("quantity", c.quantity); put("condition", c.condition); put("favorite", c.favorite); put("buyPrice", c.buyPrice ?: JSONObject.NULL); put("addedAt", c.addedAt)
    }

    private fun jsonToCard(pid: Long, o: JSONObject): OwnedCard? {
        val id = o.optString("id")
        val small = o.optString("imageSmall")
        val large = o.optString("imageLarge")
        if (id.isBlank() || id.length > 64 || !small.startsWith("https://") || !large.startsWith("https://")) return null
        val sym = o.optString("setSymbol")
        return OwnedCard(
            profileId = pid, id = id,
            name = o.optString("name").take(80), setId = o.optString("setId").take(40),
            setName = o.optString("setName").take(80),
            setSymbol = if (sym.startsWith("https://")) sym else "",
            number = o.optString("number").take(12), printedTotal = o.optInt("printedTotal"),
            rarity = if (o.isNull("rarity")) null else o.optString("rarity").take(40),
            imageSmall = small, imageLarge = large,
            price = if (o.isNull("price")) null else o.optDouble("price"),
            quantity = o.optInt("quantity", 1).coerceIn(1, 9999),
            condition = o.optString("condition", "NM").let { if (it in Cond.all) it else "NM" },
            favorite = o.optBoolean("favorite", false),
            buyPrice = if (o.isNull("buyPrice")) null else o.optDouble("buyPrice"),
            addedAt = o.optLong("addedAt", System.currentTimeMillis())
        )
    }

    private fun readLimited(ins: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw RuntimeException("Arquivo grande demais")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun exportBackup(uri: Uri, password: String) {
        val p = current ?: return
        info = "Exportando…"
        viewModelScope.launch {
            try {
                val n = withContext(Dispatchers.IO) {
                    val list = dao.snapshot(p.id)
                    val arr = JSONArray()
                    list.forEach { arr.put(cardToJson(it)) }
                    val root = JSONObject().put("v", 1).put("name", p.name).put("avatar", p.avatar).put("cards", arr)
                    val enc = Crypto.encrypt(root.toString().toByteArray(), password)
                    app.contentResolver.openOutputStream(uri)!!.use { it.write(enc) }
                    list.size
                }
                info = "✓ Backup criptografado salvo ($n cartas). Guarde a senha do backup!"
            } catch (e: Exception) {
                info = "Falha ao exportar: ${e.message}"
            }
        }
    }

    fun importBackup(uri: Uri, password: String) {
        val pid = current?.id ?: return
        info = "Importando…"
        viewModelScope.launch {
            try {
                val n = withContext(Dispatchers.IO) {
                    val data = app.contentResolver.openInputStream(uri)!!.use { readLimited(it, 20_000_000) }
                    val plain = try {
                        Crypto.decrypt(data, password)
                    } catch (e: Exception) {
                        throw RuntimeException("Senha incorreta ou arquivo inválido")
                    }
                    val arr = JSONObject(String(plain)).getJSONArray("cards")
                    if (arr.length() > 20_000) throw RuntimeException("Backup grande demais")
                    val existing = dao.snapshot(pid).associateBy { it.id }
                    val toSave = (0 until arr.length()).mapNotNull { i -> jsonToCard(pid, arr.getJSONObject(i)) }
                        .map { c ->
                            val ex = existing[c.id]
                            if (ex == null) c else c.copy(quantity = max(ex.quantity, c.quantity), price = c.price ?: ex.price)
                        }
                    dao.upsertAll(toSave)
                    toSave.size
                }
                info = "✓ $n cartas importadas para este perfil."
            } catch (e: Exception) {
                info = "Falha ao importar: ${e.message}"
            }
        }
    }

    // ================================================================ moeda e ajustes

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

    // ================================================================ escaneamento

    fun dismiss() { state = ScanState.Idle }
    fun clearAdded() { added = null }
    fun scanNow() { forceNext = true; message = null }

    fun fail(msg: String) {
        state = ScanState.Idle
        message = msg
    }

    /** Chamado a cada quadro lido pela câmera. Confirma a carta quando o mesmo número aparece 2x seguidas. */
    fun onFrame(p: Parsed) {
        if (current == null || state != ScanState.Idle) return
        val now = SystemClock.elapsedRealtime()
        if (now < retryAt) return
        if (forceNext) {
            forceNext = false
            onOcr(p)
            return
        }
        val key = if (p.number != null && p.total != null) "${p.number}/${p.total}" else null
        if (key == null) {
            ocrMiss++
            if (now - lastSeen > 1500) lockedKey = null
            candKey = null
            candCount = 0
            return
        }
        lastSeen = now
        ocrMiss = 0
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

    private fun eurPerUsd(): Double = rates["EUR"] ?: 0.92

    private fun find(p: Parsed): List<ApiCard> {
        var failure: Exception? = null
        try {
            if (p.number != null && p.total != null) {
                val r = PokeApi.search("number:${p.number} set.printedTotal:${p.total}")
                if (r.isNotEmpty()) return r
            }
            for (n in p.names) {
                val r = PokeApi.search(nameQuery(n) + (p.number?.let { " number:$it" } ?: ""))
                if (r.isNotEmpty()) return r
            }
        } catch (e: Exception) {
            failure = e
        }
        // Base reserva (TCGdex, com nomes em português): API principal fora do ar ou sem a carta.
        if (p.number != null && p.total != null) {
            val alt = try {
                TcgDex.findByNumber(p.number, p.total, eurPerUsd())
            } catch (e: Exception) {
                emptyList()
            }
            if (alt.isNotEmpty()) return alt
        }
        // Poke API Brasil: acha pelo nome (inclusive em português) e filtra pelo número lido, se houver.
        for (n in p.names.take(2)) {
            val r = try { PokeApiBr.searchCards(n, 20) } catch (e: Exception) { emptyList() }
            val f = if (p.number != null) r.filter { it.number.trimStart('0') == p.number } else r
            if (f.isNotEmpty()) return f
        }
        if (failure != null) throw failure
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
        val pid = current?.id ?: return
        viewModelScope.launch {
            val ex = dao.byId(pid, c.id)
            val saved: OwnedCard
            if (ex != null) {
                saved = ex.copy(quantity = ex.quantity + 1, price = c.price ?: ex.price)
            } else {
                saved = OwnedCard(
                    profileId = pid, id = c.id, name = c.name, setId = c.setId, setName = c.setName,
                    setSymbol = c.setSymbol, number = c.number, printedTotal = c.printedTotal,
                    rarity = c.rarity, imageSmall = c.imageSmall, imageLarge = c.imageLarge, price = c.price
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
            if (q <= 0) dao.delete(c.profileId, c.id) else dao.upsert(c.copy(quantity = q))
        }
    }

    fun remove(c: OwnedCard) {
        viewModelScope.launch { dao.delete(c.profileId, c.id) }
    }


    // ================================================================ valor, condição e catálogo

    /** Valor de UMA unidade da carta (em dólar), já ajustado pela condição. */
    fun unitValueUsd(c: OwnedCard): Double? = c.price?.let { it * Cond.factor(c.condition) }

    /** Valor total da carta (em dólar): unidade x condição x quantidade. */
    fun valueUsd(c: OwnedCard): Double = (unitValueUsd(c) ?: 0.0) * c.quantity

    fun setCondition(c: OwnedCard, code: String) {
        viewModelScope.launch { dao.upsert(c.copy(condition = code)) }
    }

    /** Cotação de 1 unidade da moeda [code] na moeda escolhida (ex: "US$ 1 = R$ 5,52"). */
    fun quote(code: String): String = money(1.0 / (rates[code] ?: 1.0))

    /** Cadeia de fontes: TCGdex pt-br -> TCGdex pt -> Poke API Brasil -> TCGdex en. */
    suspend fun catalogSearch(q: String): CatalogResult = withContext(Dispatchers.IO) {
        var firstError: Exception? = null
        for (lang in listOf("pt-br", "pt")) {
            try {
                val r = TcgDex.search(lang, q)
                if (r.isNotEmpty()) return@withContext CatalogResult(r, null)
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        try {
            val r = PokeApiBr.search(q)
            if (r.isNotEmpty()) return@withContext CatalogResult(r, "Resultados da Poke API Brasil.")
        } catch (e: Exception) {
            if (firstError == null) firstError = e
        }
        try {
            val r = TcgDex.search("en", q)
            if (r.isNotEmpty()) return@withContext CatalogResult(r, "Sem resultado em PT-BR: mostrando o catálogo em inglês.")
        } catch (e: Exception) {
            if (firstError == null) firstError = e
        }
        if (firstError != null) throw firstError
        CatalogResult(emptyList(), null)
    }

    suspend fun catalogDetail(h: CatalogHit): ApiCard? = withContext(Dispatchers.IO) {
        h.card?.let { return@withContext it }
        var d = TcgDex.detail(h.lang, h.id, eurPerUsd())
        if (d == null && h.lang == "pt-br") d = TcgDex.detail("pt", h.id, eurPerUsd())
        if (d != null && d.price == null && h.lang != "en") {
            val e = TcgDex.detail("en", h.id, eurPerUsd())
            if (e?.price != null) d = d.copy(price = e.price)
        }
        d
    }

    fun addCatalogCard(c: ApiCard, condition: String, qty: Int) {
        val pid = current?.id ?: return
        viewModelScope.launch {
            val ex = dao.byId(pid, c.id)
            val saved = if (ex != null) {
                ex.copy(quantity = ex.quantity + qty, price = c.price ?: ex.price)
            } else {
                OwnedCard(
                    profileId = pid, id = c.id, name = c.name, setId = c.setId, setName = c.setName,
                    setSymbol = c.setSymbol, number = c.number, printedTotal = c.printedTotal,
                    rarity = c.rarity, imageSmall = c.imageSmall, imageLarge = c.imageLarge,
                    price = c.price, quantity = qty, condition = condition
                )
            }
            dao.upsert(saved)
        }
    }


    // ================================================================ favoritas, preço pago e exportação

    fun toCurrency(usd: Double): Double = usd * (rates[currency] ?: 1.0)

    fun toggleFavorite(c: OwnedCard) {
        viewModelScope.launch { dao.upsert(c.copy(favorite = !c.favorite)) }
    }

    /** [text] é o preço pago por unidade, na moeda escolhida. Vazio apaga. */
    fun setBuyPrice(c: OwnedCard, text: String) {
        val t = text.trim().replace(',', '.')
        val usd: Double? = if (t.isEmpty()) null else t.toDoubleOrNull()?.let { it / (rates[currency] ?: 1.0) }
        if (t.isNotEmpty() && usd == null) return
        viewModelScope.launch { dao.upsert(c.copy(buyPrice = usd)) }
    }

    private fun csvField(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    private fun csvNum(d: Double?) = if (d == null) "" else String.format(Locale("pt", "BR"), "%.2f", d)

    private fun toCsv(list: List<OwnedCard>): ByteArray {
        val sb = StringBuilder("\uFEFF")
        sb.append("Nome;Set;Número;Raridade;Condição;Quantidade;Preço unitário ($currency);Valor total ($currency);")
        sb.append("Preço pago ($currency);Lucro/prejuízo ($currency);Favorita\n")
        for (c in list) {
            val unit = unitValueUsd(c)?.let { toCurrency(it) }
            val total = unit?.let { it * c.quantity }
            val paid = c.buyPrice?.let { toCurrency(it) }
            val pnl = if (unit != null && paid != null) (unit - paid) * c.quantity else null
            sb.append(
                listOf(
                    csvField(c.name), csvField(c.setName), csvField(numLabel(c.number, c.printedTotal)),
                    csvField(c.rarity ?: ""), c.condition, "${c.quantity}", csvNum(unit), csvNum(total),
                    csvNum(paid), csvNum(pnl), if (c.favorite) "sim" else "não"
                ).joinToString(";")
            ).append("\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun toJsonExport(list: List<OwnedCard>): ByteArray {
        val arr = JSONArray()
        list.forEach { arr.put(cardToJson(it)) }
        val root = JSONObject().put("app", "PokéScan").put("moeda", currency).put("cartas", arr)
        return root.toString(2).toByteArray(Charsets.UTF_8)
    }

    fun exportCollection(uri: Uri, json: Boolean) {
        val p = current ?: return
        info = "Exportando…"
        viewModelScope.launch {
            try {
                val n = withContext(Dispatchers.IO) {
                    val list = dao.snapshot(p.id).sortedBy { it.name.lowercase() }
                    val bytes = if (json) toJsonExport(list) else toCsv(list)
                    app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                    list.size
                }
                info = "✓ $n cartas exportadas em ${if (json) "JSON" else "CSV"}."
            } catch (e: Exception) {
                info = "Falha ao exportar: ${e.message}"
            }
        }
    }


    // ================================================================ reconhecimento por imagem

    fun changeVisual(on: Boolean) {
        visualOn = on
        prefs.visualOn = on
        if (on) { visualBroken = false; visualError = null }
    }

    fun onVisualBroken(msg: String) {
        visualBroken = true
        visualError = msg
    }

    private fun describeIndex(): String = "Índice com ${VisualIndex.data?.n ?: 0} cartas."

    private fun isMetered(): Boolean = try {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (e: Exception) {
        false
    }

    /** Carrega o índice salvo e, se houver versão nova (ou nenhum), baixa. [manual] = botão em Ajustes. */
    fun updateIndex(manual: Boolean) {
        if (indexBusy) return
        indexBusy = true
        viewModelScope.launch {
            try {
                if (indexCount == 0) {
                    val ok = withContext(Dispatchers.IO) { VisualIndex.load(app) }
                    if (ok) { indexCount = VisualIndex.data?.n ?: 0; indexStatus = describeIndex() }
                }
                if (manual) indexStatus = "Procurando o índice…"
                val r = withContext(Dispatchers.IO) { IndexUpdater.remote() }
                if (r == null) {
                    if (manual || indexCount == 0) indexStatus = "O índice ainda não foi publicado. Tente mais tarde."
                    return@launch
                }
                if (indexCount == 0 || prefs.indexStamp != r.stamp) {
                    if (!manual && isMetered()) {
                        indexStatus = "Há um índice novo (${r.size / 1_000_000} MB). Baixe no Wi-Fi em Perfil."
                        return@launch
                    }
                    indexStatus = "Baixando o índice…"
                    val ok = withContext(Dispatchers.IO) { IndexUpdater.download(app, r) && VisualIndex.load(app) }
                    if (ok) {
                        prefs.indexStamp = r.stamp
                        indexCount = VisualIndex.data?.n ?: 0
                        visualBroken = false
                        visualError = null
                        indexStatus = describeIndex()
                    } else {
                        indexStatus = "Falha ao instalar o índice."
                    }
                } else if (manual) {
                    indexStatus = "O índice já está atualizado. " + describeIndex()
                }
            } catch (e: Exception) {
                if (manual) indexStatus = "Não consegui baixar o índice: ${e.message}"
            } finally {
                indexBusy = false
            }
        }
    }

    /** Botão 🧠: identifica a carta pela imagem, agora. */
    fun identifyVisual() {
        if (current == null || state != ScanState.Idle) return
        if (!visualOn) { message = "Ligue o reconhecimento por imagem em Perfil."; return }
        if (indexCount == 0) { message = "Baixe o índice de imagens em Perfil → Reconhecimento por imagem."; return }
        if (visualBroken) { message = visualError ?: "Reconhecimento por imagem indisponível."; return }
        forceVisual = true
        message = null
        viewModelScope.launch {
            delay(4000)
            if (forceVisual) {
                forceVisual = false
                message = "Não consegui ver a carta. Enquadre-a na moldura e tente de novo."
            }
        }
    }

    /** Resultado do reconhecimento por imagem (as cartas mais parecidas, da mais para a menos). */
    fun onVisual(hits: List<VisualHit>) {
        if (current == null || state != ScanState.Idle || hits.isEmpty()) return
        if (forceVisual) {
            forceVisual = false
            showVisualChoices(hits)
            return
        }
        val top = hits[0]
        val thr = VisualIndex.data?.thr ?: 0.8f
        if (!autoScan || ocrMiss < 3 || top.score < thr) {
            visCount = 0
            if (top.score < thr) lockedVisual = null
            return
        }
        if (top.entry.id == visStable) visCount++ else { visStable = top.entry.id; visCount = 1 }
        if (visCount >= 3 && top.entry.id != lockedVisual) {
            lockedVisual = top.entry.id
            visCount = 0
            showVisualChoices(hits)
        }
    }

    private fun detailAny(id: String): ApiCard? = try {
        TcgDex.detail("pt-br", id, eurPerUsd()) ?: TcgDex.detail("pt", id, eurPerUsd()) ?: TcgDex.detail("en", id, eurPerUsd())
    } catch (e: Exception) {
        null
    }

    private fun showVisualChoices(hits: List<VisualHit>) {
        state = ScanState.Loading
        viewModelScope.launch {
            val cards = withContext(Dispatchers.IO) { hits.take(6).mapNotNull { detailAny(it.entry.id) } }
            if (cards.isEmpty()) fail("Reconheci a arte, mas não consegui abrir as cartas. Tente de novo.")
            else state = ScanState.Choose(cards)
        }
    }

    // ================================================================ atualização automática

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
                if (!Updater.signatureOk(app, f)) {
                    f.delete()
                    updateStatus = "Atualização recusada: a assinatura não confere com a do app instalado."
                    return@launch
                }
                update = null
                Updater.install(app, f)
            } catch (e: Exception) {
                updateProgress = null
                updateStatus = "Falha no download: ${e.message}"
            }
        }
    }
}
