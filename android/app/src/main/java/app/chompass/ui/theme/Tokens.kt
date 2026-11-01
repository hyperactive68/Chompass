package app.chompass.ui.theme

import androidx.compose.ui.unit.dp

/** Compatibility tokens retained for fork screens that predate the v6 token consolidation. */
object AppRadii {
    val SectionCard = 22.dp
    val Container = 18.dp
    val Field = 14.dp
    val Sheet = 28.dp
    val PillCard = 24.dp
    val Pill = 20.dp
    val Card = 16.dp
    val Tile = 12.dp
    val Track = 10.dp
    val Chip = 8.dp
}

/** Compatibility text-opacity tokens retained for fork screens. */
object AppTextOpacity {
    const val Muted = 0.6f
    const val Faint = 0.45f
    const val Disabled = 0.4f
    const val Subtle = 0.62f
    const val Secondary = 0.7f
    const val Emphasized = 0.8f
    const val Strong = 0.85f
    const val NearFull = 0.94f
}

/**
 * Vertical/horizontal spacing steps. Values mirror the app's iOS-verbatim
 * layouts (see "Deliberate choices" in `docs/local/UI_AUDIT_PLAN.md`); the PWA
 * mirrors the same steps as `--space-*` custom properties in `web/app/css/main.css`.
 */
object AppSpacing {
    /** 4.dp — icon-to-label gaps, tight intra-row spacing. */
    val Xs = 4.dp

    /** 8.dp — intra-group gaps, chip padding. */
    val Sm = 8.dp

    /** 12.dp — card-internal padding. */
    val Md = 12.dp

    /** 16.dp — screen gutters, section padding. */
    val Lg = 16.dp

    /** 20.dp — sheet gutters, hero padding. */
    val Xl = 20.dp

    /** 24.dp — section separation, large block gaps. */
    val Xxl = 24.dp
}
