package com.example.myapplication.domain.reader

/**
 * Stable identity for a readable document. Built-in content is addressed by a
 * stable package key, while imported content must never reuse the legacy
 * chapters-table identity.
 */
data class DocumentRef(val value: String) {
    init {
        require(value.isNotBlank()) { "document id must not be blank" }
        require(value.length <= MAX_ID_LENGTH) { "document id is too long" }
        require(value.none(Char::isISOControl)) { "document id contains control characters" }
    }

    val isBuiltIn: Boolean get() = value.startsWith(BUILTIN_PREFIX)
    val isImported: Boolean get() = value.startsWith(IMPORTED_PREFIX)

    companion object {
        private const val MAX_ID_LENGTH = 256
        private const val BUILTIN_PREFIX = "builtin:chapter:"
        private const val IMPORTED_PREFIX = "imported:"

        fun builtIn(chapterKey: String): DocumentRef {
            require(chapterKey.isNotBlank()) { "chapter key must not be blank" }
            require(chapterKey.none(Char::isISOControl)) { "chapter key contains control characters" }
            return DocumentRef(BUILTIN_PREFIX + chapterKey)
        }

        fun imported(documentId: String): DocumentRef {
            require(documentId.isNotBlank()) { "imported document id must not be blank" }
            return DocumentRef(IMPORTED_PREFIX + documentId)
        }
    }
}