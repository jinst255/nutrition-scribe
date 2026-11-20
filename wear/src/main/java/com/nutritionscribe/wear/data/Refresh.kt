package com.nutritionscribe.wear.data

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/** New numbers from the phone: ask the tile and the watch-face slots to redraw. */
object Refresh {
    fun all(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(com.nutritionscribe.wear.tile.ProteinTileService::class.java) }
        for (c in listOf(com.nutritionscribe.wear.complication.ProteinComplicationService::class.java,
            com.nutritionscribe.wear.complication.SayComplicationService::class.java)) {
            runCatching { ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, c)).requestUpdateAll() }
        }
    }
}
