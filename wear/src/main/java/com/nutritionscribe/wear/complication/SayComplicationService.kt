package com.nutritionscribe.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.nutritionscribe.wear.R
import com.nutritionscribe.wear.ui.WearMainActivity

/** "Say" in a watch-face slot: one tap and it's listening (watch design, approved 11-19). */
class SayComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        listener.onComplicationData(build(request.complicationType, say()))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, null)

    private fun say() = PendingIntent.getActivity(
        this, 1, Intent(this, WearMainActivity::class.java).putExtra(WearMainActivity.EXTRA_SAY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(type: ComplicationType, tap: PendingIntent?): ComplicationData? {
        val image = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_mic)).build()
        val describe = PlainComplicationText.Builder("Say what you ate").build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder("Say").build(), describe)
                .setMonochromaticImage(image).setTapAction(tap).build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(image, describe).setTapAction(tap).build()
            else -> null
        }
    }
}
