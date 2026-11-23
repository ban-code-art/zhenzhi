package com.example.myapplication.ui

import com.example.myapplication.LearningApplication
import java.io.File

/** Do not run database recovery in the crash collector process. */
class GeckoPocApplication : LearningApplication() {
    override fun onCreate() {
        if (File("/proc/self/cmdline").readText().trimEnd('\u0000').endsWith(":m03Crash")) return
        super.onCreate()
    }
}
