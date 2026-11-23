package com.example.myapplication.data.reader.export

/** Counts logical package bytes/files before allocation; subtraction prevents overflow. */
internal class BackupV2FileBudget(val byteLimit: Long = BackupV2Limits.TOTAL_BYTES, val fileLimit: Int = BackupV2Limits.FILES) {
    var bytes: Long = 0; private set
    var files: Int = 0; private set
    init { v2require(byteLimit in 0..BackupV2Limits.TOTAL_BYTES && fileLimit in 0..BackupV2Limits.FILES,
        "LIMIT_EXCEEDED", "budget may only narrow the archive limits") }
    fun reserve(size: Long, count: Int = 1) {
        v2require(size >= 0 && count >= 0 && size <= byteLimit - bytes && count <= fileLimit - files,
            "LIMIT_EXCEEDED", "fixed resources exceed byte/file budget")
        bytes += size; files += count
    }
}
