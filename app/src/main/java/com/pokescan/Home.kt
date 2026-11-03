package com.pokescan

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

// ================================================================ casca do app

@Composable
fun App(vm: MainVM) {
    var tab by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val tabs = listOf("🏠" to "Início", "🌐" to "Mercado", "📷" to "Scan", "🗂️" to "Coleção", "👤" to "Perfil")

    LaunchedEffect(Unit) { vm.checkUpdate(false) }
    BackHandler(enabled = overlay == null && tab != 0) { tab = 0 }
    BackHandler(enabled = overlay != null) { overlay = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(GoldLine))
                NavigationBar(containerColor = Color(0xFF0D0D0F)) {
                    tabs.forEachIndexed { i, (emoji, label) ->
                        NavigationBarItem(
                            selected = overlay == null && tab == i,
                            onClick = { overlay = null; tab = i },
                            icon = { Text(emoji, fontSize = 20.sp) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = Color(0xFF2A2312)
                            )
                        )
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val ov = overlay
            if (ov != null) {
                if (ov == "buscar") BuscarScreen(vm) { overlay = null }
                else PreGradeScreen(vm) { overlay = null }
            } else {
                if (tab != 2) {
                    TopHeader(
                        vm,
                        onMenu = { dialog = "menu" },
                        onHelp = { dialog = "help" },
                        onBell = { dialog = "bell" },
                        onAvatar = { tab = 4 }
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> HomeScreen(
                            vm,
                            onScan = { tab = 2 },
                            onBuscar = { overlay = "buscar" },
                            onPre = { overlay = "pre" }
                        )
                        1 -> MarketScreen(vm)
                        2 -> ScanScreen(vm)
                        3 -> CollectionScreen(vm)
                        else -> SettingsScreen(vm)
                    }
                }
            }
        }
    }

    UpdateDialog(vm)

    when (dialog) {
        "menu" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Menu") },
            text = {
                Column {
                    listOf(
                        Triple("🏠  Início", 0, null),
                        Triple("🌐  Mercado (consultar valores)", 1, null),
                        Triple("🗂️  Minha coleção", 3, null),
                        Triple("✨  Pré-avaliar condição", -1, "pre"),
                        Triple("🔍  Buscar no catálogo PT-BR", -1, "buscar"),
                        Triple("👤  Perfil, backup e ajustes", 4, null)
                    ).forEach { (label, t, ov) ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { dialog = null; overlay = ov; if (t >= 0) tab = t }
                        ) { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Fechar") } }
        )
        "help" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Como usar") },
            text = {
                Text(
                    "📷 Scan: aponte para a carta com o número visível (ex: 025/165). Ela entra sozinha na coleção.\n\n" +
                        "🔍 Buscar: ache qualquer carta pelo nome no catálogo PT-BR e adicione.\n\n" +
                        "🌐 Mercado: veja o valor por condição (NM, SP, MP, HP, D).\n\n" +
                        "✨ Pré-avaliar: responda 4 perguntas sobre a carta e veja a condição estimada.\n\n" +
                        "💾 Perfil: faça backup criptografado do deck e escolha a moeda.",
                    fontSize = 14.sp
                )
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Entendi") } }
        )
        "bell" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Novidades") },
            text = {
                Text(
                    if (vm.update != null) "Há uma nova versão do PokéScan (${vm.update?.versionName})."
                    else vm.updateStatus ?: "Você está na versão ${vm.versionName}. Toque em Verificar para procurar atualizações."
                )
            },
            confirmButton = {
                if (vm.update != null) Button(onClick = { dialog = null; vm.installUpdate() }) { Text("Atualizar") }
                else TextButton(onClick = { vm.checkUpdate(true) }) { Text("Verificar") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Fechar") } }
        )
    }
}

// ================================================================ cabeçalho

@Composable
fun TopHeader(vm: MainVM, onMenu: () -> Unit, onHelp: () -> Unit, onBell: () -> Unit, onAvatar: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onMenu), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.dp, 16.dp)) {
                    val w = size.width
                    listOf(1.5f, size.height / 2, size.height - 1.5f).forEach { y ->
                        drawLine(Color(0xFFE8E3D6), Offset(0f, y), Offset(w, y), 2.5.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            AppLogo(34.dp)
            Spacer(Modifier.width(10.dp))
            Text("POKÉSCAN", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, letterSpacing = 3.sp, color = PokeYellow)
            Text(" TCG", fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(34.dp).border(1.5.dp, Color(0xFFE8E3D6), CircleShape).clip(CircleShape).clickable(onClick = onHelp),
                contentAlignment = Alignment.Center
            ) { Text("?", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBell), contentAlignment = Alignment.Center) {
                Text("🔔", fontSize = 20.sp)
                if (vm.update != null) {
                    Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(9.dp).clip(CircleShape).background(PokeYellow))
                }
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.5.dp, PokeYellow, CircleShape).clickable(onClick = onAvatar),
                contentAlignment = Alignment.Center
            ) { Text(vm.current?.avatar ?: "👤", fontSize = 20.sp) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(GoldLine))
    }
}

