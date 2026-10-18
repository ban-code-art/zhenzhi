package com.example.myapplication.ui.mine

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.avatar.AvatarStorage
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.repository.UserRepository
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.validation.AuthValidation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountUiState(
    val username: String = "",
    val nickname: String = "",
    val avatarId: Int = 0,
    val avatarPath: String? = null,
    val loading: Boolean = false,
    val error: String? = null
)

class AccountViewModel(
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager,
    private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    private var userId: Long? = null

    init {
        viewModelScope.launch {
            val id = sessionManager.currentUserId.first() ?: return@launch
            userId = id
            userRepository.findById(id)?.let { user ->
                _state.update {
                    it.copy(
                        username = user.username,
                        nickname = user.nickname ?: "",
                        avatarId = user.avatarId,
                        avatarPath = user.avatarPath
                    )
                }
            }
        }
    }

    fun onNicknameChange(value: String) {
        _state.update { it.copy(nickname = value, error = null) }
    }

    fun selectAvatar(avatarId: Int) {
        val id = userId ?: return
        _state.update { it.copy(avatarId = avatarId, avatarPath = null) }
        viewModelScope.launch {
            userRepository.updateAvatar(id, avatarId, null)
        }
    }

    fun selectCustomAvatar(uri: Uri) {
        val id = userId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val path = withContext(Dispatchers.IO) {
                AvatarStorage.saveFromUri(appContext, uri, id)
            }
            if (path != null) {
                _state.update { it.copy(loading = false, avatarId = 0, avatarPath = path) }
                userRepository.updateAvatar(id, 0, path)
            } else {
                _state.update { it.copy(loading = false, error = "图片读取失败，请重试") }
            }
        }
    }

    fun saveNickname(onDone: () -> Unit) {
        val nickname = _state.value.nickname.trim()
        AuthValidation.validateNickname(nickname)?.let { message ->
            _state.update { it.copy(error = message) }
            return
        }
        val id = userId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            userRepository.updateNickname(id, nickname)
            _state.update { it.copy(loading = false) }
            onDone()
        }
    }

    fun changePassword(oldPassword: String, newPassword: String, confirm: String, onDone: () -> Unit) {
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
        val id = userId ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = userRepository.changePassword(id, oldPassword, newPassword)) {
                is UserRepository.AuthResult.Success -> {
                    _state.update { it.copy(loading = false) }
                    onDone()
                }
                is UserRepository.AuthResult.Failure ->
                    _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }
}

class AccountViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val db = AppDatabase.getInstance(context)
        return AccountViewModel(
            UserRepository(db.userDao()),
            SessionManager(context),
            context.applicationContext
        ) as T
    }
}
