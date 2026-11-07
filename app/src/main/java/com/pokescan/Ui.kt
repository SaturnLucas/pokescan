package com.pokescan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val CARD_RATIO = 0.716f

/** Moldura do scan: [esquerda, topo, largura, altura] em pixels, para uma área de w x h. */
fun guideRect(w: Float, h: Float): FloatArray {
    val maxW = w * 0.82f
    val maxH = h * 0.66f
    val fw = if (maxW / CARD_RATIO > maxH) maxH * CARD_RATIO else maxW
    val fh = fw / CARD_RATIO
    return floatArrayOf((w - fw) / 2f, (h - fh) / 2f - h * 0.05f, fw, fh)
}

// ---------------------------------------------------------------- app

@Composable
fun UpdateDialog(vm: MainVM) {
    val u = vm.update ?: return
    AlertDialog(
        onDismissRequest = { vm.dismissUpdate() },
        title = { Text("Nova versão ${u.versionName} 🎉") },
        text = {
            Column {
                Text(if (u.notes.isBlank()) "Há uma atualização do PokéScan." else u.notes, maxLines = 8)
                vm.updateProgress?.let { p ->
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("Baixando… ${(p * 100).toInt()}%", fontSize = 12.sp)
                }
                vm.updateStatus?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(enabled = vm.updateProgress == null, onClick = { vm.installUpdate() }) { Text("Atualizar agora") }
        },
        dismissButton = {
            TextButton(enabled = vm.updateProgress == null, onClick = { vm.dismissUpdate() }) { Text("Depois") }
        }
    )
}

// ---------------------------------------------------------------- pequenos componentes

@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(50), color = color, modifier = modifier) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            color = Color(0xFF111111),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun RoundIcon(emoji: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(54.dp).clip(CircleShape).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 22.sp) }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

fun rarityColor(r: String?): Color {
    val s = (r ?: "").lowercase()
    return when {
        s.contains("secret") || s.contains("hyper") || s.contains("special") || s.contains("illustration") -> Color(0xFFFF8AD8)
        s.contains("ultra") || s.contains("ex") || s.contains("gx") || s.contains(" v") || s.contains("vmax") -> Color(0xFFB794F6)
        s.contains("holo") || s.contains("rare") -> Color(0xFF63B3ED)
        s.contains("uncommon") -> Color(0xFF68D391)
        else -> Color(0xFFCBD5E0)
    }
}

// ---------------------------------------------------------------- escanear

