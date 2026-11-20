package com.nutritionscribe.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nutritionscribe.shared.sync.WatchState
import com.nutritionscribe.wear.complication.Slots
import com.nutritionscribe.wear.data.WearRepository
import com.nutritionscribe.wear.ui.WearMainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val RESOURCES_VERSION = "2"
private const val GREEN = 0xFF4E9873.toInt()
private const val BLUE = 0xFF6D9BD1.toInt()
private const val TRACK = 0xFF2C302D.toInt()
private const val INK = 0xFFF2F1EC.toInt()
private const val DIM = 0xFFA8A79F.toInt()

/**
 * The tile (watch design, approved 11-19): the main goal as a big number with a
 * straight bar (no ring around the edge), the next goal under it, and a Say
 * button that opens straight into listening.
 */
class ProteinTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val state = runBlocking { WearRepository(applicationContext).state.first() }
        val main = Slots.main(state)
        val second = state.goals.firstOrNull { it != main }

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("Nutrition Scribe", 12f, GREEN, bold = true))
            .addContent(space(4f))
        if (main != null) {
            column.addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
                    .addContent(text("%,d".format(Math.round(main.value)), 28f, INK, bold = false))
                    .addContent(text("  of %,d %s %s".format(Math.round(main.target), main.unit, main.name.lowercase()), 11f, DIM))
                    .build(),
            ).addContent(space(4f)).addContent(bar(main, 140f, 6f))
            second?.let { g ->
                column.addContent(space(8f))
                    .addContent(text("${g.name}  %,d / %s%,d".format(Math.round(g.value), if (g.limit) "≤" else "", Math.round(g.target)), 11f, DIM))
                    .addContent(space(3f)).addContent(bar(g, 140f, 4f))
            }
        } else {
            column.addContent(text("Open the phone app once", 11f, DIM))
        }
        column.addContent(space(10f)).addContent(sayButton())

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(15 * 60_000L)
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder().addTimelineEntry(
                    TimelineBuilders.TimelineEntry.Builder()
                        .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(column.build()).build()).build(),
                ).build(),
            )
            .build()
        return Futures.immediateFuture(tile)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder().setText(s).setFontStyle(
            LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build(),
        ).build()

    private fun space(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

    /** A short straight bar: blue to reach a goal, green to stay under. */
    private fun bar(g: WatchState.Goal, width: Float, height: Float): LayoutElementBuilders.LayoutElement {
        val fill = (width * g.fraction).coerceAtLeast(height)
        return LayoutElementBuilders.Box.Builder().setWidth(dp(width)).setHeight(dp(height))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .setModifiers(round(TRACK, height / 2))
            .addContent(LayoutElementBuilders.Box.Builder().setWidth(dp(fill)).setHeight(dp(height))
                .setModifiers(round(if (g.limit) GREEN else BLUE, height / 2)).build())
            .build()
    }

    private fun round(color: Int, r: Float) = ModifiersBuilders.Modifiers.Builder().setBackground(
        ModifiersBuilders.Background.Builder().setColor(argb(color))
            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(r)).build()).build(),
    ).build()

    private fun sayButton(): LayoutElementBuilders.LayoutElement {
        val launch = ActionBuilders.LaunchAction.Builder().setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder()
                .setPackageName(packageName)
                .setClassName(WearMainActivity::class.java.name)
                .addKeyToExtraMapping(WearMainActivity.EXTRA_SAY, ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build())
                .build(),
        ).build()
        val clickable = ModifiersBuilders.Clickable.Builder().setId("say").setOnClick(launch).build()
        return LayoutElementBuilders.Box.Builder().setWidth(dp(120f)).setHeight(dp(42f))
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(clickable)
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(GREEN))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(21f)).build()).build())
                .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription("Say what you ate").build())
                .build())
            .addContent(text("Say", 15f, 0xFF0F1110.toInt(), bold = true))
            .build()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
}
