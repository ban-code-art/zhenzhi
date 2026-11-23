package com.example.myapplication.data.reader.content

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.*
import com.example.myapplication.data.reader.storage.*
import com.example.myapplication.data.reader.verifiedBuiltInProjection
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

data class ContentPackageRoomPublication(val versionId: String, val generation: Long, val packageVersion: Int)
class ContentPackageRoomPublisher(private val database: AppDatabase, private val storage: DocumentStorage, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun active(): ContentPackageRoomPublication? = database.withTransaction { state(database.readerJobDao().findPackage(PACKAGE_ID)) }
    private fun state(row: ContentPackageEntity?): ContentPackageRoomPublication? {
        if(row == null) return null
        if(row.status == "APPLIED") {
            require(row.version == 1 && row.manifestHash == ContentPackageCodec.LEGACY_MANIFEST_HASH) { "publication: unrecognized legacy package" }
            return null
        }
        require(row.status.matches(Regex("SCHEMA2_APPLIED:[1-9][0-9]*"))) { "publication: unknown package status" }
        val generation = requireNotNull(row.status.substringAfter(':').toLongOrNull()) { "publication: generation overflow" }
        require(row.manifestHash.matches(Regex("[0-9a-f]{64}")) && row.version > 0)
        return ContentPackageRoomPublication(row.manifestHash, generation, row.version)
    }
    suspend fun publish(store: ContentPackageVersionStore, versionId: String, expectedGeneration: Long, professionalVerifier: ContentProfessionalReviewVerifier? = null): ContentPackageRoomPublication {
        require(expectedGeneration >= 0 && expectedGeneration < Long.MAX_VALUE)
        currentCoroutineContext().ensureActive()
        // Only preflight crosses dispatchers. Room remains the sole commit boundary.
        val (input, root, projected) = withContext(Dispatchers.IO) {
            val workerContext = currentCoroutineContext()
            workerContext.ensureActive()
            val input = store.readVersion(versionId)
            val verifier = professionalVerifier?.let { external -> ContentProfessionalReviewVerifier { request ->
                workerContext.ensureActive()
                fun clone(value: JsonObject) = Json.parseToJsonElement(ContentPackageCodec.canonicalJson(value)).jsonObject
                val verified = external.isVerified(request.copy(subject=clone(request.subject),manifest=clone(request.manifest),review=clone(request.review),recordBytes=request.recordBytes.copyOf(),chapterBindings=request.chapterBindings.map { it.copy() }))
                workerContext.ensureActive()
                verified
            } }
            val validation = ContentPackageValidator().validate(input.manifestBytes,input.resources,ContentValidationMode.PRODUCTION,professionalReviewVerifier=verifier)
            require(validation.productionValid) { "publication: production gate ${validation.issues}" }
            val root = ContentPackageCodec.decode(input.manifestBytes)
            require(root.text("packageId") == PACKAGE_ID) { "publication: foreign package namespace" }
            workerContext.ensureActive()
            val projected = ContentPackageProjection.prepare(storage,input).associateBy { it.documentId }
            workerContext.ensureActive()
            Triple(input, root, projected)
        }
        val version = root["packageVersion"]!!.jsonPrimitive.int
        currentCoroutineContext().ensureActive()
        return database.withTransaction {
            val packages=database.readerJobDao(); val before=packages.findPackage(PACKAGE_ID)
            check((state(before)?.generation ?: 0) == expectedGeneration) { "publication: generation conflict" }
            require(before == null || version > before.version) { "publication: package downgrade or replay" }
            val now=clock(); val next=ContentPackageRoomPublication(versionId,expectedGeneration+1,version)
            val chapterDao=database.chapterDao(); val catalog=database.readerCatalogDao(); val books=database.courseBookDao()
            val bookObjects=root.objects("books").sortedBy { it["orderIndex"]!!.jsonPrimitive.int }
            val chapterObjects=root.objects("chapters").associateBy { it.text("chapterKey") }
            val ordered=bookObjects.flatMap { book -> book["chapterKeys"]!!.jsonArray.map { chapterObjects.getValue(it.jsonPrimitive.content) } }
            val ids=linkedMapOf<String,Long>()
            ordered.forEachIndexed { order, chapter ->
                currentCoroutineContext().ensureActive()
                val key=chapter.text("chapterKey"); val title=chapter.text("title"); val revision=chapter.text("contentRevision"); val docId="builtin:chapter:$key"
                val old=chapterDao.findByContentKey(key)
                val id=old?.id ?: chapterDao.insert(ChapterEntity(orderIndex=order,title=title,summary=chapter.text("summary"),contentKey=key,createdAt=now))
                if(old != null) check(chapterDao.updateMetadata(id,order,title,chapter.text("summary"),true)==1)
                ids[key]=id
                val projection=projected.getValue(docId)
                val verified=requireNotNull(storage.openRevision(docId,revision,true))
                check(verified.manifestSha256 == projection.manifestSha256)
                val body=storage.resolveFile(docId,revision,chapter.text("bodyPath")).absolutePath
                val oldDoc=catalog.findDocumentIncludingDeleted(docId)
                require(oldDoc == null || oldDoc.kind=="BUILTIN" && oldDoc.ownerUserId==null && oldDoc.legacyChapterId==id && oldDoc.deletedAt==null && oldDoc.importStatus=="READY") { "publication: document identity conflict" }
                if(oldDoc==null) catalog.insertDocument(ReaderDocumentEntity(id=docId,ownerUserId=null,kind="BUILTIN",legacyChapterId=id,format="HTML",title=title,author="内置课程",blobPath=body,fileSha256=chapter.text("bodySha256"),contentRevision=revision,extractorVersion=ContentPackageProjection.EXTRACTOR_VERSION,importStatus="READY",createdAt=now,updatedAt=now))
                else {
                    check(oldDoc.accessEpoch<Long.MAX_VALUE && oldDoc.metadataRevision<Long.MAX_VALUE)
                    database.openHelper.writableDatabase.execSQL("UPDATE reader_documents SET title=?, blob_path=?, file_sha256=?, content_revision=?, extractor_version=?, access_epoch=access_epoch+1, metadata_revision=metadata_revision+1, updated_at=? WHERE id=?",arrayOf(title,body,chapter.text("bodySha256"),revision,ContentPackageProjection.EXTRACTOR_VERSION,now,docId))
                }
                val previous=catalog.findContentRevision(docId,revision)
                val manifestPath=File(projection.directory,DocumentStorage.MANIFEST_FILE_NAME).absolutePath
                if(previous==null) {
                    catalog.insertContentRevision(ReaderContentRevisionEntity(docId,revision,chapter.text("bodySha256"),body,manifestPath,ContentPackageProjection.EXTRACTOR_VERSION,"READY",now))
                    catalog.insertSections(projection.manifest.sections.map { ReaderSectionEntity(it.id,docId,revision,orderIndex=it.orderIndex,title=it.title,resourceHref=it.resourceHref,canonicalPath=it.resourceHref,charCount=it.charCount) })
                    catalog.insertOutline(projection.manifest.outline.map { ReaderOutlineNodeEntity("$docId:$revision:${it.nodeKey}",docId,revision,it.nodeKey,orderIndex=it.orderIndex,title=it.title,targetSectionId=it.targetSectionId) })
                } else {
                    val existing = verifiedBuiltInProjection(storage, docId, previous, catalog.findSections(docId, revision))
                    require(existing.manifestSha256 == projection.manifestSha256) { "publication: historical projection conflict" }
                    val expectedOutline = projection.manifest.outline.map { ReaderOutlineNodeEntity("$docId:$revision:${it.nodeKey}",docId,revision,it.nodeKey,orderIndex=it.orderIndex,title=it.title,targetSectionId=it.targetSectionId) }
                    require(catalog.findOutline(docId, revision) == expectedOutline) { "publication: historical outline conflict" }
                }
            }
            bookObjects.forEach { book ->
                val key=book.text("bookKey"); val bookId="course:$key"
                val entity=CourseBookEntity(bookId,key,book.text("title"),book.text("summary"),orderIndex=book["orderIndex"]!!.jsonPrimitive.int,packageId=PACKAGE_ID,contentVersion=version.toLong(),reviewStatus=book["review"]!!.jsonObject.text("status"))
                val old=books.findBook(bookId)
                require(old==null || old.contentKey==key && old.packageId==PACKAGE_ID) { "publication: book identity conflict" }
                val allowedKeys = book["chapterKeys"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                require(books.listAllChapters(bookId).all { it.chapterKey in allowedKeys }) { "publication: unexpected book chapter mapping" }
                if(old==null) books.insertBook(entity) else check(books.updateBook(entity)==1)
                book["chapterKeys"]!!.jsonArray.forEachIndexed { index, value ->
                    val chapterKey=value.jsonPrimitive.content; val projection=projected.getValue("builtin:chapter:$chapterKey")
                    val mapping=CourseBookChapterEntity(bookId,chapterKey,orderIndex=index,documentId=projection.documentId,sectionId=projection.manifest.sections.single().id,legacyChapterId=ids.getValue(chapterKey))
                    if(books.listAllChapters(bookId).any { it.chapterKey==chapterKey }) check(books.updateChapter(mapping)==1) else books.insertChapters(listOf(mapping))
                }
            }
            // Parent row must precede binding inserts, but remains unobservable until transaction commit.
            if(before==null) packages.insertPackage(ContentPackageEntity(PACKAGE_ID,version,versionId,"SCHEMA2_APPLIED:${next.generation}",now))
            else check(packages.updatePackage(PACKAGE_ID,version,versionId,"SCHEMA2_APPLIED:${next.generation}",now)==1)
            val sql=database.openHelper.writableDatabase
            sql.execSQL("UPDATE questions SET enabled=0 WHERE id IN (SELECT question_id FROM content_question_bindings WHERE package_id=?) AND origin IN ('BUILTIN','CONTENT_PACKAGE') AND owner_user_id IS NULL",arrayOf(PACKAGE_ID))
            sql.execSQL("DELETE FROM content_question_bindings WHERE package_id=?",arrayOf(PACKAGE_ID))
            root.objects("questions").forEach { question ->
                val key=question.text("chapterKey"); val chapter=chapterObjects.getValue(key); val projection=projected.getValue("builtin:chapter:$key")
                val index=ContentPackageCanonical.parse(chapter,input.resources.getValue(chapter.text("bodyPath")))
                val evidence=question.objects("evidenceRefs").first()
                val runtime=index.runtimeBlocks.single { block -> block.blockId==index.blocks.single { it.blockId==evidence.text("blockId") }.runtimeBlockId }
                val quote=evidence.text("quote")
                val locator=ReaderLocatorCodec.encode(ReaderLocator.Reflow(projection.documentId,projection.manifest.sections.single().id,projection.revision,null,runtime.nativeLocator,quote,canonicalStart=evidence["canonicalStart"]!!.jsonPrimitive.int,canonicalEnd=evidence["canonicalEnd"]!!.jsonPrimitive.int,blockId=runtime.blockId))
                val objectivesByKey=chapter.objects("knowledgePoints").associate { it.text("knowledgePointKey") to it.text("objective") }
                val knowledgePointText=question["knowledgePointKeys"]!!.jsonArray.map { objectivesByKey.getValue(it.jsonPrimitive.content) }.distinct().joinToString("；")
                val options=question["options"]!!.jsonArray
                val answer=if(question.text("type")=="CHOICE") {
                    val supplied=question["answer"]!!.jsonArray.single().jsonPrimitive.content
                    val position=options.indexOf(JsonPrimitive(supplied)); if(position in 0..3) ('A'+position).toString() else supplied.also { require(it in listOf("A","B","C","D")) }
                } else question["answer"]!!.toString()
                val statement=sql.compileStatement("INSERT INTO questions(type,chapter_id,stem,options,answer,synonyms,knowledge_point,analysis,score,enabled,created_at,owner_user_id,origin,source_document_id,source_locator_json,source_excerpt,source_revision) VALUES(?,?,?,?,?,?,?,?,?,1,?,NULL,'CONTENT_PACKAGE',?,?,?,?)")
                val questionId=try {
                    statement.bindString(1,question.text("type"));statement.bindLong(2,ids.getValue(key));statement.bindString(3,question.text("stem"));statement.bindString(4,options.toString());statement.bindString(5,answer);if(question.text("type")=="ESSAY") statement.bindString(6,ContentPackageQuestionGrading.encode(question)) else statement.bindNull(6)
                    statement.bindString(7,knowledgePointText);statement.bindString(8,question.text("analysis"));statement.bindLong(9,question["score"]!!.jsonPrimitive.long);statement.bindLong(10,now);statement.bindString(11,projection.documentId);statement.bindString(12,locator);statement.bindString(13,quote);statement.bindString(14,projection.revision);statement.executeInsert()
                } finally {statement.close()}
                check(questionId>0)
                sql.execSQL("INSERT INTO content_question_bindings(package_id,question_key,question_id) VALUES(?,?,?)",arrayOf(PACKAGE_ID,question.text("questionKey"),questionId))
            }
            currentCoroutineContext().ensureActive()
            next
        }
    }
    private fun JsonObject.text(name:String)=getValue(name).jsonPrimitive.content
    private fun JsonObject.objects(name:String)=getValue(name).jsonArray.map { it.jsonObject }
    companion object { const val PACKAGE_ID="builtin-acupuncture" }
}
