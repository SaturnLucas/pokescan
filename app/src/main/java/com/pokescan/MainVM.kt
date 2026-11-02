package com.pokescan

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ScanState {
    object Idle : ScanState
    object Loading : ScanState
    data class Choose(val cards: List<ApiCard>) : ScanState
}

class MainVM(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()

    val cards = dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var state by mutableStateOf<ScanState>(ScanState.Idle)
        private set
    var message by mutableStateOf<String?>(null)

    fun begin() { state = ScanState.Loading }
    fun dismiss() { state = ScanState.Idle }
    fun fail(msg: String) { state = ScanState.Idle; message = msg }

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
                    found.isEmpty() -> fail("Nenhuma carta encontrada. Tente de novo ou busque pelo nome.")
                    found.size == 1 -> add(found[0])
                    else -> {
                        val byName = found.filter { c -> p.raw.contains(c.name, ignoreCase = true) }
                        if (byName.size == 1) add(byName[0])
                        else state = ScanState.Choose(if (byName.isNotEmpty()) byName else found)
                    }
                }
            } catch (e: Exception) {
                fail("Erro ao consultar a API: ${e.message}")
            }
        }
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
                fail("Erro ao consultar a API: ${e.message}")
            }
        }
    }

    fun add(c: ApiCard) {
        viewModelScope.launch {
            val ex = dao.byId(c.id)
            if (ex != null) {
                dao.upsert(ex.copy(quantity = ex.quantity + 1, price = c.price ?: ex.price))
                message = "${c.name} (${c.setName}) — agora você tem ${ex.quantity + 1}"
            } else {
                dao.upsert(
                    OwnedCard(
                        id = c.id, name = c.name, setId = c.setId, setName = c.setName,
                        number = c.number, printedTotal = c.printedTotal, rarity = c.rarity,
                        imageSmall = c.imageSmall, imageLarge = c.imageLarge, price = c.price
                    )
                )
                message = "✓ ${c.name} adicionada à Pokédex"
            }
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
}
