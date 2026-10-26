package app.chompass.services.ai

import kotlinx.coroutines.runBlocking
import app.chompass.data.OpenRouterReasoningEffort
import app.chompass.models.ChatMessage
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatGptResponsesClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun completedStream_returnsTextAndUsesOfficialRequestShape() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"type":"response.output_text.delta","delta":"{\"food\":"}

                    data: {"type":"response.output_text.delta","delta":"\"apple\"}"}

                    data: {"type":"response.completed","response":{"status":"completed"}}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        val deltas = mutableListOf<String>()
        val result = ChatGptResponsesClient.analyze(
            client = OkHttpClient(),
            model = "gpt-test",
            accessToken = "oauth-token",
            prompt = "Analyze this food",
            imageBytesList = listOf(byteArrayOf(1, 2, 3)),
            onDelta = deltas::add,
            resourceBaseUrl = server.url("v1").toString(),
        )

        assertEquals("{\"food\":\"apple\"}", result)
        assertEquals(listOf("{\"food\":", "\"apple\"}"), deltas)
        val request = server.takeRequest()
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer oauth-token", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("gpt-test", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        val content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("input_image", content.getJSONObject(0).getString("type"))
        assertEquals("input_text", content.getJSONObject(1).getString("type"))
    }

    @Test
    fun reasoningEffort_isSentAsResponsesReasoningObject() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"type":"response.output_text.delta","delta":"ok"}

                    data: {"type":"response.completed","response":{"status":"completed"}}

                    data: [DONE]

                    """.trimIndent(),
                ),
        )

        ChatGptResponsesClient.analyze(
            client = OkHttpClient(),
            model = "gpt-5.6-sol",
            accessToken = "oauth-token",
            prompt = "Analyze",
            imageBytesList = emptyList(),
            reasoningEffort = OpenRouterReasoningEffort.XHIGH,
            resourceBaseUrl = server.url("v1").toString(),
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("xhigh", body.getJSONObject("reasoning").getString("effort"))
    }

    @Test
    fun autoReasoning_omitsOptionalField() {
        assertTrue(ChatGptResponsesClient.reasoningBody(OpenRouterReasoningEffort.AUTO) == null)
        assertEquals("max", ChatGptResponsesClient.reasoningBody(OpenRouterReasoningEffort.MAX)?.getString("effort"))
    }

    @Test
    fun coachUsesStreamedTextWhenCompletedResponseOmitsOutput() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"type":"response.output_text.delta","delta":"Coach antwoord"}
                    
                    data: {"type":"response.completed","response":{"status":"completed"}}
                    
                    data: [DONE]
                    
                    """.trimIndent(),
                ),
        )

        val result = ChatGptResponsesClient.coach(
            client = OkHttpClient(),
            model = "gpt-test",
            accessToken = "oauth-token",
            systemPrompt = "You are Coach.",
            history = emptyList(),
            newUserMessage = "What should I eat?",
            imageBytes = null,
            tools = org.json.JSONArray(),
            maxOutputTokens = 256,
            executeTool = { _, _ -> error("no tool expected") },
            reasoningEffort = OpenRouterReasoningEffort.MAX,
            resourceBaseUrl = server.url("v1").toString(),
        )

        assertEquals("Coach antwoord", result)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("max", body.getJSONObject("reasoning").getString("effort"))
    }

    @Test
    fun streamWithoutCompletedEvent_isRejected() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"),
        )

        val error = runCatching {
            runBlocking {
                ChatGptResponsesClient.analyze(
                    client = OkHttpClient(),
                    model = "gpt-test",
                    accessToken = "oauth-token",
                    prompt = "Analyze",
                    imageBytesList = emptyList(),
                    resourceBaseUrl = server.url("v1").toString(),
                )
            }
        }.exceptionOrNull()

        assertEquals(AiError.InvalidResponse, error)
    }

    @Test
    fun coachExecutesResponsesFunctionCallAndContinuesWithOutput() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call","call_id":"call_1","name":"get_data_summary","arguments":"{}"}]}}

data: [DONE]
""".trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Your logged intake is on track."}]}]}}

data: [DONE]
""".trimIndent(),
                ),
        )

        val toolCalls = mutableListOf<String>()
        val result = ChatGptResponsesClient.coach(
            client = OkHttpClient(),
            model = "gpt-test",
            accessToken = "oauth-token",
            systemPrompt = "You are Coach.",
            history = listOf(ChatMessage(role = ChatMessage.Role.USER, content = "How am I doing?")),
            newUserMessage = "Summarize my intake.",
            imageBytes = null,
            tools = org.json.JSONArray().put(
                JSONObject()
                    .put("type", "namespace")
                    .put("name", "chompass")
                    .put("description", "Local Coach tools")
                    .put(
                        "tools",
                        org.json.JSONArray().put(
                            JSONObject()
                                .put("type", "function")
                                .put("name", "get_data_summary")
                                .put("parameters", JSONObject().put("type", "object")),
                        ),
                    ),
            ),
            maxOutputTokens = 256,
            executeTool = { name, args ->
                toolCalls += "$name:${args.toString()}"
                "{\"foods\":{\"count\":3}}"
            },
            resourceBaseUrl = server.url("v1").toString(),
        )

        assertEquals("Your logged intake is on track.", result)
        assertEquals(listOf("get_data_summary:{}"), toolCalls)
        val first = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("gpt-test", first.getString("model"))
        assertFalse(first.getBoolean("store"))
        assertTrue(first.getBoolean("stream"))
        assertFalse(first.has("max_output_tokens"))
        assertEquals("auto", first.getString("tool_choice"))
        val namespace = first.getJSONArray("tools").getJSONObject(0)
        assertEquals("namespace", namespace.getString("type"))
        assertEquals("chompass", namespace.getString("name"))
        val second = JSONObject(server.takeRequest().body.readUtf8())
        val secondInput = second.getJSONArray("input")
        assertEquals("function_call", secondInput.getJSONObject(secondInput.length() - 2).getString("type"))
        assertEquals("function_call_output", secondInput.getJSONObject(secondInput.length() - 1).getString("type"))
    }
}