@Composable
fun Kicker(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = PokeYellow, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = 2.sp)
}

@Composable
fun Pill(text: String, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) PokeYellow else Color(0xFF1C1912),
        border = BorderStroke(1.dp, if (selected) PokeYellow else GoldLine),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick)
    ) {
        Text(
            text,
            Modifier.padding(horizontal = if (compact) 10.dp else 16.dp, vertical = 8.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}

// ================================================================ início

@Composable
fun HomeScreen(vm: MainVM, onScan: () -> Unit, onBuscar: () -> Unit, onPre: () -> Unit) {
    val cards by vm.cards.collectAsState()
    val totalUsd = cards.sumOf { vm.valueUsd(it) }
    val top = cards.filter { it.price != null }.sortedByDescending { vm.unitValueUsd(it) ?: 0.0 }.take(6)
    val condLabel = if (cards.all { it.condition == "NM" }) "NM" else "Por condição"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Kicker("POKÉSCAN TCG")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(PokeGreen))
                Text("  Catálogo PT-BR", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Seu acervo em um só lugar", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Identifique cartas, acompanhe sua coleção e consulte o valor de mercado.",
            fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp
        )
        Spacer(Modifier.height(20.dp))

        // cartão de valor
        val shape = RoundedCornerShape(26.dp)
        Box(
            Modifier.fillMaxWidth().clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF211A0E), Color(0xFF0E0D0A))))
                .border(1.dp, GoldLine, shape)
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(
                    PokeYellow.copy(alpha = 0.10f), radius = size.height * 0.52f,
                    center = Offset(size.width * 0.74f, size.height * 0.42f), style = Stroke(width = 38.dp.toPx())
                )
                drawLine(
                    PokeYellow.copy(alpha = 0.10f), Offset(size.width * 0.80f, size.height * 0.60f),
                    Offset(size.width * 0.95f, size.height * 0.80f), 30.dp.toPx(), StrokeCap.Round
                )
            }
            Column(Modifier.padding(22.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(PokeYellow))
                        Text("  Valor da coleção", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(
                        shape = RoundedCornerShape(50), color = PokeYellow.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, PokeYellow.copy(alpha = 0.55f))
                    ) {
                        Text(
                            "$condLabel · ${vm.currency}", Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            color = PokeYellow, fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(vm.money(totalUsd), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(GoldLine))
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HomeStat("Cartas", "${cards.sumOf { it.quantity }}")
                    HomeStat("Posições", "${cards.size}")
                    HomeStat("Com valor", "${cards.count { it.price != null }}")
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile("📷", "Escanear", "Pela câmera", Modifier.weight(1f), onScan)
            ActionTile("🔍", "Buscar", "Catálogo PT-BR", Modifier.weight(1f), onBuscar)
            ActionTile("✨", "Pré-avaliar", "Condição física", Modifier.weight(1f), onPre)
        }

        Spacer(Modifier.height(26.dp))
        Kicker("DESTAQUES DA COLEÇÃO")
        Spacer(Modifier.height(6.dp))
        Text("Suas cartas mais caras", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(12.dp))
        if (top.isEmpty()) {
            Text(
                "Escaneie ou busque suas primeiras cartas para ver o ranking de valor.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(top.size) { i -> RankCard(vm, i + 1, top[i]) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun HomeStat(label: String, value: String) {
    Column {
        Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun ActionTile(icon: String, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape).background(PanelBg).border(1.dp, GoldLine, shape).clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 24.sp)
        Spacer(Modifier.height(6.dp))
        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1)
        Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text("›", fontSize = 24.sp, color = PokeYellow)
    }
}

@Composable
fun RankCard(vm: MainVM, rank: Int, c: OwnedCard) {
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.width(118.dp).clip(shape).background(PanelBg).border(1.dp, GoldLine, shape).padding(8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(
                model = c.imageSmall, contentDescription = c.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(CARD_RATIO).clip(RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.height(6.dp))
            Text(c.name, fontSize = 12.sp, maxLines = 1)
            Text(vm.money(vm.unitValueUsd(c)), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = PokeYellow, maxLines = 1)
        }
        Box(
            Modifier.align(Alignment.TopStart).size(26.dp).clip(CircleShape).background(PokeYellow),
            contentAlignment = Alignment.Center
        ) { Text("${rank}º", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimary) }
    }
}

// ================================================================ coleção

@Composable
fun CollectionScreen(vm: MainVM) {
    var seg by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Kicker("COLEÇÃO")
            Spacer(Modifier.height(4.dp))
            Text("Seu acervo", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Cartas", seg == 0) { seg = 0 }
                Pill("Sets", seg == 1) { seg = 1 }
            }
        }
        Box(Modifier.weight(1f)) { if (seg == 0) DexScreen(vm) else SetsScreen(vm) }
    }
}

