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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.nutritionscribe.shared.model.Liquids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * The packaged-food assist, offered on a confirm card and on Home.
 *
 * One camera, doing both at once: it looks for a barcode the whole time, and
 * "Read label" photographs the Nutrition Facts panel whenever it is in view. It
 * used to be two modes that switched by themselves, which in the log meant
 * people backing out and coming in again. A barcode is looked up in the label
 * pack, then Open Food Facts; if neither has it, the camera simply stays up for
 * the label. Tap the picture to focus; the light helps with shiny wrappers.
 */
@Composable
fun ScanScreen(onFound: (FoodCandidate) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val pack = ServiceLocator.labelPack
    var message by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<NutritionFactsParser.Panel?>(null) }
    val events = ServiceLocator.debugEvents
    val scope = rememberCoroutineScope()
    val openedAt = remember { System.currentTimeMillis() }
    // Barcodes already tried, so a code in view is looked up once, not per frame.
    val tried = remember { mutableSetOf<String>() }
    var done by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        events.startFlow("scan", onlyIfIdle = true)
        events.event("scan_open", "label_pack" to pack.status.value.toString(), "camera_granted" to granted)
    }

    /** Hands a product on, with the micronutrients its label left out borrowed from the same food. */
    fun deliver(c: FoodCandidate, how: String) {
        if (done) return
        done = true
        scope.launch {
            val full = withContext(Dispatchers.IO) { ServiceLocator.withBorrowedMicros(c) }
            events.event("scan_found", "how" to how, "ms" to System.currentTimeMillis() - openedAt, "candidate" to describe(full),
                "borrowed_micros" to (full.per100g.orEmpty().keys - c.per100g.orEmpty().keys))
            onFound(full)
        }
    }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scanning needs the camera. Nothing is recorded; the picture is read on your phone.")
            Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            OutlinedButton(onClick = onCancel) { Text("Not now") }
        }
        return
    }

    panel?.let { p ->
        PanelForm(p, onUse = { deliver(it, "label") }, onRetake = { events.event("scan_retake"); panel = null; reading = false })
        return
    }

    fun onBarcode(code: String) {
        if (done || reading || !tried.add(code)) return
        val t0 = System.currentTimeMillis()
        val inPack = pack.source()?.byBarcode(code)
        if (inPack != null) {
            events.event("scan_barcode", "code" to code, "source" to "pack", "ms" to System.currentTimeMillis() - t0, "candidate" to describe(inPack))
            deliver(inPack, "barcode")
            return
        }
        status = "Checking Open Food Facts for $code…"
        scope.launch {
            val online = withContext(Dispatchers.IO) { ServiceLocator.openFoodFacts.byBarcode(code) }
            events.event("scan_barcode", "code" to code, "source" to if (online != null) "open_food_facts" else "none",
                "label_pack" to pack.isInstalled, "ms" to System.currentTimeMillis() - t0, "candidate" to online?.let { describe(it) })
            if (online != null) deliver(online, "barcode_online")
            else {
                status = null
                message = "Barcode $code isn't in the label pack or Open Food Facts. Tap Read label and fit the Nutrition Facts in the frame."
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Point at the barcode, or at the Nutrition Facts and tap Read label.",
            style = MaterialTheme.typography.bodyMedium,
        )
        var capture by remember { mutableStateOf<ImageCapture?>(null) }
        var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
        var torch by remember { mutableStateOf(false) }
        var focusAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
        LaunchedEffect(focusAt) { if (focusAt != null) { kotlinx.coroutines.delay(900); focusAt = null } }
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp))) {
            CameraView(
                onBarcode = ::onBarcode,
                onReady = { c, cam -> capture = c; camera = cam },
                onFocus = { at -> focusAt = at; events.event("scan_focus") },
            )
            focusAt?.let { at -> FocusRing(at) }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                        events.event("scan_torch", "on" to torch)
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                ) {
                    Icon(
                        if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = if (torch) "Turn the light off" else "Turn the light on",
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
            ) {
                Text(
                    status ?: if (reading) "Reading the label…" else "Looking for a barcode · tap to focus",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !reading && capture != null, onClick = {
                val c = capture ?: return@Button
                reading = true
                message = null
                val t0 = System.currentTimeMillis()
                events.event("scan_capture", "torch" to torch)
                c.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val media = image.image
                        if (media == null) {
                            events.event("scan_failed", "stage" to "capture", "reason" to "no image")
                            reading = false
                            image.close(); return
                        }
                        val rotation = image.imageInfo.rotationDegrees
                        // The photo the text was read from, so a misread can be
                        // checked against what the camera saw. Debug mode only.
                        val bitmap = if (events.enabled) runCatching { image.toBitmap() }.getOrNull() else null
                        val photo = bitmap?.let { b ->
                            Executors.newSingleThreadExecutor().submit<String?> { events.saveScan(rotated(b, rotation)) }
                        }
                        fun photoName() = runCatching { photo?.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
                        val size = "${image.width}x${image.height}"
                        // A reader that never answers would leave "Reading…" up for good.
                        var answered = false
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (!answered) {
                                events.event("scan_failed", "stage" to "ocr_timeout", "after_ms" to 20_000, "photo" to photoName())
                                message = "Still reading. If this keeps happening, the phone may be downloading its text reader; try again in a minute."
                            }
                        }, 20_000)
                        val input = InputImage.fromMediaImage(media, rotation)
                        val tOcr = System.currentTimeMillis()
                        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(input)
                            .addOnSuccessListener { text ->
                                answered = true
                                val p = NutritionFactsParser.parse(text.text)
                                events.event(
                                    "scan_ocr",
                                    "photo" to photoName(),
                                    "size" to size, "rotation" to rotation,
                                    "capture_ms" to tOcr - t0, "ocr_ms" to System.currentTimeMillis() - tOcr,
                                    "text" to text.text,
                                    // Where each line sat in the picture: a curved jar or a
                                    // skewed shot shows up as lines out of order or split.
                                    "lines" to text.textBlocks.flatMap { b -> b.lines.map { l ->
                                        mapOf("text" to l.text, "box" to l.boundingBox?.flattenToString(),
                                            "angle" to l.angle, "confidence" to l.confidence)
                                    } },
                                )
                                events.event(
                                    "scan_parsed",
                                    "serving_text" to p.servingText, "serving_grams" to p.servingGrams,
                                    "per_serving" to p.perServing, "complete" to p.looksComplete,
                                    "reads" to p.reads.map { r -> mapOf("metric" to r.metric, "line" to r.line, "raw" to r.raw,
                                        "value" to r.value, "fix" to r.fix, "as_read" to r.asRead) },
                                    "missing" to (Metric.MACROS + LABEL_MICROS).map { it.key }.filter { it !in p.perServing },
                                    "suspect" to p.issues.map { it.code },
                                )
                                if (p.issues.isNotEmpty()) events.event("suspect", "where" to "scan", "issues" to p.issues.map { it.code },
                                    "per_serving" to p.perServing, "serving_grams" to p.servingGrams)
                                if (p.perServing.isEmpty()) {
                                    reading = false
                                    message = "Couldn't read a Nutrition Facts panel. Try closer, with the whole panel in frame. Tap the picture to focus."
                                } else { message = null; panel = p }
                            }
                            .addOnFailureListener { e ->
                                answered = true
                                reading = false
                                events.event("scan_failed", "stage" to "ocr", "error" to e, "photo" to photoName(),
                                    "ms" to System.currentTimeMillis() - tOcr)
                                message = if (e.message.orEmpty().contains("download", ignoreCase = true))
                                    "The phone is still downloading its text reader. Try again in a minute."
                                else "Couldn't read that. Try again."
                            }
                            .addOnCompleteListener { image.close() }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        reading = false
                        events.event("scan_failed", "stage" to "capture", "error" to exception, "code" to exception.imageCaptureError)
                        message = "The camera didn't take the picture. Try again."
                    }
                })
            }) { Text("Read label") }
            OutlinedButton(onClick = { events.event("scan_cancel", "ms" to System.currentTimeMillis() - openedAt); onCancel() }) { Text("Cancel") }
        }
        if (!pack.isInstalled) {
            Text(
                "Tip: with the label pack installed (Settings), most barcodes are found without going online.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/** Where the picture was tapped, briefly, so it is clear the tap did something. */
@Composable
private fun FocusRing(at: androidx.compose.ui.geometry.Offset) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val r = 28.dp
    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset((at.x - with(d) { r.toPx() }).toInt(), (at.y - with(d) { r.toPx() }).toInt()) }
            .size(r * 2)
            .border(2.dp, Color.White, CircleShape),
    )
}

