package com.sometenn.callcaptions.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * The configuration and original/translation routing follow My Translator's MIT client.
 */
class Soniox(
    private val key: String,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val httpClient: OkHttpClient = OkHttpClient(),
    callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : CaptionService {

    companion object {
        const val DEFAULT_ENDPOINT = "wss://stt-rt.soniox.com/transcribe-websocket"

        private val json = Json { ignoreUnknownKeys = true }

        internal fun buildConfigJson(): String {
            val payload: JsonObject = buildJsonObject {
                put("model", "stt-rt-v6")
                put("audio_format", "pcm_s16le")
                put("sample_rate", 16000)
                put("num_channels", 1)
                put("language_hints", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("ja")) })
                put("enable_endpoint_detection", true)
                put(
                    "translation",
                    buildJsonObject {
                        put("type", "one_way")
                        put("target_language", "zh")
                    },
                )
            }
            return payload.toString()
        }

        internal fun parseReply(raw: String): Reply = json.decodeFromString(Reply.serializer(), raw)
    }

    private val callbackScope = CoroutineScope(SupervisorJob() + callbackDispatcher)
    private val closed = AtomicBoolean(false)
    private val failedNotified = AtomicBoolean(false)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var lastHttpStatus: Int? = null

    @Volatile
    private var lastCloseCode: Int? = null

    @Volatile
    private var lastCloseReason: String? = null

    override suspend fun start(
        receive: (List<Token>) -> Unit,
        failed: (Throwable) -> Unit,
    ) {
        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .build()

        val ws = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    lastHttpStatus = response.code
                    val sent = webSocket.send(buildConfigJson())
                    if (sent) {
                        opened.complete(Unit)
                    } else {
                        val err = explain(CaptionError("Soniox 连接暂时中断。请检查网络后重试。", kind = CaptionError.Kind.TRANSIENT))
                        opened.completeExceptionally(err)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncoming(text, receive, failed)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleIncoming(bytes.utf8(), receive, failed)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    lastCloseCode = code
                    lastCloseReason = reason
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    lastCloseCode = code
                    lastCloseReason = reason
                    val err = ServiceErrorClassifier.classify(
                        provider = Provider.SONIOX,
                        httpStatus = lastHttpStatus,
                        closeCode = code.takeIf { it != 0 },
                        closeReason = reason,
                    )
                    if (!opened.isCompleted) {
                        opened.completeExceptionally(err)
                    } else {
                        notifyFailure(err, failed)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response != null) {
                        lastHttpStatus = response.code
                    }
                    val err = explain(t)
                    if (!opened.isCompleted) {
                        opened.completeExceptionally(err)
                    } else {
                        notifyFailure(err, failed)
                    }
                }
            },
        )
        socket = ws

        try {
            opened.await()
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Throwable) {
            close()
            throw explain(e)
        }
    }

    private fun handleIncoming(
        raw: String,
        receive: (List<Token>) -> Unit,
        failed: (Throwable) -> Unit,
    ) {
        if (closed.get()) return
        try {
            val reply = parseReply(raw)
            if (reply.errorCode != null || !reply.errorMessage.isNullOrEmpty()) {
                throw ServiceErrorClassifier.classify(
                    provider = Provider.SONIOX,
                    errorCode = reply.errorCode,
                    errorMessage = reply.errorMessage,
                )
            }
            val tokens = reply.tokens
            if (tokens != null) {
                callbackScope.launch {
                    if (!closed.get()) receive(tokens)
                }
            }
            if (reply.finished == true) {
                throw CaptionError("Soniox 会话已结束。", kind = CaptionError.Kind.TRANSIENT)
            }
        } catch (e: Throwable) {
            notifyFailure(explain(e), failed)
        }
    }

    private fun notifyFailure(error: Throwable, failed: (Throwable) -> Unit) {
        if (closed.get()) return
        if (failedNotified.compareAndSet(false, true)) {
            callbackScope.launch {
                if (!closed.get()) failed(error)
            }
        }
    }

    private fun explain(error: Throwable): Throwable {
        if (error is CaptionError || error is CancellationException) return error
        return ServiceErrorClassifier.classify(
            provider = Provider.SONIOX,
            httpStatus = lastHttpStatus?.takeIf { it >= 400 },
            closeCode = lastCloseCode?.takeIf { it != 0 },
            closeReason = lastCloseReason,
        )
    }

    override suspend fun send(data: ByteArray) {
        val ws = socket ?: throw explain(IllegalStateException("Socket not connected"))
        val ok = ws.send(data.toByteString(0, data.size))
        if (!ok) {
            throw explain(IllegalStateException("WebSocket send failed"))
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            socket?.close(1001, null)
            socket = null
            callbackScope.cancel()
        }
    }
}
