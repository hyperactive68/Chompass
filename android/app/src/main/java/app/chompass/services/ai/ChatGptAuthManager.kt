package app.chompass.services.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.chompass.data.KeyStore
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI's public-client Sign in with ChatGPT flow for locally running OSS apps.
 * Credentials never leave encrypted app storage and are never exposed to the UI.
 */
class ChatGptAuthManager(
    private val context: Context,
    private val keyStore: KeyStore,
    private val client: OkHttpClient = FoodAnalysisService.defaultClient,
) {
    data class Session(
        val clientId: String,
        val subject: String,
        val email: String?,
        val name: String?,
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val expiresAtEpochSeconds: Long,
        val scopes: Set<String>,
    ) {
        val planUsageEnabled: Boolean get() = PLAN_SCOPE in scopes
    }

    private val refreshMutex = Mutex()

    fun session(): Session? = keyStore.load(SESSION_KEY)?.let(::decodeSession)

    fun hostId(): String {
        keyStore.load(HOST_ID_KEY)?.let { return it }
        val id = "urn:uuid:${UUID.randomUUID()}"
        keyStore.save(HOST_ID_KEY, id)
        return id
    }

    suspend fun signIn(): Session {
        val existing = session()
        val registeredClientId = keyStore.load(CLIENT_ID_KEY)
        val firstRegistration = registeredClientId.isNullOrBlank()
        val requestedClientId = registeredClientId ?: DYNAMIC_CLIENT_ID
        val state = randomBase64Url(32)
        val nonce = randomBase64Url(32)
        val verifier = randomBase64Url(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

        val callback = awaitAuthorizationCallback { redirectUri ->
            Uri.parse(AUTHORIZATION_ENDPOINT).buildUpon().apply {
                appendQueryParameter("client_id", requestedClientId)
                if (firstRegistration) appendQueryParameter("agent_name_hint", APP_NAME)
                appendQueryParameter("ext_agent_host_id", hostId())
                if (!firstRegistration) {
                    existing?.idToken?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("id_token_hint", it) }
                    existing?.email?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("login_hint", it) }
                }
                appendQueryParameter("response_type", "code")
                appendQueryParameter("redirect_uri", redirectUri)
                appendQueryParameter("scope", REQUESTED_SCOPES)
                appendQueryParameter("resource", RESOURCE)
                appendQueryParameter("state", state)
                appendQueryParameter("nonce", nonce)
                appendQueryParameter("code_challenge_method", "S256")
                appendQueryParameter("code_challenge", challenge)
            }.build()
        }

        if (callback.state != state) throw ChatGptAuthException("ChatGPT sign-in state did not match.")
        callback.error?.let { throw ChatGptAuthException("ChatGPT sign-in was not completed: $it") }
        val code = callback.code ?: throw ChatGptAuthException("ChatGPT sign-in returned no authorization code.")
        val issuedClientId = callback.clientId ?: registeredClientId
            ?: throw ChatGptAuthException("ChatGPT did not issue a client ID.")
        if (!firstRegistration && issuedClientId != registeredClientId) {
            throw ChatGptAuthException("ChatGPT returned a client ID for a different registration.")
        }

        // Persist registration before exchanging the one-time code, as required by
        // the dynamic-client flow. A retry can then reuse the issued client id.
        keyStore.save(CLIENT_ID_KEY, issuedClientId)
        val tokenJson = tokenRequest(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", issuedClientId)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("redirect_uri", callback.redirectUri)
                .add("resource", RESOURCE)
                .build(),
        )
        val idToken = tokenJson.requiredString("id_token")
        val identity = verifyIdToken(idToken, issuedClientId, nonce)
        if (existing != null && issuedClientId == existing.clientId && identity.subject != existing.subject) {
            throw ChatGptAuthException("ChatGPT returned a different account for this registration.")
        }
        val scopes = tokenJson.optString("scope").split(' ').filter(String::isNotBlank).toSet()
        val session = Session(
            clientId = issuedClientId,
            subject = identity.subject,
            email = identity.email,
            name = identity.name,
            accessToken = tokenJson.requiredString("access_token"),
            refreshToken = tokenJson.requiredString("refresh_token"),
            idToken = idToken,
            expiresAtEpochSeconds = Instant.now().epochSecond + tokenJson.optLong("expires_in", 3600L),
            scopes = scopes,
        )
        saveSession(session)
        return session
    }

    suspend fun accessToken(): String = refreshMutex.withLock {
        val current = session() ?: throw ChatGptAuthException("Connect your ChatGPT account in Settings first.")
        if (!current.planUsageEnabled) throw ChatGptAuthException("Enable ChatGPT plan usage for this app.")
        if (current.expiresAtEpochSeconds > Instant.now().epochSecond + 60) return@withLock current.accessToken

        val json = tokenRequest(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", current.clientId)
                .add("refresh_token", current.refreshToken)
                .add("resource", RESOURCE)
                .build(),
        )
        val scopes = json.optString("scope").takeIf(String::isNotBlank)
            ?.split(' ')?.filter(String::isNotBlank)?.toSet() ?: current.scopes
        val refreshed = current.copy(
            accessToken = json.requiredString("access_token"),
            refreshToken = json.optString("refresh_token").takeIf(String::isNotBlank) ?: current.refreshToken,
            idToken = json.optString("id_token").takeIf(String::isNotBlank) ?: current.idToken,
            expiresAtEpochSeconds = Instant.now().epochSecond + json.optLong("expires_in", 3600L),
            scopes = scopes,
        )
        if (!refreshed.planUsageEnabled) throw ChatGptAuthException("ChatGPT plan usage is no longer enabled.")
        saveSession(refreshed)
        refreshed.accessToken
    }

    suspend fun listModels(): List<String> {
        val request = Request.Builder()
            .url("$RESOURCE/models")
            .header("Authorization", "Bearer ${accessToken()}")
            .get()
            .build()
        val body = executeJson(request)
        val models = body.optJSONArray("models") ?: return emptyList()
        return buildList {
            for (i in 0 until models.length()) {
                val item = models.optJSONObject(i) ?: continue
                if (item.optString("visibility") != "list") continue
                item.optString("slug").trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    /** Returns true when OpenAI confirmed revocation; local credentials are always removed. */
    suspend fun disconnect(): Boolean {
        val current = session()
        val revocationConfirmed = current == null || runCatching {
                val discovery = executeJson(Request.Builder().url(DISCOVERY_ENDPOINT).get().build())
                val body = FormBody.Builder()
                    .add("token", current.refreshToken)
                    .add("token_type_hint", "refresh_token")
                    .add("client_id", current.clientId)
                    .build()
                val request = Request.Builder().url(discovery.requiredString("revocation_endpoint")).post(body).build()
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) throw ChatGptAuthException("ChatGPT disconnect failed (HTTP ${response.code}).")
                }
            }.isSuccess
        keyStore.delete(SESSION_KEY)
        return revocationConfirmed
    }

    private suspend fun tokenRequest(body: FormBody): JSONObject =
        executeJson(Request.Builder().url(TOKEN_ENDPOINT).post(body).build())

    private suspend fun executeJson(request: Request): JSONObject {
        val response = try {
            client.newCall(request).await()
        } catch (_: UnknownHostException) {
            throw ChatGptAuthException(
                "OpenAI-authenticatie kan auth.openai.com niet vinden. Controleer je netwerkverbinding en probeer opnieuw.",
            )
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val detail = runCatching {
                    JSONObject(text).opt("error")?.let { error ->
                        if (error is JSONObject) error.optString("message") else error.toString()
                    }
                }.getOrNull().orEmpty()
                throw ChatGptAuthException(detail.ifBlank { "ChatGPT request failed (HTTP ${it.code})." })
            }
            return runCatching { JSONObject(text) }
                .getOrElse { throw ChatGptAuthException("ChatGPT returned an invalid response.") }
        }
    }

    private suspend fun awaitAuthorizationCallback(buildUrl: (String) -> Uri): CallbackResult =
        withContext(Dispatchers.IO) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = AUTH_TIMEOUT_MS
                val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"
                val authUrl = buildUrl(redirectUri)
                withContext(Dispatchers.Main) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, authUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    throw ChatGptAuthException("ChatGPT sign-in timed out.")
                }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val requestLine = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val target = requestLine.split(' ').getOrNull(1)
                        ?: throw ChatGptAuthException("ChatGPT callback was invalid.")
                    val uri = Uri.parse("http://127.0.0.1$target")
                    val ok = uri.path == "/auth/callback"
                    val html = if (ok) {
                        "<html><body><h2>Connected to Chompass</h2><p>You can close this tab and return to the app.</p></body></html>"
                    } else {
                        "<html><body><h2>Could not connect</h2><p>Return to Chompass and try again.</p></body></html>"
                    }
                    val bytes = html.toByteArray(StandardCharsets.UTF_8)
                    it.getOutputStream().bufferedWriter().use { writer ->
                        writer.write("HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\n")
                        writer.write("Content-Type: text/html; charset=utf-8\r\n")
                        writer.write("Content-Length: ${bytes.size}\r\n")
                        writer.write("Connection: close\r\n\r\n")
                        writer.write(html)
                    }
                    CallbackResult(
                        redirectUri = redirectUri,
                        code = uri.getQueryParameter("code"),
                        state = uri.getQueryParameter("state"),
                        clientId = uri.getQueryParameter("client_id"),
                        error = uri.getQueryParameter("error"),
                    )
                }
            }
        }

    private suspend fun verifyIdToken(token: String, clientId: String, nonce: String): Identity {
        val parts = token.split('.')
        if (parts.size != 3) throw ChatGptAuthException("ChatGPT returned an invalid identity token.")
        val header = JSONObject(String(base64UrlDecode(parts[0]), StandardCharsets.UTF_8))
        if (header.optString("alg") != "RS256") throw ChatGptAuthException("ChatGPT identity token used an unsupported algorithm.")
        val kid = header.optString("kid").takeIf(String::isNotBlank)
            ?: throw ChatGptAuthException("ChatGPT identity token had no key id.")
        val jwks = executeJson(Request.Builder().url(JWKS_ENDPOINT).get().build()).optJSONArray("keys")
            ?: throw ChatGptAuthException("ChatGPT identity keys were unavailable.")
        val jwk = (0 until jwks.length()).asSequence()
            .mapNotNull(jwks::optJSONObject)
            .firstOrNull { it.optString("kid") == kid && it.optString("kty") == "RSA" }
            ?: throw ChatGptAuthException("ChatGPT identity signing key was not found.")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(
                BigInteger(1, base64UrlDecode(jwk.requiredString("n"))),
                BigInteger(1, base64UrlDecode(jwk.requiredString("e"))),
            ),
        )
        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
            verify(base64UrlDecode(parts[2]))
        }
        if (!verified) throw ChatGptAuthException("ChatGPT identity signature was invalid.")

        val claims = JSONObject(String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8))
        // OpenID issuer values may be serialized with a trailing slash. Treat
        // that equivalent form as the same fixed OpenAI issuer; otherwise a
        // successful browser consent is discarded before the session is saved.
        if (claims.optString("iss").trimEnd('/') != ISSUER) {
            throw ChatGptAuthException("ChatGPT identity issuer was invalid.")
        }
        val audienceMatches = when (val aud = claims.opt("aud")) {
            is String -> aud == clientId
            is JSONArray -> (0 until aud.length()).any { aud.optString(it) == clientId }
            else -> false
        }
        if (!audienceMatches) throw ChatGptAuthException("ChatGPT identity audience was invalid.")
        if (claims.optString("azp").isNotBlank() && claims.optString("azp") != clientId) {
            throw ChatGptAuthException("ChatGPT identity authorized party was invalid.")
        }
        if (claims.optLong("exp") <= Instant.now().epochSecond) throw ChatGptAuthException("ChatGPT identity token expired.")
        if (claims.optString("nonce") != nonce) throw ChatGptAuthException("ChatGPT identity nonce did not match.")
        return Identity(
            subject = claims.requiredString("sub"),
            email = claims.optString("email").takeIf(String::isNotBlank),
            name = claims.optString("name").takeIf(String::isNotBlank),
        )
    }

    private fun saveSession(session: Session) {
        keyStore.save(SESSION_KEY, JSONObject().apply {
            put("client_id", session.clientId)
            put("subject", session.subject)
            put("email", session.email)
            put("name", session.name)
            put("access_token", session.accessToken)
            put("refresh_token", session.refreshToken)
            put("id_token", session.idToken)
            put("expires_at", session.expiresAtEpochSeconds)
            put("scopes", JSONArray(session.scopes.toList()))
        }.toString())
    }

    private fun decodeSession(raw: String): Session? = runCatching {
        val json = JSONObject(raw)
        val scopeArray = json.optJSONArray("scopes") ?: JSONArray()
        Session(
            clientId = json.requiredString("client_id"),
            subject = json.requiredString("subject"),
            email = json.optString("email").takeIf(String::isNotBlank),
            name = json.optString("name").takeIf(String::isNotBlank),
            accessToken = json.requiredString("access_token"),
            refreshToken = json.requiredString("refresh_token"),
            idToken = json.requiredString("id_token"),
            expiresAtEpochSeconds = json.getLong("expires_at"),
            scopes = (0 until scopeArray.length()).mapNotNull { scopeArray.optString(it).takeIf(String::isNotBlank) }.toSet(),
        )
    }.getOrNull()

    private data class CallbackResult(
        val redirectUri: String,
        val code: String?,
        val state: String?,
        val clientId: String?,
        val error: String?,
    )

    private data class Identity(val subject: String, val email: String?, val name: String?)

    companion object {
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        private const val APP_NAME = "Chompass"
        private const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
        private const val ISSUER = "https://auth.openai.com"
        private const val AUTHORIZATION_ENDPOINT = "$ISSUER/api/accounts/authorize"
        private const val TOKEN_ENDPOINT = "$ISSUER/api/accounts/oauth/token"
        private const val DISCOVERY_ENDPOINT = "$ISSUER/.well-known/openid-configuration"
        private const val JWKS_ENDPOINT = "$ISSUER/.well-known/jwks.json"
        const val RESOURCE = "https://api.openai.com/v1"
        private const val REQUESTED_SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
        private const val AUTH_TIMEOUT_MS = 5 * 60 * 1000
        private const val HOST_ID_KEY = "chatgpt_host_id"
        private const val CLIENT_ID_KEY = "chatgpt_client_id"
        private const val SESSION_KEY = "chatgpt_session"

        private val secureRandom = SecureRandom()

        private fun randomBase64Url(bytes: Int): String = ByteArray(bytes).also(secureRandom::nextBytes).let(::base64Url)
        private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        private fun base64UrlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    }
}

class ChatGptAuthException(message: String) : Exception(message)

private fun JSONObject.requiredString(name: String): String =
    optString(name).takeIf(String::isNotBlank)
        ?: throw ChatGptAuthException("ChatGPT response was missing $name.")

