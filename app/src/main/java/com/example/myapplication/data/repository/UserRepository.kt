package com.example.myapplication.data.repository

import com.example.myapplication.data.local.dao.UserDao
import com.example.myapplication.data.local.entity.UserEntity
import com.example.myapplication.data.security.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class UserRepository(private val userDao: UserDao) {

    sealed interface AuthResult {
        data class Success(val userId: Long) : AuthResult
        data class Failure(val message: String) : AuthResult
    }

    suspend fun register(username: String, password: String, nickname: String?): AuthResult {
        if (userDao.countByUsername(username) > 0) {
            return AuthResult.Failure("用户名已存在")
        }
        val now = System.currentTimeMillis()
        val (salt, hash) = withContext(Dispatchers.Default) {
            val generatedSalt = PasswordHasher.generateSalt()
            generatedSalt to PasswordHasher.hash(password, generatedSalt)
        }
        val id = userDao.insert(
            UserEntity(
                username = username,
                passwordHash = hash,
                salt = salt,
                nickname = nickname?.takeIf { it.isNotBlank() },
                createdAt = now,
                updatedAt = now
            )
        )
        return AuthResult.Success(id)
    }

    suspend fun login(username: String, password: String): AuthResult {
        val user = userDao.findByUsername(username)
            ?: return AuthResult.Failure("用户名或密码错误")
        val matches = withContext(Dispatchers.Default) {
            PasswordHasher.verify(password, user.salt, user.passwordHash)
        }
        if (!matches) {
            return AuthResult.Failure("用户名或密码错误")
        }
        return AuthResult.Success(user.id)
    }

    suspend fun findById(id: Long): UserEntity? = userDao.findById(id)

    fun observeById(id: Long): Flow<UserEntity?> = userDao.observeById(id)

    suspend fun updateNickname(userId: Long, nickname: String?) {
        userDao.updateProfile(userId, nickname?.takeIf { it.isNotBlank() }, System.currentTimeMillis())
    }

    suspend fun updateAvatar(userId: Long, avatarId: Int, avatarPath: String?) {
        userDao.updateAvatar(userId, avatarId, avatarPath, System.currentTimeMillis())
    }

    suspend fun changePassword(userId: Long, oldPassword: String, newPassword: String): AuthResult {
        val user = userDao.findById(userId) ?: return AuthResult.Failure("用户不存在")
        val matches = withContext(Dispatchers.Default) {
            PasswordHasher.verify(oldPassword, user.salt, user.passwordHash)
        }
        if (!matches) {
            return AuthResult.Failure("原密码错误")
        }
        val (salt, hash) = withContext(Dispatchers.Default) {
            val generatedSalt = PasswordHasher.generateSalt()
            generatedSalt to PasswordHasher.hash(newPassword, generatedSalt)
        }
        userDao.updatePassword(userId, hash, salt, System.currentTimeMillis())
        return AuthResult.Success(userId)
    }
}
