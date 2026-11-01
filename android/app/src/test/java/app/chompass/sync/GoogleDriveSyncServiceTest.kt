package app.chompass.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveSyncServiceTest {
    @Test
    fun requiredAppDataScopeMustBeGranted() {
        assertTrue(
            GoogleDriveSyncService.hasRequiredScope(
                listOf(GoogleDriveSyncService.DRIVE_APPDATA_SCOPE),
            ),
        )
    }

    @Test
    fun unrelatedOrPartialScopesAreRejected() {
        assertFalse(GoogleDriveSyncService.hasRequiredScope(emptyList()))
        assertFalse(
            GoogleDriveSyncService.hasRequiredScope(
                listOf("https://www.googleapis.com/auth/drive.file"),
            ),
        )
    }
}
