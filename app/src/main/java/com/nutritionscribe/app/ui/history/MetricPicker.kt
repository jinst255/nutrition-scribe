package com.nutritionscribe.app.ui.history

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import kotlinx.coroutines.launch

/**
 * What to plot: the same two pages as Home — Macros, then Minerals & vitamins —
 * swiped between, each a row of chips with the user's goals first.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MetricPicker(
    selected: Metric,
    goals: List<MetricGoal>,
    onSelect: (Metric) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(initialPage = if (selected.isMicro) 1 else 0) { 2 }
    val scope = rememberCoroutineScope()
    val enabled = goals.filter { it.enabled }.sortedBy { it.sortOrder }.map { it.metric }
    fun pageMetrics(page: Int): List<Metric> {
        val pool = com.nutritionscribe.shared.model.Extras.shown(if (page == 0) Metric.MACROS else Metric.MICROS)
        return enabled.filter { it in pool } + pool.filter { it !in enabled }
    }
    // Swiping to the other page picks from it, so the chart follows the page.
    LaunchedEffect(pager.settledPage) {
        val onPage = pageMetrics(pager.settledPage)
        if (selected !in onPage) onSelect(onPage.first())
    }
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf("Macros", "Minerals & vitamins").forEachIndexed { i, label ->
                val on = pager.currentPage == i
                Column(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }
                        .padding(top = 4.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.secondary,
                    )
                    Box(Modifier.padding(top = 8.dp).height(2.dp).width(if (on) 36.dp else 0.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().animateContentSize(), verticalAlignment = Alignment.Top) { page ->
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pageMetrics(page).forEach { m ->
                    val on = m == selected
                    val isGoal = m in enabled
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).clickable { onSelect(m) },
                    ) {
                        Box(Modifier.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
                            Text(
                                m.shortLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isGoal) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    on -> MaterialTheme.colorScheme.onPrimaryContainer
                                    isGoal -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
