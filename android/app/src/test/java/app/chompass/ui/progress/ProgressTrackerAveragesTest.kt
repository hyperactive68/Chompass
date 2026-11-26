package app.chompass.ui.progress

import app.chompass.models.CaffeineEntry
import app.chompass.models.FoodEntry
import app.chompass.models.FoodSource
import app.chompass.models.WaterEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ProgressTrackerAveragesTest {
    private val today = LocalDate.of(2026, 11, 21)

    @Test
    fun onlyEnabledTrackersAppear() {
        val ui = buildProgressPreviewUiState(
            profile = null,
            weights = emptyList(),
            bodyFatEntries = emptyList(),
            foods = emptyList(),
            timeRange = TimeRange.WEEK,
            anchorDate = today,
            waterTrackingEnabled = true,
            waterEntries = listOf(water(today.minusDays(1), 250)),
        )

        assertEquals(listOf(TrackedItemType.WATER), ui.trackerAverages.map { it.type })
        assertEquals(250.0, ui.trackerAverages.single().average, 0.001)
    }

    @Test
    fun waterAverageUsesLoggedDaysInsideRangeAndSkipsUntrackedDays() {
        val ui = buildProgressPreviewUiState(
            profile = null,
            weights = emptyList(),
            bodyFatEntries = emptyList(),
            foods = emptyList(),
            timeRange = TimeRange.WEEK,
            anchorDate = today,
            waterTrackingEnabled = true,
            waterEntries = listOf(
                water(today.minusDays(8), 1_000),
                water(today.minusDays(2), 250),
                water(today.minusDays(2), 250),
                water(today.minusDays(1), 900),
                water(today, 250),
            ),
            untrackedDays = setOf(today.minusDays(1).toString()),
        )

        val average = ui.trackerAverages.single()
        assertEquals(TrackedItemType.WATER, average.type)
        assertEquals(375.0, average.average, 0.001)
        assertEquals(2, average.loggedDays)
    }

    @Test
    fun caffeineAverageCombinesTrackerLogsAndFoodCaffeine() {
        val ui = buildProgressPreviewUiState(
            profile = null,
            weights = emptyList(),
            bodyFatEntries = emptyList(),
            foods = listOf(
                food(today.minusDays(2), 100.0),
                food(today.minusDays(1), 200.0),
                food(today.minusDays(8), 500.0),
            ),
            timeRange = TimeRange.WEEK,
            anchorDate = today,
            caffeineTrackingEnabled = true,
            caffeineEntries = listOf(CaffeineEntry(date = instant(today.minusDays(2)), mg = 50.0)),
        )

        val average = ui.trackerAverages.single()
        assertEquals(TrackedItemType.CAFFEINE, average.type)
        assertEquals(175.0, average.average, 0.001)
        assertEquals(2, average.loggedDays)
    }

    @Test
    fun enabledTrackerWithoutLogsReportsZeroLoggedDays() {
        val ui = buildProgressPreviewUiState(
            profile = null,
            weights = emptyList(),
            bodyFatEntries = emptyList(),
            foods = emptyList(),
            timeRange = TimeRange.WEEK,
            anchorDate = today,
            nicotineTrackingEnabled = true,
        )

        val average = ui.trackerAverages.single()
        assertEquals(TrackedItemType.NICOTINE, average.type)
        assertEquals(0.0, average.average, 0.001)
        assertEquals(0, average.loggedDays)
    }

    @Test
    fun historyKeepsTrackerAvailableAndHiddenSettingRemovesRows() {
        val ui = buildProgressPreviewUiState(
            profile = null,
            weights = emptyList(),
            bodyFatEntries = emptyList(),
            foods = emptyList(),
            timeRange = TimeRange.WEEK,
            anchorDate = today,
            waterEntries = listOf(water(today.minusDays(1), 250)),
            nicotineTrackingEnabled = true,
            hiddenTrackedItems = setOf(TrackedItemType.NICOTINE.storageId),
        )

        assertEquals(listOf(TrackedItemType.WATER), ui.trackerAverages.map { it.type })
    }

    private fun water(day: LocalDate, milliliters: Int) =
        WaterEntry(date = instant(day), milliliters = milliliters)

    private fun food(day: LocalDate, caffeine: Double) = FoodEntry(
        name = "Coffee food",
        calories = 50,
        protein = 0.0,
        carbs = 0.0,
        fat = 0.0,
        source = FoodSource.MANUAL,
        caffeine = caffeine,
        timestamp = instant(day),
    )

    private fun instant(day: LocalDate) =
        day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
}
