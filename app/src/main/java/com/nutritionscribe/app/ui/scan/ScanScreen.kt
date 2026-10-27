package com.nutritionscribe.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nutritionscribe.app.di.ServiceLocator
import com.nutritionscribe.app.ui.confirm.ItemDraft
import com.nutritionscribe.app.ui.home.SectionLabel
import com.nutritionscribe.app.ui.theme.NumberStyle
import com.nutritionscribe.shared.model.Liquids
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.NutrientSanity
import com.nutritionscribe.shared.resolve.FoodCandidate
import com.nutritionscribe.shared.resolve.FrontMatcher
import com.nutritionscribe.shared.resolve.Kinds
import com.nutritionscribe.shared.resolve.LabelPackSource
import com.nutritionscribe.shared.resolve.NutritionFactsParser
import com.nutritionscribe.shared.resolve.Origin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** After this long without knowing the front, the screen asks for the Nutrition Facts. */
private const val FRONT_MS = 5000L

/** How often a camera frame is read for words: the front's big print, or a panel coming into view. */
private const val READ_EVERY_MS = 700L

/** With the label in view and nothing new read for this long, it reads what it has (notes S7, SCAN-4). */
private const val STILL_MS = 8000L

/** What the camera looks for (note S9): everything at once, or the one thing chosen. */
private enum class ScanMode(val label: String) { AUTO("Auto"), BARCODE("Barcode"), FRONT("Front"), LABEL("Label") }

/** The lines every US label must print, the progress count: the serving, then 15 nutrients. */
private val REQUIRED: List<String> = listOf("serving") + listOf(
    Metric.CALORIES, Metric.FAT, Metric.SATURATED_FAT, Metric.TRANS_FAT, Metric.CHOLESTEROL, Metric.SODIUM, Metric.CARBS,
    Metric.FIBER, Metric.SUGAR, Metric.ADDED_SUGAR, Metric.PROTEIN, Metric.VITAMIN_D, Metric.CALCIUM, Metric.IRON, Metric.POTASSIUM,
).map { it.key }

