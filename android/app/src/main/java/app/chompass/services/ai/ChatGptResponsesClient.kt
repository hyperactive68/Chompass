package app.chompass.services.ai

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Responses API client for OAuth tokens carrying chatgpt.tokens.use.direct. */
object ChatGptResponsesClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun analyze(
        client: OkHttpClient,
        model: String,
        accessToken: String,
        prompt: String,
        imageBytesList: List<ByteArray>,
        onDelta: (String) -> Unit = {},
        resourceBaseUrl: String = ChatGptAuthManager.RESOURCE,
    ): String {
        val content = JSONArray().apply {
            imageBytesList.forEach { image ->
                put(
                    JSONObject()
                        .put("type", "input_image")
                        .put("image_url", "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(image)}"),
                )
            }
            put(JSONObject().put("type", "input_text").put("text", prompt))
        }
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("store", false)
            .put("stream", true)
        val request = Request.Builder()
            .url("${resourceBaseUrl.trimEnd('/')}/responses")
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val text = StringBuilder()
        var completed = false
        val response = RetryPolicy.open { client.newCall(request) }
        AiSse.read(response) { payload ->
            val event = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return@read
            when (event["type"]?.jsonPrimitive?.content) {
                "response.output_text.delta" -> {
                    val delta = event["delta"]?.jsonPrimitive?.content.orEmpty()
                    if (delta.isNotEmpty()) {
                        text.append(delta)
                        onDelta(delta)
                    }
                }
                "response.completed" -> completed = true
                "response.failed", "response.incomplete" -> {
                    val responseNode = event["response"]?.jsonObject
                    val error = responseNode?.get("error")?.jsonObject
                    val code = error?.get("code")?.jsonPrimitive?.content.orEmpty()
                    val message = error?.get("message")?.jsonPrimitive?.content.orEmpty()
                    val friendly = if (code == "subscription_sharing_usage_limit_exceeded" ||
                        code == "subscription_sharing_usage_unavailable"
                    ) {
                        "ChatGPT plan usage is unavailable or its usage limit was reached. Review usage in ChatGPT settings."
                    } else {
                        message.ifBlank { "ChatGPT response failed${code.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}." }
                    }
                    throw AiError.Api(friendly, providerMessage = code.takeIf(String::isNotBlank))
                }
            }
        }
        if (!completed) throw AiError.InvalidResponse
        return text.toString().takeIf(String::isNotBlank) ?: throw AiError.InvalidResponse
    }
}
