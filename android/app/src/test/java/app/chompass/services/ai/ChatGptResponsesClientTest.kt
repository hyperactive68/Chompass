package app.chompass.services.ai

import kotlinx.coroutines.runBlocking
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
}
