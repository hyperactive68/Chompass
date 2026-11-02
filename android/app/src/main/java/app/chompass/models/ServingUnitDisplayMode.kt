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

fun metricServingAmount(
    option: ServingUnitOption,
    quantity: Double?,
    totalGrams: Double,
): MetricServingAmount {
    val positiveQuantity = quantity?.takeIf { it > 0 }
    return when (option.normalizedUnit) {
        "ml" -> positiveQuantity?.let { MetricServingAmount(it, "ml") }
        "l" -> positiveQuantity?.let { MetricServingAmount(it * 1000.0, "ml") }
        else -> MetricServingAmount(totalGrams, "g")
    } ?: MetricServingAmount(totalGrams, "g")
}