@Composable
fun ScanScreen(vm: MainVM) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("📷", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            Text("Precisamos da câmera para escanear as cartas.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Permitir câmera") }
        }
        return
    }

    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val analyzer = remember {
        FrameAnalyzer(
            ctx.applicationContext, recognizer, { vm.wantsFrames }, { vm.onFrame(it) },
            { vm.wantsVisual }, { vm.guide }, { vm.onVisual(it) }, { vm.onVisualBroken(it) }
        )
    }
    DisposableEffect(Unit) { onDispose { analyzer.close() } }
    var torch by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(vm.addedTick) {
        if (vm.addedTick > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(vm.added) {
        if (vm.added != null) {
            delay(4500)
            vm.clearAdded()
        }
    }
    LaunchedEffect(vm.message) {
        if (vm.message != null) {
            delay(5000)
            vm.message = null
        }
    }

    val loading = vm.state == ScanState.Loading
    val frameColor by animateColorAsState(
        when {
            vm.added != null -> PokeGreen
            loading -> PokeYellow
            else -> Color.White.copy(alpha = 0.85f)
        },
        label = "frame"
    )
    val scan by rememberInfiniteTransition(label = "scan").animateFloat(
        initialValue = 0.04f,
        targetValue = 0.96f,
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Reverse),
        label = "line"
    )

    Box(Modifier.fillMaxSize()) {
        CameraPreview(analyzer, torch, Modifier.fillMaxSize())

        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { sz ->
                    val w = sz.width.toFloat()
                    val h = sz.height.toFloat()
                    if (w > 0f && h > 0f) {
                        val g = guideRect(w, h)
                        vm.guide = Guide(g[0] / w, g[1] / h, g[2] / w, g[3] / h, w / h)
                    }
                }
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            val g = guideRect(size.width, size.height)
            val left = g[0]
            val top = g[1]
            val fw = g[2]
            val fh = g[3]
            val r = 18.dp.toPx()

            drawRect(Color(0xB3000000))
            drawRoundRect(
                Color.Black, Offset(left, top), Size(fw, fh), CornerRadius(r, r), blendMode = BlendMode.Clear
            )

            val len = 34.dp.toPx()
            val sw = 4.dp.toPx()
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(frameColor, Offset(x, y), Offset(x + dx * len, y), sw, StrokeCap.Round)
                drawLine(frameColor, Offset(x, y), Offset(x, y + dy * len), sw, StrokeCap.Round)
            }
            corner(left, top, 1f, 1f)
            corner(left + fw, top, -1f, 1f)
            corner(left, top + fh, 1f, -1f)
            corner(left + fw, top + fh, -1f, -1f)

            if (vm.autoScan || loading) {
                val y = top + fh * scan
                drawLine(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, frameColor, Color.Transparent), left, left + fw
                    ),
                    Offset(left + 8.dp.toPx(), y), Offset(left + fw - 8.dp.toPx(), y), 3.dp.toPx()
                )
            }
        }

        // topo: carta adicionada / mensagens
        Box(Modifier.align(Alignment.TopCenter).padding(12.dp)) {
            AnimatedVisibility(
                visible = vm.added != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                vm.added?.let { AddedCard(it, vm) }
            }
        }
        vm.message?.let { m ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).clickable { vm.message = null },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.tertiary
            ) {
                Text(m, Modifier.padding(14.dp), color = Color.White, fontSize = 14.sp)
            }
        }

        // base: status e botões
        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Consultando a carta…", color = Color.White)
                }
            } else if (vm.autoScan) {
                Text(
                    "Aponte para a carta e deixe o número visível (ex: 025/165)",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp
                )
            } else {
                Button(onClick = { vm.scanNow() }) { Text("📷  Escanear carta") }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon("🔦", active = torch) { torch = !torch }
                RoundIcon(if (vm.autoScan) "⚡" else "✋", active = vm.autoScan) { vm.changeAutoScan(!vm.autoScan) }
                RoundIcon("🔎") { showSearch = true }
                if (vm.visualReady) RoundIcon("🧠") { vm.identifyVisual() }
            }
        }
    }

    if (showSearch) {
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text("Buscar pelo nome") },
            text = {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Ex: Charizard") }
                )
            },
            confirmButton = {
                Button(enabled = query.isNotBlank(), onClick = { vm.searchByText(query.trim()); showSearch = false }) {
                    Text("Buscar")
                }
            },
            dismissButton = { TextButton(onClick = { showSearch = false }) { Text("Cancelar") } }
        )
    }

    (vm.state as? ScanState.Choose)?.let { choose ->
        ChooseDialog(choose.cards, vm, onPick = { vm.add(it) }, onDismiss = { vm.dismiss() })
    }
}

@Composable
fun AddedCard(a: Added, vm: MainVM) {
    val c = a.card
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = c.imageSmall,
                contentDescription = c.name,
                modifier = Modifier.width(56.dp).aspectRatio(CARD_RATIO).clip(RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
                Text(
                    "${c.setName} · ${numLabel(c.number, c.printedTotal)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(vm.money(c.price), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Tag(if (a.isNew) "NOVA!" else "x${c.quantity}", if (a.isNew) PokeGreen else PokeYellow)
        }
    }
}

