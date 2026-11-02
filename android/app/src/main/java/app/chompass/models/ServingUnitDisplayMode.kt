package app.chompass.models

import app.chompass.R

/**
 * Controls only how serving amounts are shown in the UI. The AI/parser keeps
 * the original serving unit and the stored nutrition math is unchanged.
 */
enum class ServingUnitDisplayMode(
    val storageKey: String,
    val displayNameRes: Int,
    val subtitleRes: Int,
) {
    AI_UNITS(
        "aiUnits",
        R.string.serving_unit_display_mode_ai_units,
        R.string.serving_unit_display_mode_ai_units_subtitle,
    ),
    METRIC(
        "metric",
        R.string.serving_unit_display_mode_metric,
        R.string.serving_unit_display_mode_metric_subtitle,
    );

    companion object {
        val Default = AI_UNITS

        fun fromStorage(raw: String?): ServingUnitDisplayMode =
            entries.firstOrNull { it.storageKey == raw } ?: Default
    }
}

/** Metric presentation for a serving; it does not change the stored option. */
data class MetricServingAmount(
    val value: Double,
    val unit: String,
)

private val LIQUID_DISPLAY_KEYWORDS = setOf(
    "milk", "melk", "juice", "sap", "smoothie", "soup", "soep", "broth", "bouillon",
    "coffee", "koffie", "tea", "thee", "latte", "cappuccino", "yogurt", "yoghurt",
    "water", "soda", "cola", "beer", "bier", "wine", "wijn", "drink", "drinks", "drank",
    "beverage", "beverages", "suppe", "soupe", "sopa", "caldo",
)

private fun isRecognizedLiquidFood(foodName: String): Boolean {
    val normalized = foodName.lowercase(java.util.Locale.ROOT)
    val words = normalized.split(Regex("[^\\p{L}\\p{Nd}]+"))
    return words.any { word ->
        word in LIQUID_DISPLAY_KEYWORDS ||
            word.endsWith("soep") ||
            word.endsWith("soup") ||
            word.endsWith("bouillon")
    }
}

fun metricServingAmount(
    option: ServingUnitOption,
    quantity: Double?,
    totalGrams: Double,
    foodName: String? = null,
): MetricServingAmount {
    val positiveQuantity = quantity?.takeIf { it > 0 }
    val liquidRule = foodName
        ?.takeIf { it.isNotBlank() }
        ?.let { ServingUnitHeuristics.matchingRule(it) }
        ?.takeIf { it.unit.equals("ml", ignoreCase = true) }
    val isLiquid = liquidRule != null || foodName?.let(::isRecognizedLiquidFood) == true
    if (isLiquid && option.normalizedUnit !in setOf("ml", "l")) {
        // A soup/drink may be stored as "serving", "bowl" or "cup" because
        // the parser unit is intentionally left untouched. For display only,
        // use an approximate 1 g ≈ 1 ml conversion; nutrition math remains
        // weight-based and the stored/parser unit is unchanged.
        return MetricServingAmount(totalGrams, "ml")
    }
    return when (option.normalizedUnit) {
        "ml" -> positiveQuantity?.let { MetricServingAmount(it, "ml") }
        "l" -> positiveQuantity?.let { MetricServingAmount(it * 1000.0, "ml") }
        else -> MetricServingAmount(totalGrams, "g")
    } ?: MetricServingAmount(totalGrams, "g")
}
