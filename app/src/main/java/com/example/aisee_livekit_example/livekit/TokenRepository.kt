package com.example.aisee_livekit_example.livekit

import com.example.aisee_livekit_example.BuildConfig

interface TokenRepository {
    val wsUrl: String
    suspend fun getToken(identity: String, roomName: String): String
}

class ConfigTokenRepository : TokenRepository {
    override val wsUrl: String = BuildConfig.LIVEKIT_WS_URL
    private val token: String = BuildConfig.LIVEKIT_TOKEN

    override suspend fun getToken(identity: String, roomName: String): String {
        return token
    }
}
