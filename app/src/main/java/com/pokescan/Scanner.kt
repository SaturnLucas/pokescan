package com.pokescan

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer

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

fun captureAndRecognize(ctx: Context, ic: ImageCapture, rec: TextRecognizer, vm: MainVM) {
    vm.begin()
    ic.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val media = image.image
            if (media == null) {
                image.close()
                vm.fail("Falha ao ler a imagem da câmera.")
                return
            }
            val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
            rec.process(input)
                .addOnSuccessListener { vm.onOcr(OcrParser.parse(it)) }
                .addOnFailureListener { vm.fail("OCR falhou: ${it.message}") }
                .addOnCompleteListener { image.close() }
        }

        override fun onError(exception: ImageCaptureException) {
            vm.fail("Erro na câmera: ${exception.message}")
        }
    })
}

@Composable
fun CameraPreview(imageCapture: ImageCapture, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    AndroidView(modifier = modifier, factory = { c ->
        val pv = PreviewView(c)
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(c))
        pv
    })
    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } }
    }
}
