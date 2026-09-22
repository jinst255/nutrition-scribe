package com.nutritionscribe.app.data.repository

import com.nutritionscribe.app.data.db.MetricGoalDao
import com.nutritionscribe.app.data.db.MetricGoalRow
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
                ceilingValue = if (metric.direction == com.nutritionscribe.shared.model.GoalDirection.LIMIT) {
                    target
                } else {
                    existing.ceilingValue
                },
            ).toRow(),
        )
    }

    suspend fun setFloor(metric: Metric, floor: Double?) {
        val existing = find(metric) ?: MetricGoal.default(metric)
        dao.upsert(existing.copy(floorValue = floor).toRow())
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
