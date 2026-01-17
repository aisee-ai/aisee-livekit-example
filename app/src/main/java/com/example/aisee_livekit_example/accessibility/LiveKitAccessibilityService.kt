package com.example.aisee_livekit_example.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.aisee_livekit_example.livekit.ConfigTokenRepository
import com.example.aisee_livekit_example.livekit.LiveKitClient
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamBytesOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream

class LiveKitAccessibilityService : AccessibilityService(), LifecycleOwner {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var lifecycleRegistry: LifecycleRegistry
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private lateinit var liveKitClient: LiveKitClient
    private var isMicOn = true
    private var isRoomConnected = false

    // CameraX
    private var imageCapture: ImageCapture? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, " onCreate")

        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val tokenRepository = ConfigTokenRepository()
        liveKitClient = LiveKitClient(applicationContext, tokenRepository = tokenRepository)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, " onServiceConnected")

        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        // Start camera on main thread (required for bindToLifecycle)
//        serviceScope.launch {
//            try {
//                withContext(Dispatchers.Main) { startCamera() }
//            } catch (t: Throwable) {
//                Log.e(TAG, "Failed to start camera", t)
//            }
//        }

        val identity = "wearable-user"
        val roomName = "default-room"

        serviceScope.launch {
            try {
                Log.d(TAG, "Connecting to LiveKit room: $roomName")
                liveKitClient.connect(identity, roomName)
                liveKitClient.setMicEnabled(isMicOn)
                isRoomConnected = true
                registerTakePhotoRpc(liveKitClient.room)
                Log.d(TAG, "LiveKit room connected from accessibility service")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to connect LiveKit room", t)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used
    }

    override fun onInterrupt() {
        Log.w(TAG, " interrupted by system")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_F2) {
            Log.d(TAG, "F2 key released in ")

            if (!isRoomConnected) {
                Log.w(TAG, "Room not connected yet. Ignoring F2.")
                return true
            }

            isMicOn = !isMicOn
            Log.d(TAG, "Toggling mic. New state: $isMicOn")

            liveKitClient.setMicEnabled(isMicOn)

            if (isMicOn) {
                serviceScope.launch { liveKitClient.sendText("Hi") }
            }
            return true
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, " onDestroy")

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        serviceScope.cancel()
    }

    private fun registerTakePhotoRpc(room: Room?) {
        room?.registerRpcMethod(method = "takePhoto") { request ->
            Log.d(TAG, "RPC takePhoto called: $request")

            try {
                val uri = captureImage()
                Log.d(TAG, "Photo captured and saved: $uri")
//                serviceScope.launch {
//                    runCatching { sendImageUri(room, uri) }
//                        .onFailure { t -> Log.e(TAG, "sendImageUri failed", t) }
//                }
                val file = copyUriToCache(uri)
                val result = room.localParticipant.sendFile(file, StreamBytesOptions(topic = "images"))
                result.onSuccess { info -> Log.i(TAG, "sent file id: ${info.id}") }
                    .onFailure { e -> Log.e(TAG, "sendFile failed", e) }
//                sendImageUri(room, uri)
//                val file = File(uri)
//                val result = room.localParticipant.sendFile(file,
//                    StreamBytesOptions(topic = "images")
//                )
//                result.onSuccess { info ->
//                    Log.i(TAG, "sent file id: ${info.id}")
//                }
                uri.toString()
            } catch (t: Throwable) {
                Log.e(TAG, "takePhoto failed", t)
                "ERROR: ${t.message ?: "unknown error"}"
            }
        }
    }

    private suspend fun copyUriToCache(uri: android.net.Uri): File = withContext(Dispatchers.IO) {
        val dst = File(cacheDir, "upload_${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)!!.use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
        dst
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)

                Log.d(TAG, "Camera started and bound to lifecycle.")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private suspend fun captureImage(): android.net.Uri {
        val capture = imageCapture ?: throw IllegalStateException("Camera not ready (imageCapture is null)")

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GeminiRealtime-Images")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        if (cont.isActive) cont.resumeWithException(exc)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val saved = output.savedUri
                        if (saved != null) {
                            if (cont.isActive) cont.resume(saved)
                        } else {
                            if (cont.isActive) cont.resumeWithException(
                                IllegalStateException("Image saved but MediaStore returned null Uri")
                            )
                        }
                    }
                }
            )
        }
    }

    companion object {
        private const val TAG = "LiveKitAccessibilityService"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}