/**
 * The packaged-food assist, offered on a confirm card and on Home.
 *
 * One camera that says what it is doing. Three marks along its top show what it
 * is looking for: a barcode (all the time), the front of the box (read as it
 * comes into view, "Is this Life Original cereal?"), and the Nutrition Facts.
 * If the front isn't known within about five seconds it asks for the label, which
 * always works, so nobody spends minutes turning a box under the camera.
 *
 * A product's other kinds are offered beside it (Cinnamon, Vanilla), and a kind
 * nobody has saved ("Pumpkin Spice") gets a short label scan with its name filled
 * in, or is logged with the closest kind's numbers for now.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScanScreen(
    onFound: (FoodCandidate) -> Unit,
    onCancel: () -> Unit,
    nameHint: String? = null,
    /** Scanning a new kind of this product: its label is read with the name already in. */
    kindOf: FoodCandidate? = null,
    kindName: String? = null,
    onMyLabels: () -> Unit = {},
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val pack = ServiceLocator.labelPack
    val settings = ServiceLocator.settingsRepository
    val events = ServiceLocator.debugEvents
    val scope = rememberCoroutineScope()
    val openedAt = remember { System.currentTimeMillis() }
    var message by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<NutritionFactsParser.Panel?>(null) }
    var panelPhoto by remember { mutableStateOf<Bitmap?>(null) }
    // Barcodes already tried, so a code in view is looked up once, not per frame.
    val tried = remember { mutableSetOf<String>() }
    var done by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }

    // The front of the box, read from the live picture.
    // From the Logged card with no kind named yet: ask its name first.
    var sheet by remember { mutableStateOf<FrontSheet?>(if (kindOf != null && kindName == null) FrontSheet.NewKind(kindOf, "", null) else null) }
    var frontDone by remember { mutableStateOf(kindOf != null) }
    var frontSeen by remember { mutableStateOf(false) }
    var gaveUp by remember { mutableStateOf(false) }
    var guesses by remember { mutableStateOf<List<FoodCandidate>>(emptyList()) }
    var matching by remember { mutableStateOf(false) }
    // A panel in view: what of it can already be read, as a checklist.
    var panelSeen by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf<Set<String>>(emptySet()) }
    // A new kind being scanned: the product it is a kind of, and its name.
    var newKindOf by remember { mutableStateOf(kindOf?.takeIf { kindName != null }) }
    var newKindName by remember { mutableStateOf(kindName) }
    // The product the front showed, when they still went to the label: its numbers
    // are what this label should roughly say.
    var expected by remember { mutableStateOf<FoodCandidate?>(null) }
    val guide by settings.scanGuide.collectAsState(initial = true)
    var mode by remember { mutableStateOf(if (kindOf != null) ScanMode.LABEL else ScanMode.AUTO) }
    // Every number read off the panel while it was in view, by how often it was read:
    // a photo that misses a line takes it from here when two frames agreed on it.
    val votes = remember { mutableMapOf<String, MutableMap<Double, Int>>() }
    var liveServing by remember { mutableStateOf<Double?>(null) }
    var lastNewAt by remember { mutableStateOf(0L) }
    var autoRead by remember { mutableStateOf(false) }
    // Where the panel sits in the picture, so the box follows it (note S20).
    var panelBox by remember { mutableStateOf<android.graphics.Rect?>(null) }
    var frameSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var panelBoxAt by remember { mutableStateOf(0L) }
    LaunchedEffect(panelBoxAt) { delay(1500); if (System.currentTimeMillis() - panelBoxAt >= 1400) panelBox = null }
    // One photo with several labels (a variety pack, note S10): which one was eaten.
    var choices by remember { mutableStateOf<List<Pair<String?, NutritionFactsParser.Panel>>>(emptyList()) }
    var pickedTitle by remember { mutableStateOf<String?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var torch by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        events.startFlow("scan", onlyIfIdle = true)
        events.event("scan_open", "label_pack" to pack.status.value.toString(), "camera_granted" to granted,
            "kind_of" to kindOf?.id, "kind" to kindName)
    }
    LaunchedEffect(granted, mode) {
        if (!granted || (mode != ScanMode.AUTO && mode != ScanMode.FRONT)) return@LaunchedEffect
        delay(if (mode == ScanMode.FRONT) FRONT_MS * 2 else FRONT_MS)
        if (sheet == null && panel == null && !done && !panelSeen && !frontDone) {
            gaveUp = true
            events.event("scan_front_gave_up", "ms" to System.currentTimeMillis() - openedAt, "front_seen" to frontSeen,
                "guesses" to guesses.map { ItemDraft.withBrand(it, " ") })
        }
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
        val hint = newKindOf?.let { base -> newKindName?.let { k -> ItemDraft.withBrand(base.copy(name = Kinds.nameFor(base, k)), " ") } }
            ?: listOfNotNull(nameHint, pickedTitle).joinToString(" ").ifBlank { null }
        PanelForm(
            panel = p, photo = panelPhoto, nameHint = hint, fromFront = newKindOf != null,
            brand = newKindOf?.brand, expected = expected,
            onUse = { c ->
                panelPhoto?.let { b -> scope.launch { withContext(Dispatchers.IO) { ScanPhotos.save(b) }?.let { ScanPhotos.remember(c.id, it) } } }
                deliver(c, if (newKindOf != null) "label_new_kind" else "label")
            },
            onRetake = {
                events.event("scan_retake"); panel = null; panelPhoto = null; reading = false
                autoRead = false; votes.clear(); seen = emptySet(); liveServing = null; pickedTitle = null; lastNewAt = System.currentTimeMillis()
            },
        )
        return
    }

    fun onBarcode(code: String) {
        // Reading their own label means their label, not the product list's.
        if (done || reading || mode == ScanMode.LABEL || newKindOf != null || !tried.add(code)) return
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
                gaveUp = true
                message = "Barcode $code isn't in the label pack or Open Food Facts. Turn it to the Nutrition Facts and tap Read label."
            }
        }
    }

    /** A frame's words: a panel coming into view, or the front of the box. */
    fun onText(lines: List<NutritionFactsParser.OcrLine>, frame: Bitmap?) {
        if (done || reading || sheet != null || choices.isNotEmpty() || mode == ScanMode.BARCODE) return
        val text = lines.joinToString("\n") { it.text }
        if (mode != ScanMode.FRONT && looksLikePanel(text)) {
            if (!panelSeen) events.event("scan_panel_in_view", "ms" to System.currentTimeMillis() - openedAt)
            panelSeen = true
            panelBoxOf(lines)?.let { panelBox = it; panelBoxAt = System.currentTimeMillis() }
            frame?.let { frameSize = it.width to it.height }
            val p = runCatching { NutritionFactsParser.parse(lines) }.getOrNull()
            if (p != null) {
                p.perServing.forEach { (k, v) -> votes.getOrPut(k) { mutableMapOf() }.merge(v, 1, Int::plus) }
                p.servingGrams?.let { liveServing = it }
                val now = p.perServing.keys + listOfNotNull(if (p.servingText != null || p.servingGrams != null) "serving" else null)
                if (!seen.containsAll(now)) { seen = seen + now; lastNewAt = System.currentTimeMillis() }
            }
            return
        }
        if (frontDone || matching || mode == ScanMode.LABEL || newKindOf != null) return
        val big = lines.filter { it.text.count(Char::isLetter) >= 3 }
        if (big.isEmpty()) return
        frontSeen = true
        matching = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { frontAnswer(big.map { it.text to it.height }) }
            matching = false
            if (found == null || done || sheet != null || frontDone) return@launch
            // Only a strong match is offered at all (notes S22, S34): a wrong question costs more than none.
            if (found.sure || found.yours) guesses = listOf(found.candidate) + found.others.take(2)
            if (found.sure || found.newKind != null || found.yours) {
                events.event("scan_front", "sure" to found.sure, "yours" to found.yours, "new_kind" to found.newKind,
                    "candidate" to describe(found.candidate), "words" to found.words, "kinds" to found.kinds.map { Kinds.label(it) },
                    "ms" to System.currentTimeMillis() - openedAt)
                sheet = if (found.newKind != null) FrontSheet.NewKind(found.candidate, found.newKind, frame?.let(::centerCrop))
                else FrontSheet.Found(found, frame?.let(::centerCrop))
            }
        }
    }

    val labelMode = newKindOf != null || mode == ScanMode.LABEL || (mode == ScanMode.AUTO && (panelSeen || gaveUp))

    /** A capture that missed a line takes it from the live frames, when two of them agreed. */
    fun withLive(p: NutritionFactsParser.Panel): NutritionFactsParser.Panel {
        val extra = votes.mapNotNull { (k, vs) ->
            if (k in p.perServing) null else vs.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.let { k to it.key }
        }.toMap()
        if (extra.isEmpty()) return p
        events.event("scan_live_filled", "keys" to extra)
        return p.copy(perServing = p.perServing + extra, servingGrams = p.servingGrams ?: liveServing)
    }

    /** Takes the photo and reads it: the button, or by itself once the label has been held still. */
    fun readLabel(trigger: String) {
        val c = capture ?: return
        if (reading || done) return
        reading = true
        message = null
        val t0 = System.currentTimeMillis()
        events.event("scan_capture", "torch" to torch, "panel_in_view" to panelSeen, "trigger" to trigger, "mode" to mode.name,
            "live_lines" to seen.size)
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
                // The photo the text was read from: shown on the check screen
                // and kept with the label, so a number can be checked against it.
                val bitmap = runCatching { rotated(image.toBitmap(), rotation) }.getOrNull()
                val photo = if (events.enabled) bitmap?.let { b -> Executors.newSingleThreadExecutor().submit<String?> { events.saveScan(b) } } else null
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
                        // With each line's box, so a two-column Supplement Facts panel
                        // pairs every name with the amount on its row.
                        val boxed = text.textBlocks.flatMap { b -> b.lines.mapNotNull { l ->
                            l.boundingBox?.let { r -> NutritionFactsParser.OcrLine(l.text, r.left, r.top, r.right, r.bottom) }
                        } }
                        val p = if (boxed.isNotEmpty()) NutritionFactsParser.parse(boxed) else NutritionFactsParser.parse(text.text)
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
                            "missing" to REQUIRED.filter { it != "serving" && it !in p.perServing },
                            "suspect" to p.issues.map { it.code },
                        )
                        if (p.issues.isNotEmpty()) events.event("suspect", "where" to "scan", "issues" to p.issues.map { it.code },
                            "per_serving" to p.perServing, "serving_grams" to p.servingGrams)
                        // Several labels in the one photo: which mix was it?
                        val several = if (boxed.isNotEmpty()) NutritionFactsParser.splitPanels(boxed).takeIf { it.size >= 2 }
                            ?.map { it.title to NutritionFactsParser.parse(it.lines) }?.filter { it.second.perServing.size >= 3 }.orEmpty() else emptyList()
                        if (several.size >= 2) {
                            events.event("scan_several_labels", "n" to several.size, "titles" to several.map { it.first },
                                "calories" to several.map { it.second.perServing[Metric.CALORIES.key] })
                            reading = false; panelPhoto = bitmap; choices = several
                            return@addOnSuccessListener
                        }
                        if (p.perServing.size < 3) {
                            reading = false
                            if (mode == ScanMode.AUTO) gaveUp = true
                            message = "Couldn't read a Nutrition Facts panel. Try closer, with the whole panel in the box. Tap the picture to focus."
                            // A photo of the front, not the panel: what its big words say.
                            if (!frontDone && boxed.isNotEmpty() && mode != ScanMode.LABEL) scope.launch {
                                val found = withContext(Dispatchers.IO) { frontAnswer(boxed.map { it.text to it.height }) }
                                events.event("scan_front_matches", "from" to "photo", "candidate" to found?.candidate?.let { describe(it) },
                                    "sure" to found?.sure)
                                if (found != null && (found.sure || found.yours)) {
                                    guesses = listOf(found.candidate) + found.others.take(2)
                                    sheet = FrontSheet.Found(found, bitmap?.let(::centerCrop))
                                }
                            }
                        } else { message = null; panelPhoto = bitmap; panel = withLive(p) }
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
    }

    // Held still with nothing new for a while, or every line read: it reads what it has.
    LaunchedEffect(labelMode, panelSeen, lastNewAt, reading, autoRead, capture) {
        if (!labelMode || !panelSeen || reading || done || autoRead || capture == null || choices.isNotEmpty()) return@LaunchedEffect
        val all = REQUIRED.all { it in seen }
        delay(if (all) 1200 else STILL_MS)
        if (!reading && !done && choices.isEmpty()) { autoRead = true; readLabel(if (all) "auto_all_lines" else "auto_still") }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val base = newKindOf
        if (base != null && newKindName != null) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(Kinds.nameFor(base, newKindName!!), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${base.brand ?: "New kind"} · now its Nutrition Facts", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ScanMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = {
                            if (mode != m) {
                                events.event("scan_mode", "from" to mode.name, "to" to m.name)
                                mode = m
                                if (m == ScanMode.LABEL) frontDone = true
                                if (m != ScanMode.AUTO) gaveUp = false
                            }
                        },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, ScanMode.entries.size),
                        icon = {},
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) { Text(m.label, maxLines = 1) }
                }
            }
            Text(
                when (mode) {
                    ScanMode.AUTO -> "Auto picks for you: a barcode, the front, or the Nutrition Facts."
                    ScanMode.BARCODE -> "Only the barcode. Hold it flat in the middle."
                    ScanMode.FRONT -> "Only the front. Hold the product's name in view."
                    ScanMode.LABEL -> "Only the Nutrition Facts. Fill the frame with the panel."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
            )
        }
        var focusAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
        LaunchedEffect(focusAt) { if (focusAt != null) { delay(900); focusAt = null } }
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(16.dp))) {
            CameraView(
                onBarcode = ::onBarcode,
                onText = ::onText,
                onReady = { c, cam -> capture = c; camera = cam },
                onFocus = { at -> focusAt = at; events.event("scan_focus") },
            )
            focusAt?.let { at -> FocusRing(at) }
            val box = panelBox
            val frameAt = frameSize
            if (labelMode && box != null && frameAt != null) LiveBox(box, frameAt)
            else if (guide && labelMode) GuideBox()
            if (mode == ScanMode.AUTO) Marks(
                barcode = !done,
                front = !frontDone && !gaveUp && !panelSeen,
                frontOff = gaveUp || frontDone,
                label = labelMode,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            )
            // Both always there: the light, and the box that shows where the label goes.
            Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (camera?.cameraInfo?.hasFlashUnit() != false) {
                    FilledTonalIconToggleButton(
                        checked = torch,
                        onCheckedChange = {
                            torch = it
                            camera?.cameraControl?.enableTorch(it)
                            events.event("scan_torch", "on" to it)
                        },
                    ) {
                        Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = if (torch) "Turn the light off" else "Turn the light on")
                    }
                }
                FilledTonalIconToggleButton(
                    checked = guide,
                    onCheckedChange = {
                        events.event("scan_guide", "on" to it)
                        scope.launch { settings.setScanGuide(it) }
                    },
                ) {
                    Icon(Icons.Filled.CropFree, contentDescription = if (guide) "Hide the label box" else "Show the label box")
                }
            }
            Surface(
                color = Color.White.copy(alpha = 0.9f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 14.dp, end = 120.dp),
            ) {
                Text(
                    status ?: when {
                        reading -> "Reading the label…"
                        panelSeen && labelMode -> "Hold still · reading"
                        mode == ScanMode.LABEL || newKindOf != null -> "Fill the frame with the Nutrition Facts"
                        mode == ScanMode.BARCODE -> "Looking for a barcode · tap to focus"
                        gaveUp && newKindOf == null -> "Couldn't tell from the front"
                        frontSeen && !frontDone -> "Reading the front…"
                        mode == ScanMode.FRONT -> "Looking for the product's name"
                        else -> "Looking for a barcode, the front or the label"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF1C1C1A),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        if (labelMode && (panelSeen || mode == ScanMode.LABEL || newKindOf != null)) {
            LabelProgress(
                seen = seen,
                value = { k -> if (k == "serving") liveServing else votes[k]?.maxByOrNull { it.value }?.key },
                started = panelSeen,
                reading = reading,
                ready = capture != null,
                onRead = { readLabel("read_now") },
                onAll = { readLabel("thats_all") },
            )
        } else if (gaveUp && mode == ScanMode.FRONT) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Couldn't tell from the front", fontWeight = FontWeight.SemiBold)
                    Text("Hold the product's name in view, or read its Nutrition Facts instead.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary)
                    OutlinedButton(onClick = { mode = ScanMode.LABEL; frontDone = true; gaveUp = false }) { Text("Read the label instead") }
                }
            }
        } else if (gaveUp) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Turn it to the Nutrition Facts", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Fill the frame with the panel. It reads every line it can find, about 10 seconds, once per product. After that, just say its name.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (guesses.isNotEmpty()) {
                SectionLabel("Or is it one of these?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    guesses.take(3).forEach { c ->
                        FilterChip(selected = false, onClick = {
                            events.event("scan_front_picked", "id" to c.id, "from" to "gave_up")
                            sheet = FrontSheet.Found(FrontAnswer(c, false, false, emptyList(), emptyList(), null, siblingsOf(c)), null)
                        }, label = { Text(shortName(c), maxLines = 1) })
                    }
                }
            }
        } else if (mode == ScanMode.AUTO) {
            Text("If it can't tell from the front in about 5 seconds, it will ask for the label.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val progressShown = labelMode && (panelSeen || mode == ScanMode.LABEL || newKindOf != null)
            if (!progressShown && mode != ScanMode.BARCODE) Button(enabled = !reading && capture != null, onClick = { readLabel("button") }) { Text("Read label") }
            OutlinedButton(onClick = { events.event("scan_cancel", "ms" to System.currentTimeMillis() - openedAt); onCancel() }) { Text("Cancel") }
        }
        if (newKindOf == null) {
            val labels by remember { ServiceLocator.database.personalFoodDao().observeAll() }.collectAsState(initial = emptyList())
            val mine = remember(labels) { labels.mapNotNull { ServiceLocator.personalStore.decode(it) }.count { !it.isMeal && it.candidate?.origin == Origin.LABEL } }
            if (mine > 0) {
                TextButton(onClick = { events.event("my_labels_open", "where" to "scan"); onMyLabels() }) {
                    Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  My labels ($mine)")
                }
            }
        }
        if (!pack.isInstalled) {
            Text(
                "Tip: with the label pack installed (Settings), most barcodes and fronts are found without going online.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }

    when (val s = sheet) {
        is FrontSheet.Found -> FoundSheet(
            s,
            onYes = { c ->
                events.event("scan_front_yes", "id" to c.id, "kind" to Kinds.label(c))
                sheet = null; frontDone = true; deliver(c, if (s.answer.yours) "front_yours" else "front")
            },
            onLabel = {
                events.event("scan_front_no", "id" to s.answer.candidate.id)
                expected = s.answer.candidate.takeIf { s.answer.sure }
                sheet = null; frontDone = true; gaveUp = true
            },
            onNotListed = {
                events.event("scan_kind_not_listed", "id" to s.answer.candidate.id)
                sheet = FrontSheet.NewKind(s.answer.candidate, "", s.thumb)
            },
            onDismiss = { sheet = null; frontDone = true },
        )
        is FrontSheet.NewKind -> NewKindSheet(
            s,
            onScan = { kind ->
                events.event("scan_new_kind", "base" to s.base.id, "kind" to kind, "how" to "scan")
                newKindOf = s.base; newKindName = kind; sheet = null; frontDone = true
            },
            onUseBase = { kind ->
                events.event("scan_new_kind", "base" to s.base.id, "kind" to kind, "how" to "use_base")
                sheet = null; frontDone = true
                // Logged with the closest kind's numbers and marked as typical, not a
                // label: the confirm card says so, and the next scan of its front asks once.
                deliver(s.base.copy(id = "kindguess:${s.base.id}:${kind.lowercase()}", name = Kinds.nameFor(s.base, kind),
                    origin = Origin.CURATED, barcode = null), "front_new_kind_estimate")
            },
            onDismiss = { sheet = null; frontDone = true },
        )
        null -> Unit
    }
    if (choices.isNotEmpty()) SeveralLabelsSheet(
        choices,
        onPick = { title, p ->
            events.event("scan_several_pick", "title" to title, "calories" to p.perServing[Metric.CALORIES.key])
            pickedTitle = title; choices = emptyList(); panel = p
        },
        onDismiss = { choices = emptyList() },
    )
}

/** The panel's outline in the frame: the lines that name a nutrient, padded a little. */
private fun panelBoxOf(lines: List<NutritionFactsParser.OcrLine>): android.graphics.Rect? {
    val words = listOf("nutrition", "serving", "calories", "fat", "cholest", "sodium", "carbo", "fiber", "sugar", "protein",
        "vitamin", "calcium", "iron", "potass", "daily value")
    val hits = lines.filter { l -> val t = l.text.lowercase(); words.any { it in t } }
    if (hits.size < 3) return null
    val r = android.graphics.Rect(hits.minOf { it.left }, hits.minOf { it.top }, hits.maxOf { it.right }, hits.maxOf { it.bottom })
    val px = (r.width() * 0.06).toInt(); val py = (r.height() * 0.04).toInt()
    return android.graphics.Rect(r.left - px, r.top - py, r.right + px, r.bottom + py)
}

/** The box around the label, moving with it as the camera sees it (note S20). */
@Composable
private fun LiveBox(box: android.graphics.Rect, frame: Pair<Int, Int>) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val vw = constraints.maxWidth.toFloat(); val vh = constraints.maxHeight.toFloat()
        val (fw, fh) = frame
        // The preview fills the view and crops the rest, centred.
        val scale = maxOf(vw / fw, vh / fh)
        val dx = (vw - fw * scale) / 2f; val dy = (vh - fh * scale) / 2f
        val spec = androidx.compose.animation.core.tween<Float>(260)
        val l by androidx.compose.animation.core.animateFloatAsState((box.left * scale + dx).coerceIn(0f, vw), spec, label = "l")
        val t by androidx.compose.animation.core.animateFloatAsState((box.top * scale + dy).coerceIn(0f, vh), spec, label = "t")
        val r by androidx.compose.animation.core.animateFloatAsState((box.right * scale + dx).coerceIn(0f, vw), spec, label = "r")
        val b by androidx.compose.animation.core.animateFloatAsState((box.bottom * scale + dy).coerceIn(0f, vh), spec, label = "b")
        val stroke = with(androidx.compose.ui.platform.LocalDensity.current) { 2.5.dp.toPx() }
        val radius = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                Color(0xFF7FD3A4), topLeft = androidx.compose.ui.geometry.Offset(l, t),
                size = androidx.compose.ui.geometry.Size((r - l).coerceAtLeast(1f), (b - t).coerceAtLeast(1f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
            )
        }
    }
}

