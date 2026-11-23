package com.example.myapplication.data.reader.importer

/**
 * MIME 文本编码的最小实现（P5-01 HTML/MHTML 适配器共用）。
 *
 * 为什么不用 `java.util.Base64`：minSdk 24，`java.util.Base64` 需要 API 26；
 * 也不能用 `android.util.Base64`（JVM 单测不可用）。这类纯字节变换自己实现更稳，
 * 而且能顺带把"非法字符/缺填充"当成明确失败而不是静默产出脏数据。
 */
object MimeBase64 {
    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        alphabet.forEachIndexed { index, ch -> table[ch.code] = index }
    }

    /** 解码标准 Base64（忽略空白与换行，遇到 '=' 结束）。非法字符抛 [IllegalArgumentException]。 */
    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in text) {
            if (ch == '=') break
            if (ch.isWhitespace()) continue
            val code = ch.code
            val value = if (code < 128) DECODE_TABLE[code] else -1
            require(value >= 0) { "非法的 Base64 字符: '$ch'" }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** 解码 URL-safe Base64（`-`/`_` 变体），MHTML 的 Content-Location 声明里可能遇到。 */
    fun decodeUrlSafe(text: String): ByteArray =
        decode(text.replace('-', '+').replace('_', '/'))
}

object QuotedPrintable {
    /**
     * 解码 quoted-printable 正文：`=XX` 十六进制转义、`=\r\n`/`=\n` 软换行。
     *
     * 只在 MHTML 的分段正文里使用；头部字段不做解码（不要拿它解 RFC 2047 编码字）。
     */
    fun decode(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var index = 0
        while (index < bytes.size) {
            val byte = bytes[index]
            if (byte == '='.code.toByte()) {
                // 软换行：= 后紧跟 CRLF 或 LF
                if (index + 1 < bytes.size && bytes[index + 1] == '\n'.code.toByte()) {
                    index += 2
                    continue
                }
                if (index + 2 < bytes.size && bytes[index + 1] == '\r'.code.toByte() &&
                    bytes[index + 2] == '\n'.code.toByte()
                ) {
                    index += 3
                    continue
                }
                if (index + 2 < bytes.size) {
                    val high = hexValue(bytes[index + 1])
                    val low = hexValue(bytes[index + 2])
                    if (high >= 0 && low >= 0) {
                        out.write((high shl 4) or low)
                        index += 3
                        continue
                    }
                }
                // 孤立的 '=' 原样保留（宽松处理，避免坏包直接失败）
                out.write(byte.toInt())
                index += 1
            } else {
                out.write(byte.toInt())
                index += 1
            }
        }
        return out.toByteArray()
    }

    private fun hexValue(byte: Byte): Int = when (val ch = byte.toInt().toChar()) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }
}
