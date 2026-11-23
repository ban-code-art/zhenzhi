package com.example.myapplication.core

import android.util.Log

/**
 * 最小诊断日志（第四期 P1：此前主源码零日志，启动恢复的静默 `runCatching` 让故障不可诊断）。
 *
 * 约定：
 *  - 只记录「发生了什么 + 错误类型/码」，**不记录**密钥、正文、用户问题、笔记内容；
 *  - 分区 tag 便于过滤：`adb logcat -s AcuLearn`；
 *  - 这是诊断兜底，不是审计日志：业务状态仍以数据库为准。
 */
object AppLog {
    private const val TAG = "AcuLearn"

    fun w(area: String, message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, "[$area] $message") else Log.w(TAG, "[$area] $message", error)
    }

    fun e(area: String, message: String, error: Throwable? = null) {
        if (error == null) Log.e(TAG, "[$area] $message") else Log.e(TAG, "[$area] $message", error)
    }

    /** 关键路径的“非致命但被吞掉”的结果。 */
    fun swallowed(area: String, message: String, error: Throwable) {
        // 只打错误类型与消息前缀，避免把大段异常栈灌进日志。
        Log.w(TAG, "[$area] $message (${error.javaClass.simpleName}: ${error.message?.take(160)})")
    }
}
