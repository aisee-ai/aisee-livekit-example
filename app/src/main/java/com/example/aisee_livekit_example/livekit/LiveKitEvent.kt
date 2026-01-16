package com.example.aisee_livekit_example.livekit

sealed class LiveKitEvent {
    object Connected : LiveKitEvent()
    object Disconnected : LiveKitEvent()
    data class TextReceived(
        val text: String,
        val fromIdentity: String?
    ) : LiveKitEvent()
    data class Error(val throwable: Throwable) : LiveKitEvent()
}