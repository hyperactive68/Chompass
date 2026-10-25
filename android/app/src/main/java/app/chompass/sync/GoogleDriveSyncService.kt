package app.chompass.sync

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/**
 * Google Drive app-data backup using Google's Android AuthorizationClient.
 *
 * The service never stores a Google token in DataStore or in the backup JSON.
 * Google Play services keeps the authorization grant; the short-lived access
 * token is retained only in memory for this process. Drive appDataFolder is
 * private to this app and is not visible in the user's normal Drive folders.
 */
class GoogleDriveSyncService(
    private val context: Context,
    private val syncRepository: SyncRepository,
    private val prefs: app.chompass.data.PreferencesStore,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncMutex = Mutex()

    @Volatile
    private var accessToken: String? = null

    fun authorizationRequest(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
        .build()

    /** Runs the Google account/consent flow; resolution is returned to the UI. */
    suspend fun requestAuthorization(): AuthorizationResult = withContext(Dispatchers.IO) {
        Tasks.await(Identity.getAuthorizationClient(context).authorize(authorizationRequest()))
    }

    fun acceptAuthorization(result: AuthorizationResult): Boolean {
        val token = result.accessToken?.takeIf { it.isNotBlank() } ?: return false
        accessToken = token
        return true
    }

    /** Coalesced background sync used after a food log or edit. */
    fun scheduleSyncAfterFoodChange() {
        scope.launch {
            if (!prefs.googleDriveSyncOnFoodLog.firstOrFalse()) return@launch
            syncMutex.withLock {
                runCatching { syncIfAlreadyAuthorized() }
            }
        }
    }

    suspend fun syncNow(): SyncRepository.SyncResult = syncMutex.withLock {
        val token = accessToken ?: return@withLock SyncRepository.SyncResult.Failed(
            context.getString(app.chompass.R.string.settings_google_drive_not_connected),
        )
        uploadAndMerge(token)
    }

    suspend fun syncIfAlreadyAuthorized(): SyncRepository.SyncResult? {
        if (!prefs.googleDriveConnected.firstOrFalse()) return null
        val existing = accessToken
        if (existing != null) return uploadAndMerge(existing)
        val result = runCatching { requestAuthorization() }.getOrNull() ?: return null
        if (result.hasResolution()) return null
        val token = result.accessToken?.takeIf { it.isNotBlank() } ?: return null
        accessToken = token
        return uploadAndMerge(token)
    }

    private suspend fun uploadAndMerge(token: String): SyncRepository.SyncResult = withContext(Dispatchers.IO) {
        try {
            val remote = findBackupFile(token)
            if (remote != null) {
                val remoteJson = download(token, remote)
                if (remoteJson.isNotBlank()) {
                    when (val merged = syncRepository.importDocumentJson(remoteJson)) {
                        is SyncRepository.SyncResult.Failed -> return@withContext merged
                        is SyncRepository.SyncResult.Success -> Unit
                    }
                }
            }
            val localJson = syncRepository.exportDocumentJson()
            if (remote == null) create(token, localJson) else update(token, remote, localJson)
            prefs.setGoogleDriveLastSyncAt(Instant.now().toString())
            SyncRepository.SyncResult.Success(context.getString(app.chompass.R.string.settings_google_drive_sync_success))
        } catch (t: Throwable) {
            SyncRepository.SyncResult.Failed(
                context.getString(
                    app.chompass.R.string.settings_google_drive_sync_failed,
                    t.message ?: context.getString(app.chompass.R.string.error_unknown),
                ),
            )
        }
    }

    private fun findBackupFile(token: String): String? {
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("pageSize", "1")
            .addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("q", "'appDataFolder' in parents and name = '$FILE_NAME' and trashed = false")
            .build()
        val body = execute(Request.Builder().url(url).get().bearer(token).build())
        val files = JSONObject(body).optJSONArray("files") ?: return null
        return files.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
    }

    private fun download(token: String, fileId: String): String {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .build()
        return execute(Request.Builder().url(url).get().bearer(token).build())
    }

    private fun create(token: String, json: String) {
        val metadata = JSONObject()
            .put("name", FILE_NAME)
            .put("parents", org.json.JSONArray().put("appDataFolder"))
            .put("mimeType", "application/json")
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody(JSON_MEDIA))
            .addPart(json.toRequestBody(JSON_MEDIA))
            .build()
        val url = "https://www.googleapis.com/upload/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id")
            .build()
        execute(Request.Builder().url(url).post(multipart).bearer(token).build())
    }

    private fun update(token: String, fileId: String, json: String) {
        val url = "https://www.googleapis.com/upload/drive/v3/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "media")
            .build()
        execute(Request.Builder().url(url).patch(json.toRequestBody(JSON_MEDIA)).bearer(token).build())
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Google Drive HTTP ${response.code}: ${text.take(200)}")
            return text
        }
    }

    private fun Request.Builder.bearer(token: String): Request.Builder =
        header("Authorization", "Bearer $token")

    private suspend fun kotlinx.coroutines.flow.Flow<Boolean>.firstOrFalse(): Boolean = first()

    private companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val FILE_NAME = "Chompass-sync.json"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
