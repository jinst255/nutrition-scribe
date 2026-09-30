package com.nutritionscribe.wear.tile

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.CircularProgressIndicator
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nutritionscribe.wear.data.WearRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val RESOURCES_VERSION = "1"

/**
 * Tile using the graphicDataCard pattern (Section 9): a tinted rounded card containing
 * a small circular progress ring, with the "Protein 118g of 160g" label inside the
 * card rather than floating above it. Built from LayoutElementBuilders directly so it
 * has no dependency on a specific ProtoLayout material-library version.
 */
class ProteinTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val summary = runBlocking { WearRepository(applicationContext).todaySummary.first() }
        val progress = (summary.proteinGrams / summary.goalGrams).toFloat().coerceIn(0f, 1f)

        val card = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF2F6F4F.toInt()))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build())
                            .build(),
                    )
                    .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(12f)).build())
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        CircularProgressIndicator.Builder()
                            .setProgress(progress)
                            .build(),
                    )
                    .addContent(
                        Text.Builder(this, "Protein ${summary.proteinGrams.toInt()}g of ${summary.goalGrams.toInt()}g")
                            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                            .setColor(argb(0xFFFAFAF7.toInt()))
                            .build(),
                    )
                    .build(),
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(card).build())
                            .build(),
                    )
                    .build(),
            )
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
}
