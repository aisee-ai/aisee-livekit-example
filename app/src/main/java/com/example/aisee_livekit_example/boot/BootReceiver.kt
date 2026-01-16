package com.example.aisee_livekit_example.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.aisee_livekit_example.accessibility.LiveKitAccessibilityService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "onReceive called")

        if (context == null || intent?.action == null) {
            Log.e(TAG, "Received null context or intent")
            return
        }

        Log.d(TAG, "BootReceiver triggered with action: ${intent.action}")

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            testRoot()
            enableAccessibilityService(context)
            enableScreenOn()
            startAccessibilityService(context)
        } else {
            Log.w(TAG, "Unexpected action: ${intent.action}")
        }
    }

    private fun enableAccessibilityService(context: Context) {
        val cmd1 =
            "settings put secure enabled_accessibility_services ${context.packageName}/${LiveKitAccessibilityService::class.java.name}"
        val cmd2 = "settings put secure accessibility_enabled 1"
        try {
            runShellCommand(cmd1)
            runShellCommand(cmd2)
        }
        catch (e: Exception) {
            Log.e(TAG, "Failed to enable accessibility service", e)
        }

        Log.d(TAG, "Accessibility service enabled")
    }

    private fun enableScreenOn() {
        runShellCommand("input keyevent KEYCODE_WAKEUP")
        runShellCommand("input keyevent 82")
        runShellCommand("settings put system screen_off_timeout 2147483647")
        runShellCommand("settings put system screen_brightness 0")
        Log.d(TAG, "Screen forced on")
    }

    private fun runShellCommand(command: String) {
        try {
            // Some devices ship a minimal "su" that does NOT support "-c".
            // The error "invalid uid/gid '-c'" indicates this su expects a UID/GID first.
            // Try root with: su 0 sh -c <command>
            val process = Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "-c", command))

            // Capture output for debugging (many failures only show up on stderr).
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                if (stdout.isNotBlank()) Log.d(TAG, "Command ok: $command\nstdout: $stdout")
                else Log.d(TAG, "Command ok: $command")
            } else {
                Log.e(
                    TAG,
                    "Command failed (exit=$exitCode): $command" +
                        (if (stdout.isNotBlank()) "\nstdout: $stdout" else "") +
                        (if (stderr.isNotBlank()) "\nstderr: $stderr" else "")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shell command error: $command", e)
        }
    }

    private fun testRoot() {
        runShellCommand("id")
        runShellCommand("id -u")
        runShellCommand("ls -l $(which su) 2>/dev/null || which su")
    }

    private fun startServiceSafely(context: Context, serviceIntent: Intent) {
        Log.d(TAG, "Starting service: ${serviceIntent.component?.className}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun startAccessibilityService(context: Context) {
        startServiceSafely(
            context,
            Intent(context, LiveKitAccessibilityService::class.java)
        )
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}