package com.pokescan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay

private const val CARD_RATIO = 0.716f

@Composable
fun App(vm: MainVM) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("📷" to "Escanear", "📖" to "Pokédex", "📊" to "Sets")
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (emoji, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(emoji) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ScanScreen(vm)
                1 -> DexScreen(vm)
                else -> SetsScreen(vm)
            }
        }
    }
}

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

    LaunchedEffect(vm.message) {
        if (vm.message != null) {
            delay(5000)
            vm.message = null
        }
    }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Precisamos da câmera para escanear as cartas.")
            Spacer(Modifier.width(8.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Permitir câmera") }
        }
        return
    }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    var query by remember { mutableStateOf("") }
    val loading = vm.state == ScanState.Loading

    Box(Modifier.fillMaxSize()) {
        CameraPreview(imageCapture, Modifier.fillMaxSize())

        Box(
            Modifier.align(Alignment.Center)
                .fillMaxWidth(0.8f)
                .aspectRatio(CARD_RATIO)
                .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
        )

        vm.message?.let { m ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).clickable { vm.message = null },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface
            ) {
                Text(m, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.93f))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Ou digite o nome da carta") }
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !loading && query.isNotBlank(),
                    onClick = { vm.searchByText(query.trim()) }
                ) { Text("Buscar") }
            }
            Spacer(Modifier.padding(4.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
                onClick = { captureAndRecognize(ctx, imageCapture, recognizer, vm) }
            ) { Text(if (loading) "Procurando…" else "📷 Escanear carta") }
        }
    }

    (vm.state as? ScanState.Choose)?.let { choose ->
        ChooseDialog(choose.cards, onPick = { vm.add(it) }, onDismiss = { vm.dismiss() })
    }
}

@Composable
fun ChooseDialog(cards: List<ApiCard>, onPick: (ApiCard) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("Qual é a sua carta?") },
        text = {
            LazyColumn {
                items(cards) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 6.dp),
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
                            Text("${c.setName} · ${c.number}/${c.printedTotal}", fontSize = 13.sp)
                            c.rarity?.let { Text(it, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun DexScreen(vm: MainVM) {
    val cards by vm.cards.collectAsState()
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    val shown = cards.filter { it.name.contains(filter, true) || it.setName.contains(filter, true) }
    val total = cards.sumOf { (it.price ?: 0.0) * it.quantity }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text(
            "${cards.size} únicas · ${cards.sumOf { it.quantity }} no total · ≈ US$ ${"%.2f".format(total)}",
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            singleLine = true,
            placeholder = { Text("Filtrar por nome ou set") }
        )
        if (cards.isEmpty()) {
            Text("Sua Pokédex está vazia. Escaneie a primeira carta!")
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(shown, key = { it.id }) { c ->
                Box(Modifier.clickable { selected = c.id }) {
                    AsyncImage(
                        model = c.imageSmall,
                        contentDescription = c.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(CARD_RATIO)
                    )
                    if (c.quantity > 1) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                "x${c.quantity}",
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    val current = selected?.let { id -> cards.find { it.id == id } }
    if (current != null) {
        DetailDialog(current, vm) { selected = null }
    }
}

@Composable
fun DetailDialog(c: OwnedCard, vm: MainVM, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } },
        dismissButton = { TextButton(onClick = { vm.remove(c); onClose() }) { Text("Remover") } },
        title = { Text(c.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = c.imageLarge,
                    contentDescription = c.name,
                    modifier = Modifier.fillMaxWidth().aspectRatio(CARD_RATIO)
                )
                Text("${c.setName} · ${c.number}/${c.printedTotal}")
                Text("Raridade: ${c.rarity ?: "—"}")
                Text("Preço de mercado: " + (c.price?.let { "US$ %.2f".format(it) } ?: "—"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.setQty(c, c.quantity - 1) }) { Text("−") }
                    Text("  ${c.quantity}  ", fontSize = 18.sp)
                    OutlinedButton(onClick = { vm.setQty(c, c.quantity + 1) }) { Text("+") }
                }
            }
        }
    )
}

@Composable
fun SetsScreen(vm: MainVM) {
    val cards by vm.cards.collectAsState()
    val groups = cards.groupBy { it.setId }.values
        .map { l -> Triple(l.first().setName, l.size, l.first().printedTotal) }
        .sortedByDescending { it.second.toFloat() / it.third.coerceAtLeast(1) }

    if (groups.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) { Text("Escaneie cartas para ver o progresso por set.") }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(groups) { (name, owned, total) ->
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text("$owned/$total")
                }
                LinearProgressIndicator(
                    progress = { (owned.toFloat() / total.coerceAtLeast(1)).coerceAtMost(1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
