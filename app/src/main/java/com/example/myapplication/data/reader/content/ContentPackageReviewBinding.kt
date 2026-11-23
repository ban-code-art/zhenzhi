package com.example.myapplication.data.reader.content

import kotlinx.serialization.json.*

/** Stable review input; it conveys content identity, never reviewer authority or approval. */
object ContentPackageReviewBinding {
    fun payload(request: ContentProfessionalReviewRequest): JsonObject {
        val root = request.manifest
        ContentPackageWire.v2.check(root, "root")
        val role = request.scopeKey.substringBefore(':')
        val key = request.scopeKey.substringAfter(':', "")
        require(key.isNotBlank()) { "review binding: scope key required" }
        val chapters = root.objects("chapters")
        val subject = when (role) {
            "book" -> root.objects("books").singleOrNull { it.text("bookKey") == key }
            "chapter" -> chapters.singleOrNull { it.text("chapterKey") == key }
            "fact" -> chapters.flatMap { it.objects("knowledgePoints") }.flatMap { it.objects("facts") }
                .singleOrNull { it.text("factKey") == key }
            "question" -> root.objects("questions").singleOrNull { it.text("questionKey") == key }
            else -> null
        }
        require(subject != null && subject == request.subject && subject.getValue("review") == request.review) {
            "review binding: subject/scope differs from current manifest"
        }
        val keys = when (role) {
            "book" -> subject.getValue("chapterKeys").jsonArray.map { it.jsonPrimitive.content }
            "chapter" -> listOf(key)
            "question" -> listOf(subject.text("chapterKey"))
            else -> chapters.filter { chapter -> chapter.objects("knowledgePoints").any { point ->
                point.objects("facts").any { it.text("factKey") == key }
            } }.map { it.text("chapterKey") }
        }
        val bound = keys.map { chapterKey -> chapters.single { it.text("chapterKey") == chapterKey } }
        require(request.chapterBindings == bound.map { chapter ->
            ContentProfessionalChapterBinding(chapter.text("chapterKey"), chapter.text("contentRevision"), chapter.text("bodySha256"))
        }) { "review binding: current chapter bindings differ" }
        val books = root.objects("books").filter { book -> bound.any { it.text("bookKey") == book.text("bookKey") } }
        val questions = root.objects("questions").filter { it.text("chapterKey") in keys }
        return buildJsonObject {
            put("format", "content-professional-review-input")
            put("schemaVersion", 1)
            put("packageId", root.getValue("packageId"))
            put("scopeKey", request.scopeKey)
            put("subject", contentOnly(subject))
            put("books", JsonArray(books.map(::contentOnly)))
            put("chapters", JsonArray(bound.map(::contentOnly)))
            put("sources", root.getValue("sources"))
            put("questions", JsonArray(questions.map(::contentOnly)))
            put("resources", JsonArray(root.objects("resources").filter { it.text("kind") != "REVIEW" }))
        }
    }

    /** Reuses CJ2 and SHA256; the resulting digest is not a signed review record. */
    fun sha256(request: ContentProfessionalReviewRequest): String =
        ContentPackageCodec.sha256(ContentPackageCodec.canonicalJson(payload(request)).encodeToByteArray())

    private fun contentOnly(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it != "review" && it != "contentRevision" }
            .mapValues { (_, child) -> contentOnly(child) })
        is JsonArray -> JsonArray(value.map(::contentOnly))
        else -> value
    }

    private fun JsonObject.objects(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
