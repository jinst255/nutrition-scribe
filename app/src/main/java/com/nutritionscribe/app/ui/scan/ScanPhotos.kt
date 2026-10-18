package com.nutritionscribe.app.ui.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nutritionscribe.app.di.ServiceLocator
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The photos of labels people scanned, kept with the food so My labels can show
 * the label a number came from. They stay on the phone, in the app's own files,
 * and are never part of the debug log or a backup.
 */
object ScanPhotos {
    /** A scan used but not logged yet: its candidate id and photo, until the entry is saved. */
    private val pending = ConcurrentHashMap<String, String>()

    private fun dir(): File = File(ServiceLocator.appContext.filesDir, "labels").apply { mkdirs() }

    fun file(name: String): File = File(dir(), name)

    /** Saved small (the long side at most 1280 px): enough to read a label by, not a camera's worth. */
    fun save(bitmap: Bitmap): String? = runCatching {
        val long = maxOf(bitmap.width, bitmap.height)
        val scale = if (long > 1280) 1280f / long else 1f
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val name = "label-${UUID.randomUUID()}.jpg"
        file(name).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        name
    }.getOrNull()

    fun load(name: String?): Bitmap? = name?.let { n -> runCatching { BitmapFactory.decodeFile(file(n).path) }.getOrNull() }

    fun remember(candidateId: String, photo: String) { pending[candidateId] = photo }

    /** The photo for a candidate that is being logged now, once. */
    fun take(candidateId: String?): String? = candidateId?.let { pending.remove(it) }

    /** A food forgotten, or its photo replaced. */
    fun delete(name: String?) { name?.let { runCatching { file(it).delete() } } }
}
