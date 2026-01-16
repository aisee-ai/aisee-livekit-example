package com.example.aisee_livekit_example.livekit

import android.content.Context
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.annotations.Beta
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamTextOptions
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.track.DataPublishReliability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class LiveKitClient(
    private val appContext: Context,
    private val tokenRepository: TokenRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var room: Room? = null

    private val _events = MutableSharedFlow<LiveKitEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LiveKitEvent> = _events.asSharedFlow()

    @OptIn(Beta::class)
    suspend fun connect(identity: String, roomName: String) {
        if (room != null) return

        val wsUrl = tokenRepository.wsUrl
        val token = tokenRepository.getToken(identity, roomName)

        Log.d("LiveKitClient", "Connecting to $wsUrl with token $token")


        val audioHandler = AudioSwitchHandler(appContext).apply {
            // Make logs visible if you want
            loggingEnabled = true

            preferredDeviceList = listOf(
                AudioDevice.Speakerphone::class.java,
//                AudioDevice.WiredHeadset::class.java,
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.Earpiece::class.java,
            )
        }

//        val newRoom = LiveKit.create(appContext)

        val newRoom = LiveKit.create(
            appContext,
            overrides = LiveKitOverrides(
                audioOptions = AudioOptions(
                    audioHandler = audioHandler
                )
            )
        )
        room = newRoom

        registerTakePhotoRpc(room)


        scope.launch {
            newRoom.events.collect { event ->
                Log.d("LiveKitClient", "Room event: $event")

                when (event) {
                    is RoomEvent.Connected -> {
                        _events.tryEmit(LiveKitEvent.Connected)
                    }
                    is RoomEvent.TranscriptionReceived -> {
                        var transcription : String = event.transcriptionSegments[0].text
//                        val text = event.transcription.text
//                        Log.d("LiveKitClient", "TranscriptionReceived text=$text")
                        if (event.transcriptionSegments[0].final) {
                            _events.tryEmit(
                                LiveKitEvent.TextReceived(
                                    text = transcription,
                                    fromIdentity = "Agent"
                                )
                            )
                            Log.d("LiveKitClient", "Received final transcript $transcription")

                        }
//                        Log.d("LiveKitClient", "Recieved transcript ${event.transcriptionSegments}")

                    }

                    is RoomEvent.DataReceived -> {
                        val text = event.data.toString(Charsets.UTF_8)
                        val topic = event.topic

                        Log.d("LiveKitClient", "DataReceived topic=$topic text=$text")

                        _events.tryEmit(
                            LiveKitEvent.TextReceived(
                                text = text,
                                fromIdentity = event.participant?.identity?.value
                            )
                        )

                        when (topic) {
                            "lk.chat" -> {
                                // User text replies or agent replies if you configured output to lk.chat
                                _events.tryEmit(
                                    LiveKitEvent.TextReceived(
                                        text = text,
                                        fromIdentity = event.participant?.identity?.value
                                    )
                                )
                            }

                            "lk.transcription" -> {
                                // Agent transcription, partial or final
                                _events.tryEmit(
                                    LiveKitEvent.TextReceived(
                                        text = text,
                                        fromIdentity = event.participant?.identity?.value
                                    )
                                )
                            }

                            else -> {
                                // Debug or fallback
                                _events.tryEmit(
                                    LiveKitEvent.TextReceived(
                                        text = "[$topic] $text",
                                        fromIdentity = event.participant?.identity?.value
                                    )
                                )
                            }
                        }

                        _events.tryEmit(
                            LiveKitEvent.TextReceived(
                                text = text,
                                fromIdentity = event.participant?.identity?.value
                            )
                        )
                    }
                    is RoomEvent.Disconnected -> {
                        _events.tryEmit(LiveKitEvent.Disconnected)
                        event.error?.let { error ->
                            _events.tryEmit(LiveKitEvent.Error(error))
                        }
                    }
                    is RoomEvent.FailedToConnect -> {
                        _events.tryEmit(LiveKitEvent.Error(event.error))
                        Log.d("LiveKitClient", "Failed to connect: ${event.error}")
                        _events.tryEmit(LiveKitEvent.Disconnected)
                    }

                    else -> {
                        // ignore for now
                    }
                }
            }
        }

        try {
            newRoom.connect(
                url = wsUrl,
                token = token
            )
        } catch (t: Throwable) {
            _events.tryEmit(LiveKitEvent.Error(t))
            throw t
        }
    }

    suspend fun disconnect() {
        val r = room ?: return
        try {
            r.disconnect()
        } finally {
            room = null
            _events.tryEmit(LiveKitEvent.Disconnected)
        }
    }

    // Enabling the mic publishes the device microphone as an audio track to the room.
    // LiveKit automatically plays subscribed remote audio tracks through the device speaker.
    fun setMicEnabled(enabled: Boolean) {
        val local: LocalParticipant = room?.localParticipant ?: return
//        Log.d("LiveKitClient", "Setting mic enabled=$enabled")
        scope.launch {
            try {
                local.setMicrophoneEnabled(enabled)
            } catch (t: Throwable) {
                _events.tryEmit(LiveKitEvent.Error(t))
            }
        }
    }

    suspend fun sendText(message: String) {
        val r = room ?: return

        val bytes = message.toByteArray(Charsets.UTF_8)

        val oldResult = r.localParticipant.publishData(
            data = bytes,
            reliability = DataPublishReliability.RELIABLE,
            topic = "lk.chat"
        )

        val result = r.localParticipant.sendText(
            message,
            options = StreamTextOptions(
                topic = "lk.chat"
            )
        )


        result.exceptionOrNull()?.let { throw it }
    }

    private fun registerTakePhotoRpc(room: Room?) {
        room?.registerRpcMethod(
            method = "takePhoto"
        ) { request ->
            Log.d("LiveKitRPC", "photo taken")

            // Return value must be a String
            "This is a photo of a blue giraffe"
        }
    }
}