package com.example.myapplication.ui.mine

import com.example.myapplication.data.session.SessionIdentity
import java.util.UUID

internal data class AvatarPickerTicket(
    val token: String,
    val identity: SessionIdentity,
    val issuedAtMillis: Long,
)

internal enum class AvatarPickerOutcome {
    ACCEPTED, CANCELLED, NO_PENDING, TOKEN_MISMATCH, IDENTITY_CHANGED, EXPIRED,
}

internal data class AvatarPickerResult(
    val outcome: AvatarPickerOutcome,
    val ticket: AvatarPickerTicket? = null,
)

/**
 * The ViewModel retains this authority through configuration recreation. A restored token after
 * process death has no matching ticket and is rejected. Keep an outstanding platform request even
 * after its deadline/account change: replacing it would make two tokenless platform deliveries
 * indistinguishable to the same ActivityResult launcher.
 */
internal class AvatarPickerGuard(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val lifetimeMillis: Long = 15 * 60 * 1_000L,
    private val nextToken: () -> String = { UUID.randomUUID().toString() },
) {
    init { require(lifetimeMillis > 0) }

    private var pending: AvatarPickerTicket? = null

    @Synchronized
    fun begin(identity: SessionIdentity?): AvatarPickerTicket? {
        val userId = identity?.userId ?: return null
        if (pending != null || userId <= 0L) return null
        val token = nextToken().also { require(it.isNotBlank()) }
        return AvatarPickerTicket(token, identity, nowMillis()).also { pending = it }
    }

    @Synchronized
    fun pendingTicket(): AvatarPickerTicket? = pending

    @Synchronized
    fun consume(
        token: String?,
        currentIdentity: SessionIdentity?,
        cancelled: Boolean = false,
    ): AvatarPickerResult {
        val ticket = pending ?: return AvatarPickerResult(AvatarPickerOutcome.NO_PENDING)
        if (token != ticket.token) return AvatarPickerResult(AvatarPickerOutcome.TOKEN_MISMATCH)
        pending = null
        if (cancelled) return AvatarPickerResult(AvatarPickerOutcome.CANCELLED)
        if (ticket.identity != currentIdentity || currentIdentity?.userId == null) {
            return AvatarPickerResult(AvatarPickerOutcome.IDENTITY_CHANGED)
        }
        val elapsed = nowMillis() - ticket.issuedAtMillis
        if (elapsed < 0L || elapsed >= lifetimeMillis) return AvatarPickerResult(AvatarPickerOutcome.EXPIRED)
        return AvatarPickerResult(AvatarPickerOutcome.ACCEPTED, ticket)
    }

    @Synchronized
    fun cancel(token: String?): Boolean {
        if (token == null || pending?.token != token) return false
        pending = null
        return true
    }
}