/**
 * The preview, bound once. It used to rebind the camera on every recomposition,
 * which is to say every time the status line changed.
 */
@Composable
private fun CameraView(
    onBarcode: (String) -> Unit,
    onReady: (ImageCapture, androidx.camera.core.Camera) -> Unit,
    onFocus: (androidx.compose.ui.geometry.Offset) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestBarcode by rememberUpdatedState(onBarcode)
    val latestReady by rememberUpdatedState(onReady)
    val latestFocus by rememberUpdatedState(onFocus)
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
            ).build(),
        )
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        codes.firstOrNull()?.rawValue?.let { raw -> ContextCompat.getMainExecutor(context).execute { latestBarcode(raw) } }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching {
                p.unbindAll()
                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                latestReady(capture, cam)
                @Suppress("ClickableViewAccessibility")
                view.setOnTouchListener { v, e ->
                    if (e.action == android.view.MotionEvent.ACTION_UP) {
                        val point = view.meteringPointFactory.createPoint(e.x, e.y)
                        cam.cameraControl.startFocusAndMetering(
                            androidx.camera.core.FocusMeteringAction.Builder(
                                point,
                                androidx.camera.core.FocusMeteringAction.FLAG_AF or androidx.camera.core.FocusMeteringAction.FLAG_AE,
                            ).setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS).build(),
                        )
                        latestFocus(androidx.compose.ui.geometry.Offset(e.x, e.y))
                        v.performClick()
                    }
                    true
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { provider?.unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(modifier = Modifier.fillMaxSize(), factory = { view })
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
    val now = values.mapNotNull { (m, v) -> v.toDoubleOrNull()?.let { m.key to it } }.toMap()
    // Re-checked as they type, so fixing the box clears its warning.
    val issues = remember(now, serving) { com.nutritionscribe.shared.model.NutrientSanity.check(now, serving.toDoubleOrNull()) }
    val fixes = remember { panel.reads.filter { it.fix != null }.associateBy { it.metric } }
    // Numbers that can't all be true used to go straight into My Foods and repeat on
    // every "log again". Now the first tap stops and points at the box; only a
    // second tap uses them as they are (labels do sometimes break the arithmetic).
    var warned by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(issues.isEmpty()) { if (issues.isEmpty()) warned = false }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Check what was read, per serving", style = MaterialTheme.typography.titleMedium)
        if (issues.isNotEmpty()) {
            Text(
                if (warned) "These still don't add up. Fix the marked boxes, or tap Use anyway if the label really says so."
                else "Some of these don't add up. Check the marked boxes against the label.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Named, it can be logged again by name and its other vitamins found; three
        // "Scanned label" chips in Log again were no use to anyone.
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("What is it?") },
            placeholder = { Text("e.g. Food Club creamy peanut butter") }, singleLine = true,
            supportingText = { Text("Brand and product, so you can find it again") },
            modifier = Modifier.fillMaxWidth())
        val liquid = Liquids.isLiquid(name, panel.servingText)
        OutlinedTextField(value = serving, onValueChange = { serving = it }, label = { Text(if (liquid) "Serving (mL)" else "Serving (grams)") },
            supportingText = { panel.servingText?.let { Text("Label says: $it") } },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        // The macros, and the micronutrients a US label has to print.
        (Metric.MACROS + LABEL_MICROS).forEach { m ->
            // Only the box most likely wrong gets the warning, not every box involved.
            val issue = issues.firstOrNull { it.metrics.firstOrNull() == m.key }
            val fix = fixes[m.key]?.takeIf { f -> values[m]?.toDoubleOrNull() == f.value }
            OutlinedTextField(
                value = values[m].orEmpty(),
                onValueChange = { values[m] = it },
                label = { Text("${m.label} (${m.unit})") },
                isError = issue != null,
                supportingText = when {
                    issue != null -> ({ Text(issue.message) })
                    fix?.fix == "g_read_as_9" -> ({ Text("Read as ${fmt(fix.asRead ?: 0.0)}; the last 9 looked like a \"g\"") })
                    fix?.fix == "from_percent_dv" -> ({ Text("Worked out from the % Daily Value") })
                    fix?.fix == "calories_apart" -> ({ Text("The large number printed apart; check it") })
                    else -> null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (issues.isNotEmpty() && !warned) {
                    warned = true
                    ServiceLocator.debugEvents.event("scan_use_blocked", "suspect" to issues.map { it.code })
                    return@Button
                }
                val per = values.mapNotNull { (m, v) -> v.toDoubleOrNull()?.let { m.key to it } }.toMap()
                // Stored in grams: a drink's millilitres are weighed by its density.
                val grams = serving.toDoubleOrNull()?.let { if (liquid) Liquids.grams(it, name) else it }
                val p = NutritionFactsParser.Panel(panel.servingText, grams, per)
                // What they changed is the most useful line in the log: every edit
                // here is something the reader got wrong.
                val keys = (panel.perServing.keys + per.keys).distinct()
                ServiceLocator.debugEvents.event(
                    "scan_use",
                    "name" to name, "serving_read" to panel.servingGrams, "serving_used" to serving.toDoubleOrNull(),
                    "read" to panel.perServing, "used" to per,
                    "changed" to keys.filter { panel.perServing[it] != per[it] }.associateWith { k -> mapOf("read" to panel.perServing[k], "used" to per[k]) },
                    "suspect_read" to panel.issues.map { it.code }, "suspect_used" to issues.map { it.code },
                )
                onUse(p.asCandidate(name.trim()))
            }, enabled = values.isNotEmpty() && name.isNotBlank()) { Text(if (warned) "Use anyway" else "Use these") }
            OutlinedButton(onClick = onRetake) { Text("Retake") }
        }
    }
}

private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)

private val LABEL_MICROS = listOf(Metric.CHOLESTEROL, Metric.CALCIUM, Metric.IRON, Metric.POTASSIUM, Metric.VITAMIN_D)

private fun rotated(b: android.graphics.Bitmap, degrees: Int): android.graphics.Bitmap =
    if (degrees == 0) b else android.graphics.Bitmap.createBitmap(
        b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }, true,
    )

/** A found product, as much of it as helps tell a wrong match from a right one. */
internal fun describe(c: FoodCandidate): Map<String, Any?> = mapOf(
    "id" to c.id, "name" to c.name, "brand" to c.brand, "origin" to c.origin,
    "serving" to c.servingLabel, "serving_grams" to (c.labelServingGrams ?: c.defaultGrams),
    "per_serving" to c.perServing, "per_100g" to c.per100g,
    "suspect" to com.nutritionscribe.shared.model.NutrientSanity.codes(c.perServing ?: c.per100g.orEmpty(), if (c.perServing != null) c.labelServingGrams else 100.0),
)
