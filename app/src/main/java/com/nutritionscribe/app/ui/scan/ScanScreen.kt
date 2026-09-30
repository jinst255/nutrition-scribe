package com.nutritionscribe.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.NutritionFactsParser
import java.util.concurrent.Executors

/**
 * The packaged-food assist, offered on a confirm card — never a required step.
 *
 * Barcode first: with the label pack installed, a scan is the exact label. Without
 * the pack, or for a product the pack does not have, "Read the label" photographs
 * the Nutrition Facts panel and reads it on the phone. Either way nothing leaves
 * the device, and what was read is shown in editable boxes before it is used.
 */
@Composable
fun ScanScreen(onFound: (FoodCandidate) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val pack = ServiceLocator.labelPack
    var mode by remember { mutableStateOf(if (pack.isInstalled) Mode.BARCODE else Mode.LABEL) }
    var message by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<NutritionFactsParser.Panel?>(null) }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scanning needs the camera. Nothing is recorded or sent anywhere; the picture is read on your phone.")
            Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            OutlinedButton(onClick = onCancel) { Text("Not now") }
        }
        return
    }

    panel?.let { p ->
        PanelForm(p, onUse = onFound, onRetake = { panel = null })
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            when (mode) {
                Mode.BARCODE -> "Point the camera at the barcode."
                Mode.LABEL -> "Fit the Nutrition Facts panel in the frame, then tap Read."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!pack.isInstalled && mode == Mode.LABEL) {
            Text(
                "Tip: with the label pack installed (Settings), a barcode scan finds the exact product.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        var capture by remember { mutableStateOf<ImageCapture?>(null) }
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            CameraView(
                mode = mode,
                onBarcode = { code ->
                    val found = pack.source()?.byBarcode(code)
                    if (found != null) onFound(found)
                    else {
                        message = "That barcode ($code) isn't in the label pack. Read the label instead."
                        mode = Mode.LABEL
                    }
                },
                onCapture = { capture = it },
            )
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == Mode.LABEL) {
                Button(onClick = {
                    val c = capture ?: return@Button
                    message = "Reading…"
                    c.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val media = image.image
                            if (media == null) { image.close(); return }
                            val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
                            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(input)
                                .addOnSuccessListener { text ->
                                    val p = NutritionFactsParser.parse(text.text)
                                    if (p.perServing.isEmpty()) message = "Couldn't read a Nutrition Facts panel. Try closer, with the whole panel in frame."
                                    else { message = null; panel = p }
                                }
                                .addOnFailureListener { message = "Couldn't read that. Try again." }
                                .addOnCompleteListener { image.close() }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            message = "The camera didn't take the picture. Try again."
                        }
                    })
                }) { Text("Read") }
            } else {
                OutlinedButton(onClick = { mode = Mode.LABEL; message = null }) { Text("Read the label instead") }
            }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

private enum class Mode { BARCODE, LABEL }

@Composable
private fun CameraView(mode: Mode, onBarcode: (String) -> Unit, onCapture: (ImageCapture) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var handled by remember(mode) { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> PreviewView(ctx) },
        update = { view ->
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                onCapture(capture)
                val scanner = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder().setBarcodeFormats(
                        Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                    ).build(),
                )
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    .also { a ->
                        a.setAnalyzer(executor) { proxy ->
                            @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                            val media = proxy.image
                            if (mode != Mode.BARCODE || handled || media == null) { proxy.close(); return@setAnalyzer }
                            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                                .addOnSuccessListener { codes ->
                                    val raw = codes.firstOrNull()?.rawValue
                                    if (raw != null && !handled) {
                                        handled = true
                                        ContextCompat.getMainExecutor(context).execute { onBarcode(raw) }
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        }
                    }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                }
            }, ContextCompat.getMainExecutor(context))
        },
    )
}

/** What was read off the panel, as editable boxes. Nothing is used until they tap Use. */
@Composable
private fun PanelForm(panel: NutritionFactsParser.Panel, onUse: (FoodCandidate) -> Unit, onRetake: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var serving by remember { mutableStateOf(panel.servingGrams?.let { it.toInt().toString() }.orEmpty()) }
    val values = remember {
        mutableStateMapOf<Metric, String>().apply {
            Metric.entries.forEach { m -> panel.perServing[m.key]?.let { put(m, fmt(it)) } }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Check what was read, per serving", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("What is it?") },
            placeholder = { Text("e.g. Kodiak power cakes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = serving, onValueChange = { serving = it }, label = { Text("Serving (grams)") },
            supportingText = { panel.servingText?.let { Text("Label says: $it") } },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Metric.entries.forEach { m ->
            OutlinedTextField(
                value = values[m].orEmpty(),
                onValueChange = { values[m] = it },
                label = { Text("${m.label} (${m.unit})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val per = values.mapNotNull { (m, v) -> v.toDoubleOrNull()?.let { m.key to it } }.toMap()
                val p = NutritionFactsParser.Panel(panel.servingText, serving.toDoubleOrNull(), per)
                onUse(p.asCandidate(name.ifBlank { "Scanned label" }))
            }, enabled = values.isNotEmpty()) { Text("Use these") }
            OutlinedButton(onClick = onRetake) { Text("Retake") }
        }
    }
}

private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
