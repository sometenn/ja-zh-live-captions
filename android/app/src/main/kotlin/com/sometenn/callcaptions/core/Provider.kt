package com.sometenn.callcaptions.core

import okhttp3.OkHttpClient

enum class Provider(val rawValue: String) {
    SONIOX("Soniox"),
    GEMINI("Gemini");

    fun connect(key: String, httpClient: OkHttpClient = defaultHttpClient): CaptionService = when (this) {
        SONIOX -> Soniox(key = key, httpClient = httpClient)
        GEMINI -> Gemini(key = key, httpClient = httpClient)
    }

    companion object {
        private val defaultHttpClient: OkHttpClient by lazy { OkHttpClient() }

        fun fromRawValue(raw: String?): Provider =
            entries.firstOrNull { it.rawValue == raw } ?: SONIOX
    }
}

interface CaptionService {
    suspend fun start(
        receive: (List<Token>) -> Unit,
        failed: (Throwable) -> Unit,
    )

    suspend fun send(data: ByteArray)

    fun close()
}