@Composable
fun ChooseDialog(cards: List<ApiCard>, vm: MainVM, onPick: (ApiCard) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("Qual é a sua carta?") },
        text = {
            LazyColumn {
                items(cards) { c ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(c) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = c.imageSmall,
                            contentDescription = c.name,
                            modifier = Modifier.width(64.dp).aspectRatio(CARD_RATIO)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(c.name, fontWeight = FontWeight.Bold)
                            Text("${c.setName} · ${numLabel(c.number, c.printedTotal)}", fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                c.rarity?.let { Tag(it, rarityColor(it)) }
                                Spacer(Modifier.width(8.dp))
                                Text(vm.money(c.price), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    )
}

// ---------------------------------------------------------------- pokédex

/** Símbolo de raridade (estilo das cartas impressas). */
fun raritySymbol(r: String?): String {
    val s = (r ?: "").lowercase()
    return when {
        s.contains("special illustration") -> "✦✦"
        s.contains("illustration") -> "✦"
        s.contains("hyper") || s.contains("secret") -> "★★★"
        s.contains("ultra") || s.contains("double") -> "★★"
        s.contains("shiny") -> "✧"
        s.contains("ace spec") -> "ACE"
        s.contains("promo") -> "P"
        s.contains("holo") || s.contains("rare") -> "★"
        s.contains("uncommon") -> "◆"
        s.contains("common") -> "●"
        else -> "•"
    }
}

@Composable
fun RarityBadge(r: String?, modifier: Modifier = Modifier) {
    if (r == null) return
    Surface(shape = RoundedCornerShape(50), color = Color(0xCC000000), modifier = modifier) {
        Text(
            raritySymbol(r),
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = rarityColor(r), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun DexScreen(vm: MainVM) {
    val cards by vm.cards.collectAsState()
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableIntStateOf(0) }          // 0 recentes, 1 valor, 2 nome
    var onlyFav by remember { mutableStateOf(false) }
    var rarity by remember { mutableStateOf<String?>(null) }
    var layout by remember { mutableIntStateOf(1) }        // 0 = 3 colunas, 1 = 2 colunas, 2 = lista
    val rarities = cards.mapNotNull { it.rarity }.distinct().sorted()

    var shown = cards.filter {
        (it.name.contains(filter, true) || it.setName.contains(filter, true)) &&
            (!onlyFav || it.favorite) && (rarity == null || it.rarity == rarity)
    }
    shown = when (sort) {
        1 -> shown.sortedByDescending { vm.unitValueUsd(it) ?: 0.0 }
        2 -> shown.sortedBy { it.name.lowercase() }
        else -> shown
    }
    val total = cards.sumOf { vm.valueUsd(it) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Únicas", "${cards.size}", Modifier.weight(1f))
            StatCard("Total", "${cards.sumOf { it.quantity }}", Modifier.weight(1f))
            StatCard("Valor estimado", vm.money(total), Modifier.weight(1.6f))
        }
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            placeholder = { Text("Filtrar por nome ou set") }
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            item { Pill("Recentes", sort == 0, true) { sort = 0 } }
            item { Pill("Valor", sort == 1, true) { sort = 1 } }
            item { Pill("Nome", sort == 2, true) { sort = 2 } }
            item { Pill("⭐ Favoritas", onlyFav, true) { onlyFav = !onlyFav } }
            item { Pill(listOf("▦ 3 colunas", "▦ 2 colunas", "☰ Lista")[layout], false, true) { layout = (layout + 1) % 3 } }
            items(rarities) { r -> Pill("${raritySymbol(r)} $r", rarity == r, true) { rarity = if (rarity == r) null else r } }
        }
        if (cards.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🃏", fontSize = 56.sp)
                Text("Sua Pokédex está vazia.\nEscaneie a primeira carta!", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (shown.isEmpty()) {
            Text("Nenhuma carta com esses filtros.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(when (layout) { 0 -> 3; 1 -> 2; else -> 1 }),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(shown, key = { it.id }) { c ->
                if (layout == 2) DexListRow(vm, c) { selected = c.id }
                else DexGridCard(vm, c) { selected = c.id }
            }
        }
    }

    val current = selected?.let { id -> cards.find { it.id == id } }
    if (current != null) {
        DetailDialog(current, vm) { selected = null }
    }
}

@Composable
fun DexGridCard(vm: MainVM, c: OwnedCard, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        AsyncImage(
            model = c.imageSmall,
            contentDescription = c.name,
            modifier = Modifier.fillMaxWidth().aspectRatio(CARD_RATIO)
        )
        Row(Modifier.align(Alignment.TopStart).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (c.favorite) Text("⭐", fontSize = 13.sp)
            RarityBadge(c.rarity)
        }
        if (c.quantity > 1) {
            Tag("x${c.quantity}", PokeYellow, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        if (c.price != null) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(top = 14.dp, bottom = 3.dp)
            ) {
                Text(
                    vm.money(vm.unitValueUsd(c)),
                    Modifier.align(Alignment.Center),
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun DexListRow(vm: MainVM, c: OwnedCard, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(PanelBg).border(1.dp, GoldLine, shape)
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = c.imageSmall, contentDescription = c.name,
            modifier = Modifier.width(54.dp).aspectRatio(CARD_RATIO).clip(RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                "${c.setName} · ${numLabel(c.number, c.printedTotal)}",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                c.rarity?.let { Tag("${raritySymbol(it)} ${c.condition}", rarityColor(it)) }
                if (c.favorite) Text("⭐", fontSize = 13.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(vm.money(vm.unitValueUsd(c)), color = PokeYellow, fontWeight = FontWeight.ExtraBold)
            Text("x${c.quantity}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DetailDialog(c: OwnedCard, vm: MainVM, onClose: () -> Unit) {
    var buyText by remember(c.id) {
        mutableStateOf(c.buyPrice?.let { "%.2f".format(vm.toCurrency(it)) } ?: "")
    }
    val unit = vm.unitValueUsd(c)
    val paid = c.buyPrice
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } },
        dismissButton = { TextButton(onClick = { vm.remove(c); onClose() }) { Text("Remover") } },
        title = { Text(c.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AsyncImage(
                    model = c.imageLarge,
                    contentDescription = c.name,
                    modifier = Modifier.fillMaxWidth().aspectRatio(CARD_RATIO)
                )
                Text("${c.setName} · ${numLabel(c.number, c.printedTotal)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    c.rarity?.let { Tag("${raritySymbol(it)} $it", rarityColor(it)) }
                    Text(vm.money(unit), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { vm.toggleFavorite(c) }) {
                    Text(if (c.favorite) "⭐ Favorita" else "☆ Marcar como favorita")
                }
                Text("Condição: ${Cond.label(c.condition)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Cond.all.forEach { code -> Pill(code, c.condition == code, compact = true) { vm.setCondition(c, code) } }
                }
                OutlinedTextField(
                    value = buyText,
                    onValueChange = { buyText = it },
                    label = { Text("Preço pago por unidade (${vm.currency})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { vm.setBuyPrice(c, buyText) }) { Text("Salvar preço pago") }
                if (paid != null && unit != null) {
                    val diff = (unit - paid) * c.quantity
                    Text(
                        (if (diff >= 0) "Lucro: " else "Prejuízo: ") + vm.money(kotlin.math.abs(diff)),
                        color = if (diff >= 0) PokeGreen else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.setQty(c, c.quantity - 1) }) { Text("−") }
                    Text("  ${c.quantity}  ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { vm.setQty(c, c.quantity + 1) }) { Text("+") }
                }
            }
        }
    )
}

// ---------------------------------------------------------------- sets

@Composable
fun SetsScreen(vm: MainVM) {
    val cards by vm.cards.collectAsState()
    val groups = cards.groupBy { it.setId }.values
        .map { l -> SetProgress(l.first().setName, l.first().setSymbol, l.size, l.first().printedTotal) }
        .sortedByDescending { it.owned.toFloat() / it.total.coerceAtLeast(1) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (groups.isEmpty()) {
            Text("Escaneie cartas para ver o progresso de cada coleção.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(groups) { g ->
                val pct = (g.owned.toFloat() / g.total.coerceAtLeast(1)).coerceAtMost(1f)
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (g.symbol.isNotBlank()) {
                            AsyncImage(model = g.symbol, contentDescription = null, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(g.name, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                                Text("${g.owned}/${g.total}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { pct },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                                color = if (pct >= 1f) PokeGreen else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            Text(
                                "${(pct * 100).toInt()}% completo",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

data class SetProgress(val name: String, val symbol: String, val owned: Int, val total: Int)