/**
 * "13 of 16 lines": what has been read off the label so far, and what it is still
 * looking for (note S7). It reads by itself once it has every line, or once the
 * label has been held still with nothing new for [STILL_MS].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelProgress(
    seen: Set<String>,
    value: (String) -> Double?,
    started: Boolean,
    reading: Boolean,
    ready: Boolean,
    onRead: () -> Unit,
    onAll: () -> Unit,
) {
    val got = REQUIRED.filter { it in seen }
    val missing = REQUIRED - got.toSet()
    val also = seen.filter { it !in REQUIRED }.mapNotNull { Metric.fromKey(it) }
    val amber = com.nutritionscribe.app.ui.theme.limitColors().over
    fun label(k: String): String {
        val v = value(k)
        if (k == "serving") return "Serving" + (v?.let { " ${fmt(it)} g" } ?: "")
        val m = Metric.fromKey(k) ?: return k
        return m.shortLabel + (v?.let { " ${fmt(it)} ${if (m == Metric.CALORIES) "" else m.unit}".trimEnd() } ?: "")
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (reading) "Reading the photo…" else if (started) "Reading the label" else "Waiting for the label",
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${got.size} of ${REQUIRED.size} lines", style = NumberStyle, color = MaterialTheme.colorScheme.primary)
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { got.size / REQUIRED.size.toFloat() }, modifier = Modifier.fillMaxWidth().heightIn(min = 6.dp),
            )
            if (got.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (got.map { label(it) } + also.map { label(it.key) }).forEach { t ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                            Text(t, fontSize = 12.5.sp)
                        }
                    }
                }
            }
            if (missing.isNotEmpty()) {
                SectionLabel(if (started) "Still looking for" else "Looking for")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    missing.forEach { k ->
                        Surface(shape = RoundedCornerShape(14.dp), color = Color.Transparent, border = BorderStroke(1.dp, amber)) {
                            Text(if (k == "serving") "Serving" else Metric.fromKey(k)?.shortLabel ?: k, fontSize = 12.5.sp, color = amber,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                        }
                    }
                }
            }
            Text(
                if (missing.isEmpty()) "Every line found. Reading it now."
                else "Hold steady. If nothing new turns up for 8 s, it moves on and you can type the rest. Not every label prints every line.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRead, enabled = ready && !reading, modifier = Modifier.heightIn(min = 44.dp)) { Text("Read now") }
                OutlinedButton(onClick = onAll, enabled = ready && !reading && started, modifier = Modifier.heightIn(min = 44.dp)) { Text("That's all it lists") }
            }
        }
    }
}

/** "This package has 3 labels": each a different mix, so which one was it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeveralLabelsSheet(
    choices: List<Pair<String?, NutritionFactsParser.Panel>>,
    onPick: (String?, NutritionFactsParser.Panel) -> Unit,
    onDismiss: () -> Unit,
) {
    com.nutritionscribe.app.ui.feedback.ReportableSheet("Several labels", onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("This package has ${choices.size} labels", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Each is a different mix. Which did you eat?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            choices.forEachIndexed { i, (title, p) ->
                Surface(
                    onClick = { onPick(title, p) }, shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title ?: "Label ${i + 1}", fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(p.servingText?.trim(), p.perServing[Metric.CALORIES.key]?.let { "${fmt(it)} kcal" },
                                p.perServing[Metric.PROTEIN.key]?.let { "${fmt(it)} g protein" }).joinToString(" \u00B7 "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            Text("The one you pick is checked next, with its name filled in from the label.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** What the front showed, as the sheet offers it. */
