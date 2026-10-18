package com.example.myapplication.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.repository.UserRepository
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.validation.AuthValidation
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
        if (s.username.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "请输入用户名和密码") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = userRepository.login(s.username.trim(), s.password)) {
                is UserRepository.AuthResult.Success -> {
                    sessionManager.login(result.userId)
                    _state.update { it.copy(loading = false) }
                    onSuccess()
                }
                is UserRepository.AuthResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    fun register(onSuccess: () -> Unit) {
        val s = _state.value
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

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = userRepository.register(username, s.password, s.nickname.trim())) {
                is UserRepository.AuthResult.Success -> {
                    sessionManager.login(result.userId)
                    _state.update { it.copy(loading = false) }
                    onSuccess()
                }
                is UserRepository.AuthResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
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
