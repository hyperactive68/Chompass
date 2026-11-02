package app.chompass.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * True when a WebDAV URL is set, so touch/tombstone should record revisions.
 * Auto-sync is a separate toggle: Sync Now still runs without it, and deletes
 * must tombstone or the next pull re-injects the row (#39).
 */
fun shouldTrackSyncRevisions(webDavUrl: String): Boolean =
    normalizeWebDavUrl(webDavUrl).isNotEmpty()

/**
 * Revision metadata is shared by WebDAV and Google Drive sync. Drive is
 * selected through OAuth and therefore has no WebDAV URL to identify it.
 */
fun shouldTrackSyncRevisions(webDavUrl: String, googleDriveConnected: Boolean): Boolean =
    googleDriveConnected || shouldTrackSyncRevisions(webDavUrl)

fun shouldAutoSyncWebDav(
    enabled: Boolean,
    configured: Boolean,
    today: LocalDate,
    lastRunAtIso: String?,
    lastAutoRunDayIso: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    if (!enabled || !configured) return false
    if (lastAutoRunDayIso == today.toString()) return false
    val lastRunDay = lastRunAtIso
        ?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }
    if (lastRunDay == today) return false
    return true
}

/** Compatibility name used by the 6.1 local-backup cadence. */
fun shouldAutoRunOnOpen(
    enabled: Boolean,
    configured: Boolean,
    today: LocalDate,
    lastRunAtIso: String?,
    lastAutoRunDayIso: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    if (!enabled || !configured) return false
    if (lastAutoRunDayIso == today.toString()) return false
    val lastRunDay = lastRunAtIso
        ?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() }
    return lastRunDay != today
}
