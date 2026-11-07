package com.pokescan

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.Executors

data class Parsed(val number: String?, val total: Int?, val names: List<String>, val raw: String)

/** Extrai número (ex: 025/165) e possíveis nomes do texto lido pelo OCR. */
object OcrParser {
    private val ignore = listOf(
        "BASIC", "STAGE", "BÁSICO", "BASICO", "FASE", "EVOLVES", "EVOLUI", "TRAINER", "TREINADOR",
        "ITEM", "SUPPORTER", "STADIUM", "ENERGY", "ENERGIA", "POKÉMON", "POKEMON", "ILLUS",
        "WEAKNESS", "FRAQUEZA", "RESISTANCE", "RESISTÊNCIA", "RETREAT", "RECUO", "ABILITY", "HABILIDADE"
    )

    fun parse(text: Text): Parsed {
        val raw = text.text
        val m = Regex("(\\d{1,3})\\s*/\\s*(\\d{2,3})").find(raw)
        val number = m?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" }
        val total = m?.groupValues?.get(2)?.toIntOrNull()
        val lines = text.textBlocks.flatMap { it.lines }
            .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
        val names = lines.map { clean(it.text) }
            .filter { s -> s.length >= 3 && ignore.none { w -> s.uppercase().startsWith(w) } }
            .take(3)
        return Parsed(number, total, names, raw)
    }

    private fun clean(s: String): String =
        s.replace(Regex("(?i)\\b(HP|PS)\\b.*"), "")
            .replace(Regex("[^\\p{L}'’ \\-]"), "")
            .trim()
}

/**
 * Lê os quadros da câmera: OCR do número (2x por segundo) e, quando pedido, o reconhecimento
 * por imagem (1x por segundo): recorta a moldura, calcula o vetor e busca no índice.
 */
class FrameAnalyzer(
    private val ctx: Context,
    private val rec: TextRecognizer,
    private val enabled: () -> Boolean,
    private val onResult: (Parsed) -> Unit,
    private val visualWanted: () -> Boolean,
    private val guide: () -> Guide?,
    private val onVisual: (List<VisualHit>) -> Unit,
    private val onVisualError: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private var last = 0L
    private var lastVis = 0L
    private var embedder: VisualEmbedder? = null
    private val main = Handler(Looper.getMainLooper())

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = image.image
        val wantOcr = media != null && enabled() && now - last >= 500
        val wantVis = visualWanted() && now - lastVis >= 900
        if (!wantOcr && !wantVis) {
            image.close()
            return
        }
        if (wantVis) {
            lastVis = now
            runVisual(image)
        }
        if (wantOcr && media != null) {
            last = now
            val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
            rec.process(input)
                .addOnSuccessListener { onResult(OcrParser.parse(it)) }
                .addOnCompleteListener { image.close() }
        } else {
            image.close()
        }
    }

    private fun runVisual(image: ImageProxy) {
        try {
            val d = VisualIndex.data ?: return
            val g = guide() ?: return
            val emb = embedder ?: VisualEmbedder(ctx).also { embedder = it }
            val bmp = VisualEmbedder.cropGuide(image, g)
            val e = emb.embedViews(bmp, d.mode) ?: return
            if (e.size != d.din) throw IllegalStateException("Índice incompatível com este app. Atualize o índice.")
            val hits = VisualIndex.search(d, VisualIndex.project(d, e), 8)
            main.post { onVisual(hits) }
        } catch (t: Throwable) {
            embedder = null
            val msg = if (t is java.io.FileNotFoundException) "Modelo de imagem ausente neste app." else (t.message ?: t.javaClass.simpleName)
            main.post { onVisualError(msg) }
        }
    }

    fun close() {
        embedder?.close()
        embedder = null
    }
}

@Composable
fun CameraPreview(analyzer: ImageAnalysis.Analyzer, torch: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var camera by remember { mutableStateOf<Camera?>(null) }

    AndroidView(modifier = modifier, factory = { c ->
        val pv = PreviewView(c)
        pv.scaleType = PreviewView.ScaleType.FILL_CENTER
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(c))
        pv
    })

    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            executor.shutdown()
        }
    }
}