// ================================================================ mercado e buscar

@Composable
fun MarketScreen(vm: MainVM) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
            Kicker("MERCADO")
            Spacer(Modifier.height(4.dp))
            Text("Consulte valores", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Preço de referência (TCGplayer/Cardmarket) em ${vm.currency}, para cada condição.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuoteChip("US$ 1 = ${vm.quote("USD")}")
                QuoteChip("€ 1 = ${vm.quote("EUR")}")
            }
        }
        CatalogPanel(vm, Modifier.weight(1f))
    }
}

@Composable
fun QuoteChip(text: String) {
    Surface(shape = RoundedCornerShape(50), color = PanelBg, border = BorderStroke(1.dp, GoldLine)) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp, color = PokeYellow)
    }
}

@Composable
fun BackBar(kicker: String, title: String, onBack: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Text("←", fontSize = 24.sp)
            }
            Column(Modifier.padding(start = 6.dp)) {
                Kicker(kicker)
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(GoldLine))
    }
}

@Composable
fun BuscarScreen(vm: MainVM, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BackBar("BUSCAR", "Catálogo PT-BR", onBack)
        Spacer(Modifier.height(10.dp))
        CatalogPanel(vm, Modifier.weight(1f))
    }
}

@Composable
fun CatalogPanel(vm: MainVM, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<CatalogHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<ApiCard?>(null) }

    fun search() {
        val q = query.trim()
        if (q.length < 3) { error = "Digite pelo menos 3 letras."; return }
        busy = true
        error = null
        note = null
        scope.launch {
            try {
                val r = vm.catalogSearch(q)
                hits = r.hits
                note = r.note
                if (r.hits.isEmpty()) error = "Nenhuma carta encontrada para \"$q\"."
            } catch (e: Exception) {
                error = "Catálogo indisponível agora (${e.message}). Tente de novo."
            }
            busy = false
        }
    }

    fun open(h: CatalogHit) {
        busy = true
        error = null
        scope.launch {
            try {
                val d = vm.catalogDetail(h)
                if (d == null) error = "Não consegui abrir essa carta." else detail = d
            } catch (e: Exception) {
                error = "Erro ao abrir a carta (${e.message})."
            }
            busy = false
        }
    }

    Column(modifier) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Nome da carta (PT-BR ou EN)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() })
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = !busy, onClick = { search() }) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Buscar")
            }
        }
        note?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
        if (hits.isEmpty() && error == null && !busy) {
            Text(
                "Digite o nome de uma carta para ver o catálogo, o valor por condição e adicionar à coleção.",
                Modifier.padding(16.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)
        ) {
            items(hits, key = { it.lang + it.id }) { h ->
                Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open(h) }) {
                    AsyncImage(
                        model = h.thumb,
                        contentDescription = h.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(CARD_RATIO).clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1C1912))
                    )
                    Text(h.name, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                    Text(h.number, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    detail?.let { CatalogDialog(vm, it) { detail = null } }
}

@Composable
fun CatalogDialog(vm: MainVM, c: ApiCard, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var cond by remember { mutableStateOf("NM") }
    var qty by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(c.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AsyncImage(
                    model = c.imageLarge, contentDescription = c.name,
                    modifier = Modifier.fillMaxWidth().aspectRatio(CARD_RATIO)
                )
                Text("${c.setName} · ${numLabel(c.number, c.printedTotal)}", fontSize = 13.sp)
                c.rarity?.let { Tag(it, rarityColor(it)) }
                Surface(shape = RoundedCornerShape(14.dp), color = PanelBg, border = BorderStroke(1.dp, GoldLine)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Valor por condição", fontSize = 12.sp, color = PokeYellow, fontWeight = FontWeight.Bold)
                        if (c.price == null) {
                            Text("Sem preço de referência disponível para esta carta.", fontSize = 13.sp)
                        }
                        Cond.all.forEach { code ->
                            val sel = code == cond
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { cond = code }
                                    .background(if (sel) PokeYellow.copy(alpha = 0.14f) else Color.Transparent)
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("$code · ${Cond.label(code)}", fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                Text(vm.money(c.price?.times(Cond.factor(code))), fontSize = 13.sp, color = PokeYellow, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { if (qty > 1) qty-- }) { Text("−") }
                    Text("  $qty  ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { qty++ }) { Text("+") }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.addCatalogCard(c, cond, qty)
                Toast.makeText(ctx, "✓ ${c.name} adicionada à coleção", Toast.LENGTH_SHORT).show()
                onClose()
            }) { Text("Adicionar à coleção") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Fechar") } }
    )
}

// ================================================================ pré-avaliar condição

private val gradeCriteria = listOf(
    Triple("Cantos", "Olhe as 4 pontas da carta", listOf("Perfeitos", "Leve desgaste", "Desgastados", "Amassados")),
    Triple("Bordas", "Veja as laterais, principalmente no verso", listOf("Limpas", "Pontos brancos", "Brancas visíveis", "Lascadas")),
    Triple("Superfície", "Incline a carta contra a luz", listOf("Impecável", "Riscos leves", "Riscos visíveis", "Marcas/dobras")),
    Triple("Centralização", "Compare as margens esquerda e direita", listOf("Ótima", "Boa", "Irregular", "Muito torta"))
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreGradeScreen(vm: MainVM, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cards by vm.cards.collectAsState()
    val levels = remember { mutableStateListOf(0, 0, 0, 0) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = cards.find { it.id == selectedId }
    val code = Cond.fromScores(levels.toList())

    Column(Modifier.fillMaxSize()) {
        BackBar("PRÉ-AVALIAR", "Condição física", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Responda 4 perguntas olhando a carta. É uma estimativa para o valor — não substitui uma gradação oficial.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (cards.isNotEmpty()) {
                Text("Carta (opcional)", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cards, key = { it.id }) { c ->
                        val sel = c.id == selectedId
                        AsyncImage(
                            model = c.imageSmall, contentDescription = c.name,
                            modifier = Modifier.width(68.dp).aspectRatio(CARD_RATIO).clip(RoundedCornerShape(6.dp))
                                .border(if (sel) 2.5.dp else 1.dp, if (sel) PokeYellow else GoldLine, RoundedCornerShape(6.dp))
                                .clickable { selectedId = if (sel) null else c.id }
                        )
                    }
                }
            }

            gradeCriteria.forEachIndexed { i, (title, hint, options) ->
                Surface(shape = RoundedCornerShape(18.dp), color = PanelBg, border = BorderStroke(1.dp, GoldLine)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            options.forEachIndexed { lv, label -> Pill(label, levels[i] == lv) { levels[i] = lv } }
                        }
                    }
                }
            }

            Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF1B160B), border = BorderStroke(1.dp, PokeYellow.copy(alpha = 0.6f))) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Condição estimada", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(code, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = PokeYellow)
                    Text(Cond.label(code), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Fator de valor: ${"%.0f".format(Cond.factor(code) * 100)}% do preço Near Mint",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (selected != null && selected.price != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(selected.name, fontSize = 14.sp)
                        Text(
                            vm.money(selected.price * Cond.factor(code)),
                            fontSize = 26.sp, fontWeight = FontWeight.ExtraBold
                        )
                        Text("em NM: ${vm.money(selected.price)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = selected != null,
                onClick = {
                    selected?.let {
                        vm.setCondition(it, code)
                        Toast.makeText(ctx, "✓ ${it.name} marcada como $code", Toast.LENGTH_SHORT).show()
                    }
                }
            ) { Text(if (selected != null) "Aplicar $code a ${selected.name}" else "Escolha uma carta para aplicar") }
        }
    }
}
