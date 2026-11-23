package com.example.myapplication.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Receives commit results from Android [PackageInstaller.Session] for self-update APKs.
 * Mirrors FluxDO's InstallResultReceiver lifecycle handling for STATUS_PENDING_USER_ACTION,
 * STATUS_SUCCESS, and explicit STATUS_FAILURE_* error reporting.
 */
class UpdateInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        if (sessionId == -1) return
        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirmation = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            extractConfirmationIntent(intent)
        } else {
            null
        }
        UpdateInstallSessionRegistry.dispatchResult(
            context = context.applicationContext,
            sessionId = sessionId,
            versionName = versionName,
            status = status,
            statusMessage = statusMessage,
            confirmationIntent = confirmation,
        )
    }

    @Suppress("DEPRECATION")
    private fun extractConfirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.example.myapplication.ACTION_UPDATE_INSTALL_RESULT"
        const val EXTRA_SESSION_ID = "update_session_id"
        const val EXTRA_VERSION_NAME = "update_version_name"
    }
}

internal object UpdateInstallSessionRegistry {
    @Volatile
    var activeInstaller: UpdateInstaller? = null

    fun dispatchResult(
        context: Context,
        sessionId: Int,
        versionName: String,
        status: Int,
        statusMessage: String?,
        confirmationIntent: Intent?,
    ) {
        activeInstaller?.handleSessionResult(
            context = context,
            sessionId = sessionId,
            versionName = versionName,
            status = status,
            statusMessage = statusMessage,
            confirmationIntent = confirmationIntent,
        )
    }
}