internal data class FrontAnswer(
    val candidate: FoodCandidate,
    val sure: Boolean,
    /** It is one of their own scanned labels. */
    val yours: Boolean,
    val others: List<FoodCandidate>,
    val words: List<String>,
    val newKind: String?,
    val kinds: List<FoodCandidate>,
)

private sealed interface FrontSheet {
    data class Found(val answer: FrontAnswer, val thumb: Bitmap?) : FrontSheet
    data class NewKind(val base: FoodCandidate, val kind: String, val thumb: Bitmap?) : FrontSheet
}

/**
 * The front's words, against the label pack and their own scanned labels. Their
 * own label wins when its words are on the box: a kind they scanned last week is
 * found by its front from then on.
 */
private fun frontAnswer(lines: List<Pair<String, Int>>): FrontAnswer? {
    val m: FrontMatcher.Match? = runCatching { ServiceLocator.labelPack.frontMatcher()?.match(lines) }.getOrNull()
    val read = (LabelPackSource.words(lines.joinToString(" ") { it.first }) + m?.words.orEmpty()).toSet()
    val tallest = lines.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val bigRead = LabelPackSource.words(lines.filter { it.second >= tallest * 0.45 }.joinToString(" ") { it.first }).toSet()
    val mine = runCatching { ServiceLocator.personalStore.all() }.getOrDefault(emptyList())
        .filter { !it.isMeal && it.candidate?.origin == Origin.LABEL || it.candidate?.id?.startsWith("kindguess:") == true }
        .mapNotNull { f ->
            val c = f.candidate ?: return@mapNotNull null
            val own = (LabelPackSource.words(c.name) + LabelPackSource.words(c.brand.orEmpty())).toSet()
            val kindWords = LabelPackSource.words(Kinds.label(c)).filter { Kinds.isFlavourWord(it) }
            val hit = own.count { it in read }
            // Its kind must be on the box, printed big, and most of its name.
            if (kindWords.isNotEmpty() && !kindWords.all { it in bigRead }) return@mapNotNull null
            if (hit < 2 || hit < own.size * 0.6) return@mapNotNull null
            f to hit
        }.maxByOrNull { it.second }?.first
    if (mine != null) {
        val c = mine.candidate!!
        if (c.id.startsWith("kindguess:")) {
            // Logged before with a near kind's numbers: now is the time for its label.
            val base = m?.candidate ?: c
            return FrontAnswer(base, false, false, emptyList(), m?.words.orEmpty(), Kinds.label(c), emptyList())
        }
        val kinds = (mine.kinds + siblingsOf(c)).distinctBy { Kinds.label(it).lowercase() }.filter { !Kinds.label(it).equals(Kinds.label(c), true) }
        return FrontAnswer(c, true, true, emptyList(), m?.words.orEmpty(), null, kinds)
    }
    if (m == null) return null
    return FrontAnswer(m.candidate, m.sure, false, m.others, m.words, m.newKind, if (m.sure) siblingsOf(m.candidate) else emptyList())
}

