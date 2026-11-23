package com.example.myapplication.ui.mine

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.avatar.AvatarStorage
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.repository.UserRepository
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.validation.AuthValidation
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountUiState(
    val username: String = "",
    val nickname: String = "",
    val avatarId: Int = 0,
    val avatarPath: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val sessionIdentity: SessionIdentity? = null,
    val avatarError: String? = null,
    val avatarPickerPending: Boolean = false,
)

class AccountViewModel(
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager,
    private val appContext: Context,
) : ViewModel() {
    private data class Operation(val identity: SessionIdentity, val token: String = UUID.randomUUID().toString())

    private val _state = MutableStateFlow(AccountUiState(loading = true))
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private val picker = AvatarPickerGuard()
    private var operation: Operation? = null

    init {
        viewModelScope.launch {
            sessionManager.identity.distinctUntilChanged().catch { error ->
                if (error is CancellationException) throw error
                operation = null
                _state.value = AccountUiState(
                    error = "无法读取登录状态，请重新进入后重试",
                    avatarPickerPending = picker.pendingTicket() != null,
                )
            }.collectLatest { identity ->
                // A new epoch owns a new draft. Old IO may finish, but loses permission to publish.
                operation = null
                _state.value = AccountUiState(
                    sessionIdentity = identity,
                    loading = identity.userId != null,
                    avatarPickerPending = picker.pendingTicket() != null,
                )
                val id = identity.userId ?: return@collectLatest
                try {
                    val user = userRepository.findById(id)
                    if (_state.value.sessionIdentity != identity || sessionManager.identity.first() != identity) {
                        return@collectLatest
                    }
                    _state.update {
                        if (it.sessionIdentity != identity) it else it.copy(
                            username = user?.username.orEmpty(),
                            nickname = user?.nickname.orEmpty(),
                            avatarId = user?.avatarId ?: 0,
                            avatarPath = user?.avatarPath,
                            loading = false,
                            error = if (user == null) "账号已不可用，请重新登录" else null,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update {
                        if (it.sessionIdentity != identity) it else it.copy(
                            loading = false,
                            error = "个人信息读取失败，请重新进入后重试",
                        )
                    }
                }
            }
        }
    }

    fun onNicknameChange(value: String) {
        if (_state.value.loading) return
        _state.update { it.copy(nickname = value, error = null) }
    }

    internal fun beginAvatarPick(): AvatarPickerTicket? {
        val current = _state.value
        if (current.loading || current.username.isBlank() || operation != null) return null
        val ticket = picker.begin(current.sessionIdentity) ?: return null
        _state.update { it.copy(avatarPickerPending = true, avatarError = null) }
        return ticket
    }

    internal fun cancelAvatarPick(token: String?, launchFailed: Boolean = false) {
        if (!picker.cancel(token)) return
        _state.update {
            it.copy(
                avatarPickerPending = false,
                avatarError = if (launchFailed) "无法打开图片选择器，请重试" else it.avatarError,
            )
        }
    }

    internal fun consumeAvatarPick(token: String?, uri: Uri?) {
        if (uri == null) {
            // Cancellation never edits the nickname or either avatar field, even after an epoch change.
            if (picker.consume(token, _state.value.sessionIdentity, cancelled = true).outcome == AvatarPickerOutcome.CANCELLED) {
                _state.update { it.copy(avatarPickerPending = false) }
            }
            return
        }
        viewModelScope.launch {
            try {
                val identity = sessionManager.identity.first()
                val result = picker.consume(token, identity)
                _state.update { it.copy(avatarPickerPending = picker.pendingTicket() != null) }
                when (result.outcome) {
                    AvatarPickerOutcome.ACCEPTED -> selectCustomAvatar(uri, result.ticket!!.identity)
                    AvatarPickerOutcome.IDENTITY_CHANGED -> _state.update {
                        it.copy(avatarError = "登录状态已变化，请重新选择图片")
                    }
                    AvatarPickerOutcome.EXPIRED -> _state.update {
                        it.copy(avatarError = "本次选图已过期，请重新选择图片")
                    }
                    AvatarPickerOutcome.NO_PENDING, AvatarPickerOutcome.TOKEN_MISMATCH,
                    AvatarPickerOutcome.CANCELLED -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (picker.cancel(token)) {
                    _state.update { it.copy(avatarPickerPending = false, avatarError = "无法确认登录状态，请重新选择图片") }
                }
            }
        }
    }

    fun selectAvatar(avatarId: Int) {
        if (avatarId != 0 && AvatarOptions.none { it.id == avatarId }) {
            _state.update { it.copy(avatarError = "该头像不可用，请重新选择") }
            return
        }
        val ticket = beginOperation(avatar = true) ?: return
        val previous = _state.value
        // Keep the existing immediate preview. A failed write restores only the avatar fields.
        _state.update { it.copy(avatarId = avatarId, avatarPath = null) }
        viewModelScope.launch {
            try {
                if (!isCurrent(ticket)) return@launch
                userRepository.updateAvatar(ticket.identity.userId!!, avatarId, null)
                if (isCurrent(ticket)) _state.update { it.copy(avatarId = avatarId, avatarPath = null, avatarError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentSafely(ticket)) {
                    _state.update {
                        it.copy(avatarId = previous.avatarId, avatarPath = previous.avatarPath, avatarError = "头像保存失败，请重试")
                    }
                }
            } finally {
                cleanUnreferencedPhoto(previous.avatarPath, ticket.identity.userId!!)
                finishOperation(ticket)
            }
        }
    }

    fun selectCustomAvatar(uri: Uri) {
        val identity = _state.value.sessionIdentity ?: return
        selectCustomAvatar(uri, identity)
    }

    private fun selectCustomAvatar(uri: Uri, identity: SessionIdentity) {
        val ticket = beginOperation(avatar = true, expectedIdentity = identity) ?: return
        val previousPath = _state.value.avatarPath
        viewModelScope.launch {
            var candidatePath: String? = null
            try {
                if (!isCurrent(ticket)) return@launch
                // Assign inside IO: prompt cancellation must not lose the path of a completed file.
                withContext(Dispatchers.IO) {
                    candidatePath = AvatarStorage.saveFromUri(appContext, uri, ticket.identity.userId!!)
                }
                if (!isCurrent(ticket)) return@launch
                val path = candidatePath
                if (path == null) {
                    _state.update { it.copy(avatarError = "图片读取失败，请重试") }
                    return@launch
                }
                userRepository.updateAvatar(ticket.identity.userId!!, 0, path)
                if (isCurrent(ticket)) {
                    _state.update { it.copy(avatarId = 0, avatarPath = path, avatarError = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentSafely(ticket)) _state.update { it.copy(avatarError = "图片保存失败，请重试") }
            } finally {
                // Room may have committed before coroutine cancellation. Re-read the exact reference
                // before deleting, so a successfully persisted candidate is never removed by cleanup.
                cleanUnreferencedPhoto(previousPath, ticket.identity.userId!!)
                cleanUnreferencedPhoto(candidatePath, ticket.identity.userId!!)
                finishOperation(ticket)
            }
        }
    }

    fun saveNickname(onDone: () -> Unit) {
        if (_state.value.loading || _state.value.avatarPickerPending) return
        val nickname = _state.value.nickname.trim()
        AuthValidation.validateNickname(nickname)?.let { message ->
            _state.update { it.copy(error = message) }
            return
        }
        val ticket = beginOperation() ?: return
        viewModelScope.launch {
            try {
                if (!isCurrent(ticket)) return@launch
                userRepository.updateNickname(ticket.identity.userId!!, nickname)
                if (isCurrent(ticket)) {
                    _state.update { it.copy(nickname = nickname, error = null) }
                    finishOperation(ticket)
                    onDone()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentSafely(ticket)) _state.update { it.copy(error = "昵称保存失败，请重试") }
            } finally {
                finishOperation(ticket)
            }
        }
    }

    fun changePassword(oldPassword: String, newPassword: String, confirm: String, onDone: () -> Unit) {
        if (_state.value.loading || _state.value.avatarPickerPending) return
        if (oldPassword.isBlank()) {
            _state.update { it.copy(error = "请输入原密码") }
            return
        }
        AuthValidation.validatePassword(newPassword)?.let { message ->
            _state.update { it.copy(error = message) }
            return
        }
        if (newPassword != confirm) {
            _state.update { it.copy(error = "两次输入的新密码不一致") }
            return
        }
        val ticket = beginOperation() ?: return
        viewModelScope.launch {
            try {
                if (!isCurrent(ticket)) return@launch
                val result = userRepository.changePassword(ticket.identity.userId!!, oldPassword, newPassword)
                if (!isCurrent(ticket)) return@launch
                when (result) {
                    is UserRepository.AuthResult.Success -> {
                        finishOperation(ticket)
                        onDone()
                    }
                    is UserRepository.AuthResult.Failure -> _state.update { it.copy(error = result.message) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentSafely(ticket)) _state.update { it.copy(error = "密码修改失败，请重试") }
            } finally {
                finishOperation(ticket)
            }
        }
    }

    private fun beginOperation(avatar: Boolean = false, expectedIdentity: SessionIdentity? = null): Operation? {
        val current = _state.value
        val identity = current.sessionIdentity ?: return null
        if (identity.userId == null || current.username.isBlank() || current.loading || current.avatarPickerPending || operation != null ||
            (expectedIdentity != null && identity != expectedIdentity)
        ) return null
        val ticket = Operation(identity)
        operation = ticket
        _state.update { if (avatar) it.copy(loading = true, avatarError = null) else it.copy(loading = true, error = null) }
        return ticket
    }

    private fun owns(ticket: Operation): Boolean =
        operation?.token == ticket.token && _state.value.sessionIdentity == ticket.identity

    private suspend fun isCurrent(ticket: Operation): Boolean {
        if (!owns(ticket)) return false
        val activeIdentity = sessionManager.identity.first()
        return activeIdentity == ticket.identity && owns(ticket)
    }

    private suspend fun isCurrentSafely(ticket: Operation): Boolean = try {
        isCurrent(ticket)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun finishOperation(ticket: Operation) {
        if (!owns(ticket)) return
        operation = null
        _state.update { it.copy(loading = false) }
    }

    private suspend fun cleanUnreferencedPhoto(path: String?, userId: Long) {
        if (path.isNullOrBlank()) return
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val reference = userRepository.findById(userId)?.avatarPath
                if (!reference.isNullOrBlank() &&
                    AvatarStorage.resolve(appContext, reference).canonicalFile == AvatarStorage.resolve(appContext, path).canonicalFile
                ) return@withContext
                if (!AvatarStorage.deleteStored(appContext, path, userId)) {
                    Log.w("AccountViewModel", "Unreferenced avatar cleanup could not remove the owned file")
                }
            } catch (_: Exception) {
                // Keep the file when reference verification fails; never trade a transient DB error
                // for removing a still-referenced photo.
                Log.w("AccountViewModel", "Avatar reference verification failed; file retained")
            }
        }
    }
}

class AccountViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AccountViewModel::class.java)) { "Unsupported account ViewModel" }
        val db = AppDatabase.getInstance(context)
        return AccountViewModel(
            UserRepository(db.userDao()),
            SessionManager(context),
            context.applicationContext,
        ) as T
    }
}
