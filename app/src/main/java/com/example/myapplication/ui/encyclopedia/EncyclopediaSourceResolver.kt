package com.example.myapplication.ui.encyclopedia

import com.example.myapplication.data.encyclopedia.EncyclopediaSourceRef

internal sealed interface EncyclopediaResolvedSource {
    data class BundledCourseChapter(
        val bookKey: String,
        val chapterKey: String,
        val quote: String?,
        val sourceTitle: String,
        val fallbackUrl: String,
    ) : EncyclopediaResolvedSource

    data class ExternalUrl(
        val title: String,
        val url: String,
        val isPdfDocument: Boolean,
    ) : EncyclopediaResolvedSource

    data class FallbackNotice(
        val title: String,
        val url: String,
        val reason: String,
    ) : EncyclopediaResolvedSource
}

/**
 * 经学百科资料出处解析器：
 * - 经核对可映射到内置课程章节的教材链接，优先进入本地 `chapter-source` 课程预览并保留来源引号定位；
 * - 国标 PDF 及合法外部教学链接交由系统文档/浏览器处理；
 * - 缺映射、非法协议或离线时保留原标题与可复制说明，保证原词条现场不丢失。
 */
internal object EncyclopediaSourceResolver {
    fun resolve(
        ref: EncyclopediaSourceRef,
        contextHint: String? = null,
    ): EncyclopediaResolvedSource {
        val url = ref.url.trim()
        val title = ref.title.trim().ifBlank { "参考资料" }
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) {
            return EncyclopediaResolvedSource.FallbackNotice(
                title = title,
                url = url,
                reason = "当前条目来源仅保留题录信息（$title），暂无法直接打开外部链接。",
            )
        }

        val mappedChapter = matchBundledCourse(url, title, contextHint)
        if (mappedChapter != null) {
            return mappedChapter
        }

        val isPdf = url.contains(".pdf", ignoreCase = true)
        return EncyclopediaResolvedSource.ExternalUrl(
            title = title,
            url = url,
            isPdfDocument = isPdf,
        )
    }

    private fun matchBundledCourse(
        url: String,
        title: String,
        contextHint: String?,
    ): EncyclopediaResolvedSource.BundledCourseChapter? {
        val bookKey = "acupuncture-foundation"
        return when {
            url.contains("courseware/zhenjiuxue/default.htm", ignoreCase = true) ->
                EncyclopediaResolvedSource.BundledCourseChapter(
                    bookKey = bookKey,
                    chapterKey = "twelve-meridians",
                    quote = contextHint?.takeIf(String::isNotBlank) ?: "手太阴肺经",
                    sourceTitle = title,
                    fallbackUrl = url,
                )

            url.contains("courseware/zhenjiuxue/c1/", ignoreCase = true) ->
                EncyclopediaResolvedSource.BundledCourseChapter(
                    bookKey = bookKey,
                    chapterKey = "common-acupoints",
                    quote = contextHint?.takeIf(String::isNotBlank),
                    sourceTitle = title,
                    fallbackUrl = url,
                )

            url.contains("courseware/zhenjiuxue/c4/", ignoreCase = true) ->
                EncyclopediaResolvedSource.BundledCourseChapter(
                    bookKey = bookKey,
                    chapterKey = "treatment-principles",
                    quote = contextHint?.takeIf(String::isNotBlank),
                    sourceTitle = title,
                    fallbackUrl = url,
                )

            url == "https://www.zhongyibaodian.com/zhenjiuxue/" ->
                EncyclopediaResolvedSource.BundledCourseChapter(
                    bookKey = bookKey,
                    chapterKey = "twelve-meridians",
                    quote = contextHint?.takeIf(String::isNotBlank),
                    sourceTitle = title,
                    fallbackUrl = url,
                )

            else -> null
        }
    }
}