private fun siblingsOf(c: FoodCandidate): List<FoodCandidate> =
    if (c.id.startsWith("label:")) runCatching { ServiceLocator.labelPack.source()?.siblings(c, limit = 5).orEmpty() }.getOrDefault(emptyList()) else emptyList()

/** Brand and product, without the kind repeated after the comma. */
private fun shortName(c: FoodCandidate): String = ItemDraft.withBrand(c.copy(name = c.name.substringBeforeLast(", ").let { main ->
    val kind = c.name.substringAfterLast(", ", "")
    if (kind.isNotBlank() && !main.contains(kind, ignoreCase = true)) "$main $kind" else main
}), " ")

private fun looksLikePanel(text: String): Boolean {
    val t = text.lowercase()
    if ("nutrition facts" in t || "supplement facts" in t) return true
    return listOf("calories", "total fat", "sodium", "carbohydrate", "protein", "serving size", "daily value").count { it in t } >= 3
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FoundSheet(
    s: FrontSheet.Found,
    onYes: (FoodCandidate) -> Unit,
    onLabel: () -> Unit,
    onNotListed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val a = s.answer
    com.nutritionscribe.app.ui.feedback.ReportableSheet("Found from the front", onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("Found from the front")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(s.thumb, a.candidate)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (a.yours) {
                        Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text("Your label", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    Text("Is this ${shortName(a.candidate)}?", fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp)
                    Text(subline(a.candidate), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Button(onClick = { onYes(a.candidate) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Yes, that's it") }
            OutlinedButton(onClick = onLabel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("No, scan the label") }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Or another kind")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    a.kinds.forEach { k -> FilterChip(selected = false, onClick = { onYes(k) }, label = { Text(Kinds.label(k)) }) }
                    Surface(
                        onClick = onNotListed,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        color = Color.Transparent,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("+ Not listed", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
            }
            Text(
                if (a.yours) "The numbers are from the label you scanned."
                else "The numbers come from the label list, not a guess. If they don't match your package, scan its label once and it remembers yours.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewKindSheet(s: FrontSheet.NewKind, onScan: (String) -> Unit, onUseBase: (String) -> Unit, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(s.kind) }
    val product = shortName(s.base).let { n -> n.replace(Regex("\\b${Regex.escape(Kinds.label(s.base))}\\b", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim() }
    com.nutritionscribe.app.ui.feedback.ReportableSheet("Found from the front (kind)", onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("Found from the front")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(s.thumb, s.base)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (kind.isBlank()) "Which kind of $product?" else "$product ${kind.trim()} isn't saved yet",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp,
                    )
                    Text("It knows $product, just not this kind.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
            OutlinedTextField(
                value = kind, onValueChange = { kind = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("What kind is it?") },
                supportingText = { Text(if (s.kind.isNotBlank()) "Read from the front. Change it if it's wrong." else "As the box says it, like Pumpkin Spice.") },
            )
            Button(onClick = { onScan(kind.trim()) }, enabled = kind.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Scan its label · about 10 s")
            }
            OutlinedButton(onClick = { onUseBase(kind.trim()) }, enabled = kind.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Use ${Kinds.label(s.base)} for now")
            }
            Text(
                "For now: logged with ${Kinds.label(s.base)}'s numbers and marked as typical. Next time you have the box out, it asks for the label once.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

private fun subline(c: FoodCandidate): String = listOfNotNull(
    c.brand,
    c.labelServingText ?: c.labelServingGrams?.let { "${it.toInt()} g" },
    (c.labelServingGrams ?: c.defaultGrams)?.let { g -> c.nutrientsFor(g)[Metric.CALORIES.key] }
        ?.let { "${Math.round(it)} kcal" } ?: c.perServing?.get(Metric.CALORIES.key)?.let { "${Math.round(it)} kcal" },
).joinToString(" · ")

/** The front as the camera saw it, or a plain tile when there is no picture. */
@Composable
private fun Thumb(b: Bitmap?, c: FoodCandidate) {
    val mod = Modifier.size(width = 60.dp, height = 78.dp).clip(RoundedCornerShape(8.dp))
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = "The front of the package", contentScale = ContentScale.Crop, modifier = mod)
    } else {
        Box(mod.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** Barcode, Front, Label: what the camera is looking for right now. */
@Composable
private fun Marks(barcode: Boolean, front: Boolean, frontOff: Boolean, label: Boolean, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Mark("Barcode", Icons.Filled.QrCodeScanner, on = barcode && !label, off = false)
        Mark("Front", Icons.Outlined.Inventory2, on = front, off = frontOff)
        Mark("Label", Icons.Outlined.ReceiptLong, on = label, off = false)
    }
}

@Composable
private fun Mark(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, off: Boolean) {
    val bg = if (on) MaterialTheme.colorScheme.primaryContainer else Color(0xB8141615)
    val fg = when {
        on -> MaterialTheme.colorScheme.onPrimaryContainer
        off -> Color(0xFFA8A79F)
        else -> Color(0xFFF2F1EC)
    }
    Surface(shape = RoundedCornerShape(15.dp), color = bg) {
        Row(Modifier.heightIn(min = 30.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (on) Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
            Text(text, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Where the panel goes: a thin green outline with its corners marked. */
@Composable
private fun GuideBox() {
    Box(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 52.dp, bottom = 68.dp)) {
        Box(Modifier.fillMaxSize().border(1.5.dp, Color(0xFF6FBF94), RoundedCornerShape(12.dp)))
        Surface(color = Color(0x99141615), shape = RoundedCornerShape(8.dp), modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
            Text("Nutrition Facts here", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
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
 * which is to say every time the status line changed. Every frame is checked for
 * a barcode; one every [READ_EVERY_MS] is also read for words.
 */
@Composable
private fun CameraView(
    onBarcode: (String) -> Unit,
    onText: (List<NutritionFactsParser.OcrLine>, Bitmap?) -> Unit,
    onReady: (ImageCapture, androidx.camera.core.Camera) -> Unit,
    onFocus: (androidx.compose.ui.geometry.Offset) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestBarcode by rememberUpdatedState(onBarcode)
    val latestText by rememberUpdatedState(onText)
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
        val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var lastRead = 0L
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
                val rotation = proxy.imageInfo.rotationDegrees
                val input = InputImage.fromMediaImage(media, rotation)
                val now = System.currentTimeMillis()
                val readNow = now - lastRead >= READ_EVERY_MS
                scanner.process(input)
                    .addOnSuccessListener { codes ->
                        codes.firstOrNull()?.rawValue?.let { raw -> ContextCompat.getMainExecutor(context).execute { latestBarcode(raw) } }
                    }
                    .addOnCompleteListener {
                        if (!readNow) { proxy.close(); return@addOnCompleteListener }
                        lastRead = now
                        reader.process(input)
                            .addOnSuccessListener { text ->
                                val lines = text.textBlocks.flatMap { b -> b.lines.mapNotNull { l ->
                                    l.boundingBox?.let { r -> NutritionFactsParser.OcrLine(l.text, r.left, r.top, r.right, r.bottom) }
                                } }
                                if (lines.isEmpty()) return@addOnSuccessListener
                                val frame = runCatching { rotated(proxy.toBitmap(), rotation) }.getOrNull()
                                ContextCompat.getMainExecutor(context).execute { latestText(lines, frame) }
                            }
                            .addOnCompleteListener { proxy.close() }
                    }
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
            reader.close()
            executor.shutdown()
        }
    }

    AndroidView(modifier = Modifier.fillMaxSize(), factory = { view })
}

/**
 * What was read off the panel, as editable boxes. Nothing is used until they tap Use.
 *
 * Most people tap Use without reading a box, so the screen opens on what matters:
 * the photo, a name (offered from the label pack by the panel's own numbers, or
 * the line they scanned for), anything that looks misread with the likely number
 * one tap away, the Use button, calories, protein and the nutrients they have
 * goals for. The rest is one tap down.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelForm(
    panel: NutritionFactsParser.Panel,
    photo: Bitmap?,
    nameHint: String?,
    fromFront: Boolean,
    brand: String?,
    /** The product the front showed: its own label should roughly agree. */
    expected: FoodCandidate?,
    onUse: (FoodCandidate) -> Unit,
    onRetake: () -> Unit,
) {
    val events = ServiceLocator.debugEvents
    var name by remember { mutableStateOf(nameHint.orEmpty()) }
    var serving by remember { mutableStateOf(panel.servingGrams?.let { it.toInt().toString() }.orEmpty()) }
    val values = remember {
        mutableStateMapOf<Metric, String>().apply {
            Metric.entries.forEach { m -> panel.perServing[m.key]?.let { put(m, fmt(it)) } }
        }
    }
    // The label pack's products with these very numbers: the panel never says its name.
    var matches by remember { mutableStateOf<List<FoodCandidate>>(emptyList()) }
    var picked by remember { mutableStateOf<FoodCandidate?>(null) }
    var zoom by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (fromFront) return@LaunchedEffect
        matches = withContext(Dispatchers.IO) {
            runCatching { ServiceLocator.labelPack.source()?.byPanel(panel.perServing, panel.servingGrams).orEmpty() }.getOrDefault(emptyList())
        }
        events.event("scan_panel_matches", "n" to matches.size, "names" to matches.map { ItemDraft.withBrand(it, " ") },
            "hint" to nameHint)
    }
    val tracked by remember { ServiceLocator.goalsRepository.enabledGoals }.collectAsState(initial = emptyList())
    val now = values.mapNotNull { (m, v) -> v.toDoubleOrNull()?.let { m.key to it } }.toMap()
    // Re-checked as they type, so fixing the box clears its warning.
    val grams = serving.toDoubleOrNull()
    val issues = remember(now, serving) { NutrientSanity.check(now, grams) }
    val suggested = remember(now, serving) { NutrientSanity.suggest(now, grams) }
    // Numbers that add up but are far from the same product's own label: the misreads
    // the arithmetic can't see. Only a note, never a block (recipes change).
    val apart = remember(now, serving, expected) {
        val e = expected ?: return@remember emptyMap()
        val g = grams ?: e.labelServingGrams ?: return@remember emptyMap()
        NutrientSanity.against(now, e.nutrientsFor(g))
    }
    var dismissed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val fixes = remember { panel.reads.filter { it.fix != null }.associateBy { it.metric } }
    // Numbers that can't all be true used to go straight into My Foods and repeat on
    // every "log again". Now the first tap stops and points at the box; only a
    // second tap uses them as they are (labels do sometimes break the arithmetic).
    var warned by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    // A mineral typed as the label's % Daily Value (notes S13, S15), kept as the amount.
    val pctText = remember { mutableStateMapOf<Metric, String>() }
    // Lines the reader didn't offer a box for, added by hand (omega-3 on a trail mix, note S14).
    var added by remember { mutableStateOf<List<Metric>>(emptyList()) }
    LaunchedEffect(issues.isEmpty()) { if (issues.isEmpty()) warned = false }
    val liquid = Liquids.isLiquid(name, panel.servingText)

    fun use() {
        if (issues.isNotEmpty() && !warned) {
            warned = true
            showAll = true
            events.event("scan_use_blocked", "suspect" to issues.map { it.code }, "suggested" to suggested)
            return
        }
        val per = values.mapNotNull { (m, v) -> v.toDoubleOrNull()?.let { m.key to it } }.toMap()
        // Stored in grams: a drink's millilitres are weighed by its density.
        val g = serving.toDoubleOrNull()?.let { if (liquid) Liquids.grams(it, name) else it }
        val p = NutritionFactsParser.Panel(panel.servingText, g, per)
        // What they changed is the most useful line in the log: every edit
        // here is something the reader got wrong.
        val keys = (panel.perServing.keys + per.keys).distinct()
        events.event(
            "scan_use",
            "name" to name, "serving_read" to panel.servingGrams, "serving_used" to serving.toDoubleOrNull(),
            "read" to panel.perServing, "used" to per,
            "changed" to keys.filter { panel.perServing[it] != per[it] }.associateWith { k -> mapOf("read" to panel.perServing[k], "used" to per[k]) },
            "suspect_read" to panel.issues.map { it.code }, "suspect_used" to issues.map { it.code },
            "apart_from_label" to apart, "expected" to expected?.id,
            "picked_match" to picked?.id, "details_opened" to showAll, "name_from_hint" to (nameHint != null && name == nameHint),
        )
        onUse(p.asCandidate(name.trim(), brand = picked?.brand ?: brand))
    }

    @Composable
    fun box(m: Metric) {
        // Only the box most likely wrong gets the warning, not every box involved.
        val issue = issues.firstOrNull { it.metrics.firstOrNull() == m.key }
        val fix = fixes[m.key]?.takeIf { f -> values[m]?.toDoubleOrNull() == f.value }
        val likely = suggested[m.key]
        val other = apart[m.key]?.takeIf { m.key !in dismissed }
        val dv = m.defaultTarget.takeIf { m.isMicro && it > 0 }
        val inPct = dv != null && m in pctText
        Column {
            OutlinedTextField(
                value = if (inPct) pctText[m].orEmpty() else values[m].orEmpty(),
                onValueChange = { t ->
                    if (inPct && dv != null) {
                        pctText[m] = t
                        val pc = t.trim().removeSuffix("%").toDoubleOrNull()
                        if (pc != null) values[m] = fmt(Math.round(pc / 100.0 * dv * 100) / 100.0) else values.remove(m)
                    } else values[m] = t
                },
                label = { Text("${m.panelLabel} (${if (inPct) "% DV" else m.unit})") },
                isError = issue != null,
                supportingText = when {
                    inPct -> ({ Text(values[m]?.let { "${pctText[m]}% DV = $it ${m.unit} (the label's Daily Value, not your need)" } ?: "Type the % the label prints") })
                    issue != null && likely != null -> ({ Text("${fmt(values[m]?.toDoubleOrNull() ?: 0.0)} → ${fmt(likely)}? ${issue.message}") })
                    issue != null -> ({ Text(issue.message) })
                    other != null -> ({ Text("The label list says ${fmt(Math.round(other * 10) / 10.0)} for this. Worth a look.") })
                    fix?.fix == "g_read_as_9" -> ({ Text("Read as ${fmt(fix.asRead ?: 0.0)}; the last 9 looked like a \"g\"") })
                    fix?.fix == "from_percent_dv" -> ({ Text("Worked out from the % Daily Value") })
                    fix?.fix == "calories_apart" -> ({ Text("The large number printed apart; check it") })
                    fix?.fix == "kcal_from_macros" -> ({ Text("Couldn't read it; worked out from protein, carbs and fat. Check it") })
                    else -> null
                },
                trailingIcon = if (issue != null && likely != null) ({
                    TextButton(onClick = {
                        events.event("scan_suggestion_used", "metric" to m.key, "from" to values[m], "to" to likely, "where" to "box")
                        pctText.remove(m)
                        values[m] = fmt(likely)
                    }) { Text("Use ${fmt(likely)}") }
                }) else if (dv != null) ({
                    TextButton(onClick = {
                        events.event("scan_unit_toggle", "metric" to m.key, "to" to if (inPct) "amount" else "percent")
                        if (inPct) pctText.remove(m)
                        else pctText[m] = values[m]?.toDoubleOrNull()?.let { fmt(Math.round(it / dv * 100).toDouble()) }.orEmpty()
                    }) { Text(if (inPct) "%DV \u21C4 ${m.unit}" else "${m.unit} \u21C4 %DV", fontSize = 12.sp) }
                }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    val all = formMetrics(panel.perServing.keys)
    // What opens the screen: calories, protein, their goals, and anything doubtful.
    val doubtful = issues.mapNotNull { it.metrics.firstOrNull() }.toSet() + fixes.keys + apart.keys
    val summary = all.filter { m ->
        m == Metric.CALORIES || m == Metric.PROTEIN || m in tracked.map { it.metric } || m.key in doubtful
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The photo the numbers came from, to check a box against.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 64.dp, height = 84.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable(enabled = photo != null) { zoom = true; events.event("scan_photo_open") },
                contentAlignment = Alignment.Center,
            ) {
                if (photo != null) Image(photo.asImageBitmap(), contentDescription = "The label you scanned", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Outlined.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val got = REQUIRED.count { k -> if (k == "serving") panel.servingText != null || panel.servingGrams != null else k in panel.perServing }
                Text("Read $got of ${REQUIRED.size} lines", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                val missing = REQUIRED.filter { k -> k != "serving" && k !in panel.perServing }.mapNotNull { Metric.fromKey(it)?.shortLabel }
                if (missing.isNotEmpty()) Text("Not read: ${missing.joinToString(", ")}. Type them, or leave them if the label doesn't list them.",
                    style = MaterialTheme.typography.bodySmall, color = com.nutritionscribe.app.ui.theme.limitColors().over)
                if (photo != null) Text("Tap the photo to check a number against it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
                Text("Retake the photo", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onRetake).padding(vertical = 2.dp))
            }
        }
        // Named, it can be logged again by name and its other vitamins found; three
        // "Scanned label" chips in Log again were no use to anyone.
        OutlinedTextField(value = name, onValueChange = { name = it; picked = null }, label = { Text("What is it?") },
            placeholder = { Text("e.g. Food Club creamy peanut butter") }, singleLine = true,
            supportingText = { Text(when {
                fromFront && name == nameHint -> "Filled in from the front"
                matches.isNotEmpty() && name.isBlank() -> "Tap one below if it's that, or type it"
                else -> "Brand and product, so you can find it again"
            }) },
            modifier = Modifier.fillMaxWidth())
        if (matches.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                matches.forEach { c ->
                    val label = ItemDraft.withBrand(c, " ")
                    FilterChip(
                        selected = picked?.id == c.id,
                        onClick = {
                            name = label; picked = c
                            // The label pack knows what this photo missed: the weight of a
                            // serving above all.
                            if (serving.isBlank()) c.labelServingGrams?.let { serving = it.toInt().toString() }
                            c.labelServingGrams?.let { g ->
                                c.nutrientsFor(g).forEach { (k, v) ->
                                    Metric.fromKey(k)?.let { m -> if (values[m].isNullOrBlank()) values[m] = fmt(Math.round(v * 10) / 10.0) }
                                }
                            }
                            events.event("scan_match_picked", "id" to c.id, "name" to label)
                        },
                        label = { Text(label, maxLines = 2) },
                    )
                }
            }
        }
        if (issues.isNotEmpty()) {
            MisreadCard(
                issues = issues, suggested = suggested, values = now, warned = warned,
                onUse = { key, v ->
                    Metric.fromKey(key)?.let { m ->
                        events.event("scan_suggestion_used", "metric" to key, "from" to values[m], "to" to v, "where" to "card")
                        values[m] = fmt(v)
                    }
                },
            )
        } else if (apart.keys.any { it !in dismissed }) {
            ApartCard(apart.filterKeys { it !in dismissed }, now, expected) { dismissed = dismissed + apart.keys }
        }
        OutlinedTextField(value = serving, onValueChange = { serving = it }, label = { Text(if (liquid) "Serving (mL)" else "Serving (grams)") },
            supportingText = {
                // Supplements and some small packs print no weight ("2 gummies"). That's
                // fine: the numbers are per serving, and the serving is counted instead.
                Text(when {
                    panel.servingText != null && panel.servingGrams == null -> "Label says: ${panel.servingText}. No weight on the label? Leave this empty."
                    panel.servingText != null -> "Label says: ${panel.servingText}"
                    else -> "Leave empty if the label shows no weight"
                })
            },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        summary.forEach { box(it) }
        val rest = all - summary.toSet()
        if (rest.isNotEmpty()) {
            TextButton(onClick = {
                showAll = !showAll
                if (showAll) events.event("scan_details_open", "boxes" to rest.size)
            }) { Text(if (showAll) "Hide the other nutrients" else "All nutrients (${rest.size} more)") }
            if (showAll) rest.forEach { box(it) }
        }
        added.forEach { box(it) }
        var addOpen by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { addOpen = true }) { Text("+ Add a line the label lists") }
            androidx.compose.material3.DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                Metric.entries.filter { it !in all && it !in added }.forEach { m ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(m.label) }, onClick = {
                        events.event("scan_line_added", "metric" to m.key)
                        added = added + m; addOpen = false
                    })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::use, enabled = values.isNotEmpty() && name.isNotBlank()) { Text(if (warned) "Use anyway" else "Use these") }
            OutlinedButton(onClick = onRetake) { Text("Retake") }
        }
    }

    if (zoom && photo != null) {
        Dialog(onDismissRequest = { zoom = false }) {
            ZoomablePhoto(photo) { zoom = false }
        }
    }
}

/**
 * "One number looks misread": what is wrong in words, and the number it most
 * likely is, one tap away. The first Use stops here; most labels need one or two
 * boxes fixed, or nothing.
 */
@Composable
private fun MisreadCard(
    issues: List<NutrientSanity.Issue>,
    suggested: Map<String, Double>,
    values: Map<String, Double>,
    warned: Boolean,
    onUse: (String, Double) -> Unit,
) {
    val boxes = issues.mapNotNull { it.metrics.firstOrNull() }.distinct()
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    if (boxes.size == 1) "One number looks misread" else "${boxes.size} numbers look misread",
                    fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            issues.distinctBy { it.metrics.firstOrNull() }.forEach { issue ->
                val key = issue.metrics.firstOrNull() ?: return@forEach
                val m = Metric.fromKey(key)
                val likely = suggested[key]
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        buildString {
                            append("${m?.panelLabel ?: key} says ${fmt(values[key] ?: 0.0)} ${m?.unit.orEmpty()}. ${issue.message}.")
                            if (likely != null) append(" It's probably ${fmt(likely)} ${m?.unit.orEmpty()}.")
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    if (likely != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumberChip(fmt(values[key] ?: 0.0), struck = true)
                            Text("→", color = MaterialTheme.colorScheme.onErrorContainer)
                            NumberChip(fmt(likely), struck = false)
                            Button(onClick = { onUse(key, likely) }, modifier = Modifier.heightIn(min = 40.dp)) { Text("Use ${fmt(likely)}") }
                        }
                    }
                }
            }
            Text(
                if (warned) "Still off? Fix the marked box, or tap Use anyway if the label really says so."
                else "Check the marked box against the photo.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun NumberChip(text: String, struck: Boolean) {
    Surface(shape = RoundedCornerShape(8.dp), color = if (struck) Color.Transparent else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onErrorContainer.copy(alpha = if (struck) 0.4f else 0.8f))) {
        Text(
            text, style = NumberStyle.copy(fontSize = 16.sp,
                textDecoration = if (struck) androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Numbers that add up but differ from the same product's label: worth a glance, not a stop. */
@Composable
private fun ApartCard(apart: Map<String, Double>, values: Map<String, Double>, expected: FoodCandidate?, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Worth a glance", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                apart.entries.joinToString(" ") { (k, e) ->
                    val m = Metric.fromKey(k)
                    "${m?.panelLabel ?: k} reads ${fmt(values[k] ?: 0.0)}; ${expected?.let { shortName(it) } ?: "the label list"} says ${fmt(Math.round(e * 10) / 10.0)}."
                } + " Recipes change, so if the photo agrees, it's fine.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onDismiss) { Text("They match the photo") }
        }
    }
}

/** The label photo, pinch to zoom: to read a number off it. */
@Composable
private fun ZoomablePhoto(photo: Bitmap, onClose: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val state = androidx.compose.foundation.gestures.rememberTransformableState { zoomChange, pan, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset += pan
    }
    Surface(shape = RoundedCornerShape(16.dp), color = Color.Black) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f).clickable(onClick = onClose)) {
            Image(
                photo.asImageBitmap(), contentDescription = "The label you scanned",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                    .transformable(state),
            )
            Text("Pinch to zoom · tap to close", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp))
        }
    }
}

private fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)

private val LABEL_MICROS = listOf(Metric.CHOLESTEROL, Metric.CALCIUM, Metric.IRON, Metric.POTASSIUM, Metric.VITAMIN_D)

/** The boxes on the scan form: macros with added sugar and trans fat under their whole, the label micros, then anything else read. */
private fun formMetrics(read: Set<String>): List<Metric> {
    val macros = Metric.MACROS.flatMap { m ->
        when (m) {
            Metric.SUGAR -> listOf(m, Metric.ADDED_SUGAR)
            Metric.SATURATED_FAT -> listOf(m, Metric.TRANS_FAT)
            else -> listOf(m)
        }
    }
    val base = (macros + LABEL_MICROS).distinct()
    return base + Metric.entries.filter { it.key in read && it !in base }
}

private fun rotated(b: Bitmap, degrees: Int): Bitmap =
    if (degrees == 0) b else Bitmap.createBitmap(
        b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }, true,
    )

/** The middle of a frame, where the box was held: the sheet's thumbnail. */
private fun centerCrop(b: Bitmap): Bitmap {
    val w = (b.width * 0.7f).toInt().coerceAtLeast(1)
    val h = (b.height * 0.7f).toInt().coerceAtLeast(1)
    return Bitmap.createBitmap(b, (b.width - w) / 2, (b.height - h) / 2, w, h)
}

/** A found product, as much of it as helps tell a wrong match from a right one. */
internal fun describe(c: FoodCandidate): Map<String, Any?> = mapOf(
    "id" to c.id, "name" to c.name, "brand" to c.brand, "origin" to c.origin,
    "serving" to c.servingLabel, "serving_grams" to (c.labelServingGrams ?: c.defaultGrams),
    "per_serving" to c.perServing, "per_100g" to c.per100g,
    "suspect" to NutrientSanity.codes(c.perServing ?: c.per100g.orEmpty(), if (c.perServing != null) c.labelServingGrams else 100.0),
)
