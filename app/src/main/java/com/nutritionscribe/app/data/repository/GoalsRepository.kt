package com.nutritionscribe.app.data.repository

import com.nutritionscribe.app.data.db.MetricGoalDao
import com.nutritionscribe.app.data.db.MetricGoalRow
import com.nutritionscribe.shared.model.GoalDirection
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun MetricGoalRow.toModel(): MetricGoal? {
    val m = Metric.fromKey(metric) ?: return null  // a metric removed in a later version
    return MetricGoal(
        metric = m,
        enabled = enabled,
        target = target,
        floorValue = floorValue,
        ceilingValue = ceilingValue,
        sortOrder = sortOrder,
        pinned = pinned,
        direction = direction?.let { runCatching { GoalDirection.valueOf(it) }.getOrNull() },
    )
}

fun MetricGoal.toRow(): MetricGoalRow = MetricGoalRow(
    metric = metric.key,
    enabled = enabled,
    target = target,
    floorValue = floorValue,
    ceilingValue = ceilingValue,
    sortOrder = sortOrder,
    pinned = pinned,
    direction = direction?.name,
)

/**
 * Which metrics the user tracks, and what they're aiming for.
 *
 * A row exists for every [Metric], enabled or not, so that switching one off and
 * back on restores the number that was set instead of forgetting it. That means
 * reads can always assume nine rows once [ensureSeeded] has run.
 */
class GoalsRepository(
    private val dao: MetricGoalDao,
    private val settings: SettingsRepository,
) {

    val goals: Flow<List<MetricGoal>> = dao.observeAll().map { rows ->
        rows.mapNotNull { it.toModel() }.sortedBy { it.sortOrder }
    }

    val enabledGoals: Flow<List<MetricGoal>> = dao.observeEnabled().map { rows ->
        rows.mapNotNull { it.toModel() }.sortedBy { it.sortOrder }
    }

    suspend fun find(metric: Metric): MetricGoal? = dao.find(metric.key)?.toModel()

    /**
     * Creates the nine rows on first run, carrying across the protein goal and
     * baseline someone already set before this screen existed.
     *
     * The Room migration could not do this: those two numbers live in DataStore,
     * which a SQL migration cannot read. So it happens here, once, on launch.
     * Without it an existing user silently loses the goal they had been using.
     */
    suspend fun ensureSeeded(legacyGoalGrams: Double?, legacyBaselineGrams: Double?) {
        if (dao.count() > 0) return

        val rows = Metric.entries.map { metric ->
            when (metric) {
                Metric.PROTEIN -> MetricGoal.default(metric, enabled = true).copy(
                    target = legacyGoalGrams ?: metric.defaultTarget,
                    floorValue = legacyBaselineGrams,
                )
                // Calories comes on by default because protein alone describes
                // very little about a day; see the nudge in the Goals screen.
                Metric.CALORIES -> MetricGoal.default(metric, enabled = true)
                else -> MetricGoal.default(metric)
            }
        }
        dao.upsertAll(rows.map { it.toRow() })
    }

    suspend fun setEnabled(metric: Metric, enabled: Boolean) {
        val existing = find(metric) ?: MetricGoal.default(metric)
        dao.upsert(existing.copy(enabled = enabled).toRow())
    }

    suspend fun setTarget(metric: Metric, target: Double?) {
        val existing = find(metric) ?: MetricGoal.default(metric)
        dao.upsert(
            existing.copy(
                target = target,
                // A LIMIT metric's target IS its ceiling; keeping them in step
                // stops the chart colouring against a stale number.
                ceilingValue = if (existing.effectiveDirection == GoalDirection.LIMIT) {
                    target
                } else {
                    existing.ceilingValue
                },
            ).toRow(),
        )
        mirrorProteinToLegacySettings(metric, target = target)
    }

    /**
     * Flips a goal between "reach this" and "stay under this".
     *
     * The ceiling has to move with it: a LIMIT goal colours against its ceiling,
     * so switching to LIMIT without setting one leaves the day scored against
     * nothing, and switching away without clearing it leaves a stale red line.
     */
    suspend fun setDirection(metric: Metric, direction: GoalDirection) {
        val existing = find(metric) ?: MetricGoal.default(metric)
        val override = if (direction == metric.direction) null else direction
        dao.upsert(
            existing.copy(
                direction = override,
                ceilingValue = if (direction == GoalDirection.LIMIT) existing.target else null,
            ).toRow(),
        )
    }

    suspend fun setFloor(metric: Metric, floor: Double?) {
        val existing = find(metric) ?: MetricGoal.default(metric)
        dao.upsert(existing.copy(floorValue = floor).toRow())
        mirrorProteinToLegacySettings(metric, floor = floor)
    }

    /**
     * Keeps the protein goal in step with the DataStore copy.
     *
     * The home ring, the History chart, the widget and the watch all still read
     * `dailyGoalGrams` / `dailyBaselineGrams` rather than this table. Until those
     * screens are reworked to read goals directly, writing only here would mean
     * editing your protein target changed the Goals tab and nothing else — and
     * since the old fields have been removed from Settings, there would be no way
     * left to change the number those screens actually use.
     *
     * Delete this once every consumer reads [goals]; it is a bridge, not a design.
     */
    private suspend fun mirrorProteinToLegacySettings(
        metric: Metric,
        target: Double? = null,
        floor: Double? = null,
    ) {
        if (metric != Metric.PROTEIN) return
        target?.let { settings.setDailyGoalGrams(it) }
        floor?.let { settings.setDailyBaselineGrams(it) }
    }

    /**
     * Applies a whole selection at once, used by the setup screen.
     * Rejects an empty selection rather than writing it: an app that tracks
     * nothing has no screens worth showing.
     */
    suspend fun applySelection(enabled: Set<Metric>): Boolean {
        if (enabled.isEmpty()) return false
        Metric.entries.forEach { metric ->
            val existing = find(metric) ?: MetricGoal.default(metric)
            dao.upsert(existing.copy(enabled = metric in enabled).toRow())
        }
        return true
    }
}
