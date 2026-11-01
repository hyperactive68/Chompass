package app.chompass.data

import app.chompass.models.AIProvider
import app.chompass.models.gapReport
import app.chompass.services.ai.toFoodEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortPolicyTest {
    @Test
    fun chatGptGpt56_exposesAllResponsesEfforts() {
        val values = OpenRouterReasoningEffort.availableFor(AIProvider.CHATGPT, "gpt-5.6-sol")
        assertEquals(
            listOf(
                OpenRouterReasoningEffort.AUTO,
                OpenRouterReasoningEffort.LOW,
                OpenRouterReasoningEffort.MEDIUM,
                OpenRouterReasoningEffort.HIGH,
                OpenRouterReasoningEffort.XHIGH,
                OpenRouterReasoningEffort.MAX,
            ),
            values,
        )
    }

    @Test
    fun unknownChatGptModel_staysAutoOnly() {
        val values = OpenRouterReasoningEffort.availableFor(AIProvider.CHATGPT, "gpt-4o")
        assertEquals(listOf(OpenRouterReasoningEffort.AUTO), values)
        assertEquals(
            OpenRouterReasoningEffort.AUTO,
            OpenRouterReasoningEffort.sanitizeFor(AIProvider.CHATGPT, "gpt-4o", OpenRouterReasoningEffort.MAX),
        )
    }

    @Test
    fun explicitZeroAnalysisFields_areMarkedAnswered() {
        val analysis = app.chompass.services.ai.FoodAnalysis(
            name = "Cola",
            calories = 139,
            protein = 0.0,
            carbs = 35.0,
            fat = 0.0,
            servingSizeGrams = 330.0,
            fiber = 0.0,
        )
        val entry = analysis.toFoodEntry(app.chompass.models.FoodSource.TEXT_INPUT, "other")
        assertTrue("protein" in entry.gapAnsweredFields)
        assertTrue("fat" in entry.gapAnsweredFields)
        assertTrue("fiber" in entry.gapAnsweredFields)
        assertFalse(entry.gapReport().missingMacros.contains(app.chompass.models.GapMacroField.PROTEIN))
        assertFalse(entry.gapReport().missingMicros.contains(app.chompass.models.MicronutrientField.FIBER))
    }
}
