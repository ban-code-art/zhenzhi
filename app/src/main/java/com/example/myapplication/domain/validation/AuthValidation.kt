package com.example.myapplication.domain.validation

object AuthValidation {

    private val USERNAME_REGEX = Regex("^[A-Za-z0-9_]{3,20}$")

    const val USERNAME_RULE = "用户名需为 3–20 位字母、数字或下划线"
    const val PASSWORD_RULE = "密码需为 6–20 位，且同时包含字母和数字"
    const val NICKNAME_RULE = "昵称不能超过 20 个字符"

    fun validateUsername(username: String): String? =
        if (USERNAME_REGEX.matches(username)) null else USERNAME_RULE

    fun validatePassword(password: String): String? {
        val valid = password.length in 6..20 &&
            password.any { it.isLetter() } &&
            password.any { it.isDigit() }
        return if (valid) null else PASSWORD_RULE
    }

    fun validateNickname(nickname: String?): String? {
        if (nickname.isNullOrBlank()) return null
        return if (nickname.trim().length <= 20) null else NICKNAME_RULE
    }
}
