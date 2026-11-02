package app.chompass.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ServingUnitDisplayModeTest {
    @Test
    fun missingOrUnknownStorageDefaultsToAiUnits() {
        assertEquals(ServingUnitDisplayMode.AI_UNITS, ServingUnitDisplayMode.fromStorage(null))
        assertEquals(ServingUnitDisplayMode.AI_UNITS, ServingUnitDisplayMode.fromStorage("old-value"))
    }

    @Test
    fun metricPresentationDoesNotChangeStoredOption() {
        val cup = ServingUnitOption(unit = "cup", gramsPerUnit = 240.0)
        val display = metricServingAmount(cup, quantity = 1.0, totalGrams = 240.0)
        assertEquals(240.0, display.value, 0.0)
        assertEquals("g", display.unit)
        assertEquals("cup", cup.unit)
    }

    @Test
    fun explicitLitersDisplayAsMilliliters() {
        val liter = ServingUnitOption(unit = "l", gramsPerUnit = 1000.0)
        val display = metricServingAmount(liter, quantity = 0.5, totalGrams = 500.0)
        assertEquals(500.0, display.value, 0.0)
        assertEquals("ml", display.unit)
        assertEquals("l", liter.unit)
    }
}
