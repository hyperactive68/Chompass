package app.chompass.services.ai

import app.chompass.R
import app.chompass.models.ChatMessage
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

    /**
     * Tool-enabled Coach turn for ChatGPT-plan tokens.
     *
     * Responses function calls are returned as output items. The app executes
     * each requested local tool, appends the original output items plus
     * function_call_output items, and asks Responses for the next turn. This is
     * the same multi-round contract used by the other Coach providers, without
     * exposing an API key or using ChatGPT's private backend endpoints.
     */
    suspend fun coach(
        client: OkHttpClient,
        model: String,
        accessToken: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        newUserMessage: String,
        imageBytes: ByteArray?,
        tools: JSONArray,
        maxOutputTokens: Int,
        maxToolRounds: Int = 6,
        executeTool: suspend (name: String, arguments: JSONObject) -> String,
        resourceBaseUrl: String = ChatGptAuthManager.RESOURCE,
    ): String {
        val input = JSONArray()
        for (message in history) {
            input.put(
                JSONObject()
                    .put("role", if (message.role == ChatMessage.Role.USER) "user" else "assistant")
                    .put("content", message.content),
            )
        }
        val userContent = JSONArray().apply {
            imageBytes?.let { image ->
                put(
                    JSONObject()
                        .put("type", "input_image")
                        .put("image_url", "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(image)}"),
                )
            }
            put(JSONObject().put("type", "input_text").put("text", newUserMessage))
        }
        input.put(JSONObject().put("role", "user").put("content", userContent))

        repeat(maxToolRounds) {
            val body = JSONObject()
                .put("model", model)
                .put("instructions", systemPrompt)
                .put("input", input)
                .put("tools", tools)
                .put("tool_choice", "auto")
                // ChatGPT-plan preview requires streaming and rejects max_output_tokens.
                .put("store", false)
                .put("stream", true)
            val request = Request.Builder()
                .url("${resourceBaseUrl.trimEnd('/')}/responses")
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            val response = executeStreamingResponse(client, request)
            val output = response.optJSONArray("output") ?: throw AiError.InvalidResponse
            val functionCalls = buildList {
                for (i in 0 until output.length()) {
                    val item = output.optJSONObject(i) ?: continue
                    if (item.optString("type") == "function_call") add(item)
                }
            }
            if (functionCalls.isEmpty()) {
                return responseText(response) ?: throw AiError.InvalidResponse
            }

            // Responses requires the model's function_call output items to be
            // passed back alongside the matching function_call_output items.
            for (i in 0 until output.length()) {
                output.optJSONObject(i)?.let(input::put)
            }
            for (call in functionCalls) {
                val callId = call.optString("call_id").takeIf(String::isNotBlank)
                    ?: throw AiError.InvalidResponse
                val name = call.optString("name").takeIf(String::isNotBlank)
                    ?: throw AiError.InvalidResponse
                val arguments = runCatching { JSONObject(call.optString("arguments", "{}")) }
                    .getOrElse { JSONObject() }
                val result = executeTool(name, arguments)
                input.put(
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", callId)
                        .put("output", result),
                )
            }
        }
        throw AiError.Api("Coach exceeded the tool-call round limit. Try rephrasing your question.", messageRes = R.string.ai_error_coach_round_limit)
    }

    /** Reads one ChatGPT-plan Responses stream and returns its completed response object. */
    private suspend fun executeStreamingResponse(client: OkHttpClient, request: Request): JSONObject {
        var completed: JSONObject? = null
        var failure: AiError? = null
        val response = RetryPolicy.open { client.newCall(request) }
        AiSse.read(response) { payload ->
            val event = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return@read
            when (event["type"]?.jsonPrimitive?.content) {
                "response.completed" -> {
                    completed = event["response"]?.let { JSONObject(it.toString()) }
                }
                "response.failed", "response.incomplete" -> {
                    val responseNode = event["response"]?.jsonObject
                    val error = responseNode?.get("error")?.jsonObject
                    val code = error?.get("code")?.jsonPrimitive?.content.orEmpty()
                    val message = error?.get("message")?.jsonPrimitive?.content.orEmpty()
                    val suffix = code.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
                    failure = AiError.Api(
                        message.ifBlank { "ChatGPT response failed$suffix." },
                        httpStatus = null,
                        providerMessage = code.takeIf(String::isNotBlank),
                    )
                }
            }
        }
        failure?.let { throw it }
        return completed ?: throw AiError.InvalidResponse
    }

    private fun responseText(response: JSONObject): String? {
        response.optString("output_text").takeIf(String::isNotBlank)?.let { return it.trim() }
        val output = response.optJSONArray("output") ?: return null
        val text = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                if (block.optString("type") == "output_text") {
                    text.append(block.optString("text"))
                }
            }
        }
        return text.toString().takeIf(String::isNotBlank)?.trim()
    }
}

