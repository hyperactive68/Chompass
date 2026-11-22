package app.chompass.ui.progress

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chompass.R
import app.chompass.models.LocaleFormat
import app.chompass.models.WaterAmountFormat
import kotlin.math.roundToInt

@Composable
internal fun TrackedItemsSection(
    averages: List<TrackedItemAverage>,
    useMetricWater: Boolean,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.progressCollapseHeader(expanded) { expanded = !expanded },
        ) {
            Text(
                stringResource(R.string.progress_trackers_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            CollapseChevron(expanded)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.progress_trackers_subtitle),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatBadgeRow(
                    averages.map { row ->
                        stringResource(row.type.labelRes) to trackerAverageLabel(row, useMetricWater)
                    }
                )
            }
        }
    }
}

@Composable
private fun trackerAverageLabel(row: TrackedItemAverage, useMetricWater: Boolean): String {
    if (row.loggedDays == 0) return "\u2014"
    val amount = row.average.roundToInt()
    return when (row.type) {
        TrackedItemType.WATER -> if (useMetricWater) {
            "${LocaleFormat.integer(amount)} ${stringResource(R.string.unit_ml)}"
        } else {
            "${LocaleFormat.integer(WaterAmountFormat.flOzFromMl(amount))} ${stringResource(R.string.unit_fl_oz)}"
        }
        TrackedItemType.NICOTINE -> LocaleFormat.integer(amount)
        TrackedItemType.CAFFEINE -> "${LocaleFormat.integer(amount)} ${stringResource(R.string.unit_mg)}"
    }
}

private val TrackedItemType.labelRes: Int
    get() = when (this) {
        TrackedItemType.WATER -> R.string.water
        TrackedItemType.NICOTINE -> R.string.nicotine
        TrackedItemType.CAFFEINE -> R.string.caffeine
    }
