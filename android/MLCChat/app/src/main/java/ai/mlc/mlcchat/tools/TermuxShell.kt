package ai.mlc.mlcchat.tools

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Bridge that runs a shell command inside the Termux app via its public
 * `com.termux.RUN_COMMAND` intent API, and returns the captured result.
 *
 * On-device prerequisites (done once by the user, not by this app):
 *  - Termux installed (F-Droid / GitHub build recommended, not the stale
 *    Play Store one).
 *  - `allow-external-apps = true` in ~/.termux/termux.properties.
 *
 * Nothing runs inside this app's process: the command executes in Termux's
 * own Linux environment and sandbox. We only send an intent and receive a
 * result bundle back through a one-shot broadcast [PendingIntent].
 */
object TermuxShell {
    private const val TAG = "TermuxShell"

    const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    // Default login shell inside Termux.
    private const val BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
    const val DEFAULT_WORKDIR = "/data/data/com.termux/files/home"

    private const val RESULT_ACTION_PREFIX = "ai.mlc.mlcchat.TERMUX_RESULT_"
    private val resultSeq = AtomicInteger(0)

    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        /** Non-null when the bridge itself failed (Termux missing, timeout, …). */
        val bridgeError: String? = null
    ) {
        val isBridgeError get() = bridgeError != null
    }

    /** True if the Termux package is installed (visible via <queries> entry). */
    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Runs [command] through `bash -c` inside Termux and suspends until the
     * result arrives or [timeoutMs] elapses. Safe to call from any
     * coroutine; must not be called on the main thread for long commands
     * (it only suspends, but keep it off the UI dispatcher anyway).
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    suspend fun run(
        context: Context,
        command: String,
        workdir: String = DEFAULT_WORKDIR,
        timeoutMs: Long = 120_000L
    ): Result {
        val appContext = context.applicationContext
        if (!isTermuxInstalled(appContext)) {
            return Result("", "", null, "Termux is not installed.")
        }

        val resultAction = RESULT_ACTION_PREFIX + resultSeq.incrementAndGet()

        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        try {
                            appContext.unregisterReceiver(this)
                        } catch (_: Exception) {
                        }
                        if (cont.isActive) cont.resume(parseResult(intent))
                    }
                }

                val filter = IntentFilter(resultAction)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(
                        receiver, filter, Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    appContext.registerReceiver(receiver, filter)
                }

                cont.invokeOnCancellation {
                    try {
                        appContext.unregisterReceiver(receiver)
                    } catch (_: Exception) {
                    }
                }

                val resultIntent = Intent(resultAction).setPackage(appContext.packageName)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext, resultSeq.get(), resultIntent, flags
                )

                val execIntent = Intent().apply {
                    setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                    action = ACTION_RUN_COMMAND
                    putExtra(EXTRA_COMMAND_PATH, BASH_PATH)
                    putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                    putExtra(EXTRA_WORKDIR, workdir)
                    putExtra(EXTRA_BACKGROUND, true)
                    putExtra(EXTRA_SESSION_ACTION, "0")
                    putExtra(EXTRA_PENDING_INTENT, pendingIntent)
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(execIntent)
                    } else {
                        appContext.startService(execIntent)
                    }
                } catch (e: Exception) {
                    try {
                        appContext.unregisterReceiver(receiver)
                    } catch (_: Exception) {
                    }
                    Log.e(TAG, "Failed to start Termux RunCommandService", e)
                    val hint = "Could not reach Termux (${e.localizedMessage}). " +
                            "Check that Termux is installed and that " +
                            "allow-external-apps=true is set in termux.properties."
                    if (cont.isActive) cont.resume(Result("", "", null, hint))
                }
            }
        }

        return outcome ?: Result(
            "", "", null,
            "Command timed out after ${timeoutMs / 1000}s with no response from Termux."
        )
    }

    private fun parseResult(intent: Intent): Result {
        // Termux nests everything under a "result" Bundle.
        val bundle: Bundle? = intent.getBundleExtra("result")
        if (bundle == null) {
            return Result("", "", null, "Termux returned an empty result.")
        }
        val stdout = bundle.getString("stdout", "") ?: ""
        val stderr = bundle.getString("stderr", "") ?: ""
        val exitCode = if (bundle.containsKey("exitCode")) bundle.getInt("exitCode") else null
        // Termux's own plugin-level error channel (distinct from the command's stderr).
        val errCode = bundle.getInt("err", 0)
        val errmsg = bundle.getString("errmsg", "") ?: ""
        val bridgeError = if (errCode != 0 && errmsg.isNotBlank()) errmsg else null
        return Result(stdout, stderr, exitCode, bridgeError)
    }

    /** Ensures a main-thread Handler exists (reserved for future UI callbacks). */
    @Suppress("unused")
    private val mainHandler = Handler(Looper.getMainLooper())
}
