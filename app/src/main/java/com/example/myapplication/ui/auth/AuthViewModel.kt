package com.example.myapplication.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.repository.UserRepository
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.validation.AuthValidation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    data class UiState(
        val username: String = "",
        val password: String = "",
        val nickname: String = "",
        val loading: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onNicknameChange(value: String) = _state.update { it.copy(nickname = value, error = null) }

    fun login(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.loading) return
        if (s.username.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "请输入用户名和密码") }
            return
        }
        submit(
            snapshot = s,
            failureMessage = "无法完成本地登录，请重试",
            onSuccess = onSuccess,
        ) { userRepository.login(s.username.trim(), s.password) }
    }

    fun register(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.loading) return
        val username = s.username.trim()
        AuthValidation.validateUsername(username)?.let { msg ->
            _state.update { it.copy(error = msg) }
            return
        }
        AuthValidation.validatePassword(s.password)?.let { msg ->
            _state.update { it.copy(error = msg) }
            return
        }
        AuthValidation.validateNickname(s.nickname)?.let { msg ->
            _state.update { it.copy(error = msg) }
            return
        }

        submit(
            snapshot = s,
            failureMessage = "无法完成本地注册，请重试",
            onSuccess = onSuccess,
        ) { userRepository.register(username, s.password, s.nickname.trim()) }
    }

    private fun submit(
        snapshot: UiState,
        failureMessage: String,
        onSuccess: () -> Unit,
        operation: suspend () -> UserRepository.AuthResult,
    ) {
        // Acquire before launch: repeated button/IME actions cannot queue a second request.
        if (!_state.compareAndSet(snapshot, snapshot.copy(loading = true, error = null))) return
        viewModelScope.launch {
            val succeeded = try {
                when (val result = operation()) {
                    is UserRepository.AuthResult.Success -> {
                        sessionManager.login(result.userId)
                        true
                    }
                    is UserRepository.AuthResult.Failure -> {
                        _state.update { it.copy(error = result.message) }
                        false
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(error = failureMessage) }
                false
            } finally {
                _state.update { it.copy(loading = false) }
            }
            if (succeeded) onSuccess()
        }
    }
}

class AuthViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = AppDatabase.getInstance(context)
        return AuthViewModel(
            UserRepository(db.userDao()),
            SessionManager(context)
        ) as T
    }
}
