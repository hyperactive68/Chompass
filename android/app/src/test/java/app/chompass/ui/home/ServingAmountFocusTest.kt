package app.chompass.ui.home

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.chompass.models.ServingUnitOption
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Codeberg #159: direct-add opens the quantity card with the amount ready to type. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServingAmountFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun focusAmountOnOpen_expandsAndFocusesTheAmount() {
        composeRule.setContent { servingCard(focusAmountOnOpen = true) }

        composeRule.waitForIdle()
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).assertExists()
    }

    @Test
    fun normalOpen_keepsTheCollapsedCardAndDoesNotStealFocus() {
        composeRule.setContent { servingCard(focusAmountOnOpen = false) }

        composeRule.waitForIdle()
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).assertDoesNotExist()
    }

    @Composable
    private fun servingCard(focusAmountOnOpen: Boolean) {
        ServingQuantityCard(
            quantityText = "2",
            onQuantityChange = {},
            selectedUnitId = "g",
            onSelectedUnitChange = {},
            servingSizeGrams = 200.0,
            unitOptions = listOf(ServingUnitOption.grams),
            menuExpanded = false,
            onMenuExpandedChange = {},
            gramUnit = "g",
            focusAmountOnOpen = focusAmountOnOpen,
        )
    }
}
