package com.example.myapplication.data.encyclopedia

import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

enum class EncyclopediaPackErrorCode {
    MISSING_ASSET,
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_PACK_ID,
    CORRUPTED_JSON,
    INTEGRITY_MISMATCH,
    SCHEMA_CONTRACT_VIOLATION,
    REFERENTIAL_INTEGRITY_VIOLATION,
}

class EncyclopediaPackException(
    val code: EncyclopediaPackErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * 经学百科离线资料包只读输入抽象，便于接入 Android AssetManager、本地目录与内存测试夹具。
 */
interface EncyclopediaPackSource {
    fun exists(relativePath: String): Boolean
    fun openStream(relativePath: String): InputStream

    fun readBytes(relativePath: String): ByteArray =
        openStream(relativePath).use { it.readBytes() }
}

class AssetEncyclopediaPackSource(
    private val assetManager: AssetManager,
    private val rootPath: String = EncyclopediaPackConstants.ASSET_ROOT,
) : EncyclopediaPackSource {
    private val normalizedRoot = rootPath.trim('/').ifEmpty {
        throw IllegalArgumentException("rootPath must not be blank")
    }

    private fun resolveAssetPath(relativePath: String): String {
        val safe = validateSafeRelativePath(relativePath, "assetPath")
        return "$normalizedRoot/$safe"
    }

    override fun exists(relativePath: String): Boolean {
        val assetPath = resolveAssetPath(relativePath)
        return try {
            assetManager.open(assetPath).use { true }
        } catch (_: IOException) {
            false
        }
    }

    override fun openStream(relativePath: String): InputStream {
        val assetPath = resolveAssetPath(relativePath)
        return try {
            assetManager.open(assetPath)
        } catch (error: FileNotFoundException) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.MISSING_ASSET,
                "Missing encyclopedia asset: $assetPath",
                error,
            )
        } catch (error: IOException) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.MISSING_ASSET,
                "Unable to open encyclopedia asset: $assetPath",
                error,
            )
        }
    }
}

class DirectoryEncyclopediaPackSource(
    private val rootDir: File,
) : EncyclopediaPackSource {
    private val canonicalRoot = rootDir.canonicalFile

    private fun resolveFile(relativePath: String): File {
        val safe = validateSafeRelativePath(relativePath, "directoryPath")
        val target = File(canonicalRoot, safe).canonicalFile
        val rootWithSep = canonicalRoot.path.let {
            if (it.endsWith(File.separatorChar)) it else it + File.separatorChar
        }
        if (target.path != canonicalRoot.path && !target.path.startsWith(rootWithSep)) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                "Path escapes pack root: $relativePath",
            )
        }
        return target
    }

    override fun exists(relativePath: String): Boolean = resolveFile(relativePath).isFile

    override fun openStream(relativePath: String): InputStream {
        val file = resolveFile(relativePath)
        if (!file.isFile) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.MISSING_ASSET,
                "Missing encyclopedia file: ${file.path}",
            )
        }
        return FileInputStream(file)
    }
}

class InMemoryEncyclopediaPackSource(
    files: Map<String, ByteArray>,
) : EncyclopediaPackSource {
    private val entries: Map<String, ByteArray> = files.entries.associate { (k, v) ->
        validateSafeRelativePath(k, "inMemoryKey") to v.copyOf()
    }

    override fun exists(relativePath: String): Boolean {
        val safe = validateSafeRelativePath(relativePath, "inMemoryLookup")
        return safe in entries
    }

    override fun openStream(relativePath: String): InputStream {
        val safe = validateSafeRelativePath(relativePath, "inMemoryRead")
        val bytes = entries[safe] ?: throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.MISSING_ASSET,
            "Missing encyclopedia asset: $safe",
        )
        return ByteArrayInputStream(bytes)
    }
}

internal fun validateSafeRelativePath(raw: String, fieldPath: String): String {
    if (raw.isBlank()) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: relative path must not be blank",
        )
    }
    if (raw.startsWith("/") || raw.contains("\\") || raw.contains("?") || raw.contains("#") || raw.contains(":")) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: unsafe relative path \"$raw\"",
        )
    }
    val segments = raw.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: invalid path segment in \"$raw\"",
        )
    }
    if (!SAFE_RELATIVE_PATH_REGEX.matches(raw)) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: unsupported characters in relative path \"$raw\"",
        )
    }
    return raw
}

private val SAFE_RELATIVE_PATH_REGEX = Regex("^[A-Za-z0-9_./-]+$")
private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")
private val HTTPS_URL_REGEX = Regex("^https://\\S+$")
private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4E,
    0x47,
    0x0D,
    0x0A,
    0x1A,
    0x0A,
)

/**
 * 经学百科只读资料包解析与全量完整性校验器。
 */
object EncyclopediaPackLoader {
    private val jsonParser = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun load(source: EncyclopediaPackSource): EncyclopediaSnapshot {
        val manifestBytes = readNonEmptyAssetBytes(source, EncyclopediaPackConstants.MANIFEST_FILE)
        val manifestObj = parseJsonObject(manifestBytes, EncyclopediaPackConstants.MANIFEST_FILE)
        val manifest = parseAndValidateManifest(manifestObj)
        val manifestByPath = manifest.files.associateBy { it.path }

        val catalogObj = readVerifiedJsonObject(
            source,
            EncyclopediaPackConstants.CATALOG_FILE,
            manifestByPath,
        )
        val atlasObj = readVerifiedJsonObject(
            source,
            EncyclopediaPackConstants.ATLAS_FILE,
            manifestByPath,
        )
        val regionsObj = readVerifiedJsonObject(
            source,
            EncyclopediaPackConstants.REGIONS_FILE,
            manifestByPath,
        )
        val learningObj = readVerifiedJsonObject(
            source,
            EncyclopediaPackConstants.LEARNING_FILE,
            manifestByPath,
        )

        val (meridians, points) = parseAndValidateCatalog(catalogObj)
        val pointMap = points.associateBy { it.code }
        val meridianMap = meridians.associateBy { it.code }

        val atlas = parseAndValidateAtlas(atlasObj, meridianMap, pointMap, manifestByPath)
        val regions = parseAndValidateRegions(regionsObj, pointMap, manifestByPath)
        val learning = parseAndValidateLearning(learningObj, pointMap)

        verifyManifestCountsMatch(manifest.counts, meridians, points, atlas, regions, learning)
        verifyReferencedAndManifestedIllustrations(source, atlas, regions, manifest.files, manifestByPath)

        return EncyclopediaSnapshot(
            manifest = manifest,
            meridians = meridians,
            points = points,
            atlas = atlas,
            regions = regions,
            learning = learning,
        )
    }

    private fun readNonEmptyAssetBytes(source: EncyclopediaPackSource, path: String): ByteArray {
        val bytes = try {
            source.readBytes(path)
        } catch (error: EncyclopediaPackException) {
            throw error
        } catch (error: IOException) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.MISSING_ASSET,
                "Failed to read encyclopedia asset: $path",
                error,
            )
        }
        if (bytes.isEmpty()) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.INTEGRITY_MISMATCH,
                "Encyclopedia asset is empty: $path",
            )
        }
        return bytes
    }

    private fun readVerifiedJsonObject(
        source: EncyclopediaPackSource,
        path: String,
        manifestByPath: Map<String, EncyclopediaPackFileEntry>,
    ): JsonObject {
        val expected = manifestByPath[path] ?: throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
            "manifest.json is missing required JSON entry: $path",
        )
        val bytes = readNonEmptyAssetBytes(source, path)
        if (bytes.size.toLong() != expected.bytes) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.INTEGRITY_MISMATCH,
                "Byte count mismatch for $path: expected ${expected.bytes}, actual ${bytes.size}",
            )
        }
        val actualSha = sha256Hex(bytes)
        if (actualSha != expected.sha256) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.INTEGRITY_MISMATCH,
                "SHA-256 mismatch for $path: expected ${expected.sha256}, actual $actualSha",
            )
        }
        val obj = parseJsonObject(bytes, path)
        validatePackEnvelope(obj, path)
        return obj
    }

    private fun parseJsonObject(bytes: ByteArray, fileLabel: String): JsonObject {
        val utf8 = try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.CORRUPTED_JSON,
                "Invalid UTF-8 in $fileLabel",
                error,
            )
        }
        val element = try {
            jsonParser.parseToJsonElement(utf8)
        } catch (error: SerializationException) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.CORRUPTED_JSON,
                "Malformed JSON in $fileLabel: ${error.message}",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.CORRUPTED_JSON,
                "Malformed JSON in $fileLabel: ${error.message}",
                error,
            )
        }
        return element as? JsonObject ?: throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fileLabel: root element must be a JSON object",
        )
    }

    private fun validatePackEnvelope(obj: JsonObject, fileLabel: String): Pair<Int, String> {
        val schemaVersion = obj.requireInt("schemaVersion", "$fileLabel.schemaVersion")
        if (schemaVersion != EncyclopediaPackConstants.SUPPORTED_SCHEMA_VERSION) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                "Unsupported schemaVersion=$schemaVersion in $fileLabel (supported=${EncyclopediaPackConstants.SUPPORTED_SCHEMA_VERSION})",
            )
        }
        val packId = obj.requireNonBlankString("packId", "$fileLabel.packId")
        if (packId != EncyclopediaPackConstants.EXPECTED_PACK_ID) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.INVALID_PACK_ID,
                "Unexpected packId=\"$packId\" in $fileLabel (expected=${EncyclopediaPackConstants.EXPECTED_PACK_ID})",
            )
        }
        return schemaVersion to packId
    }

    private fun parseAndValidateManifest(obj: JsonObject): EncyclopediaManifest {
        val (schemaVersion, packId) = validatePackEnvelope(obj, EncyclopediaPackConstants.MANIFEST_FILE)
        val contentRevision = obj.requireSha256("contentRevision", "manifest.contentRevision")
        val coordinateVersion = obj.requireNonBlankString("coordinateVersion", "manifest.coordinateVersion")
        val artworkVersion = obj.requireNonBlankString("artworkVersion", "manifest.artworkVersion")

        val reviewObj = obj.requireObject("reviewStatus", "manifest.reviewStatus")
        val reviewStatus = EncyclopediaReviewStatus(
            overall = reviewObj.requireNonBlankString("overall", "manifest.reviewStatus.overall"),
            catalog = reviewObj.requireNonBlankString("catalog", "manifest.reviewStatus.catalog"),
            atlas = reviewObj.requireNonBlankString("atlas", "manifest.reviewStatus.atlas"),
            regions = reviewObj.requireNonBlankString("regions", "manifest.reviewStatus.regions"),
            learning = reviewObj.requireNonBlankString("learning", "manifest.reviewStatus.learning"),
            illustrations = reviewObj.requireNonBlankString("illustrations", "manifest.reviewStatus.illustrations"),
        )

        val attrObj = obj.requireObject("upstreamAttribution", "manifest.upstreamAttribution")
        val stdObj = attrObj.requireObject("standard", "manifest.upstreamAttribution.standard")
        val seedObj = attrObj.requireObject("coordinateSeed", "manifest.upstreamAttribution.coordinateSeed")
        val courseObj = attrObj.requireObject("courseware", "manifest.upstreamAttribution.courseware")
        val attribution = EncyclopediaUpstreamAttribution(
            standard = StandardAttribution(
                title = stdObj.requireNonBlankString("title", "manifest.upstreamAttribution.standard.title"),
                url = stdObj.requireHttpsUrl("url", "manifest.upstreamAttribution.standard.url"),
                scope = stdObj.requireNonBlankString("scope", "manifest.upstreamAttribution.standard.scope"),
            ),
            coordinateSeed = CoordinateSeedAttribution(
                title = seedObj.requireNonBlankString("title", "manifest.upstreamAttribution.coordinateSeed.title"),
                license = seedObj.requireNonBlankString("license", "manifest.upstreamAttribution.coordinateSeed.license"),
                url = seedObj.requireHttpsUrl("url", "manifest.upstreamAttribution.coordinateSeed.url"),
                licenseFileSha256 = seedObj.requireSha256(
                    "licenseFileSha256",
                    "manifest.upstreamAttribution.coordinateSeed.licenseFileSha256",
                ),
                note = seedObj.requireNonBlankString("note", "manifest.upstreamAttribution.coordinateSeed.note"),
            ),
            courseware = CoursewareAttribution(
                title = courseObj.requireNonBlankString("title", "manifest.upstreamAttribution.courseware.title"),
                url = courseObj.requireHttpsUrl("url", "manifest.upstreamAttribution.courseware.url"),
                scope = courseObj.requireNonBlankString("scope", "manifest.upstreamAttribution.courseware.scope"),
            ),
        )

        val countsObj = obj.requireObject("counts", "manifest.counts")
        val counts = EncyclopediaPackCounts(
            meridians = countsObj.requirePositiveInt("meridians", "manifest.counts.meridians"),
            points = countsObj.requirePositiveInt("points", "manifest.counts.points"),
            regionViews = countsObj.requirePositiveInt("regionViews", "manifest.counts.regionViews"),
            pointLearning = countsObj.requirePositiveInt("pointLearning", "manifest.counts.pointLearning"),
            symptomCategories = countsObj.requirePositiveInt("symptomCategories", "manifest.counts.symptomCategories"),
            symptoms = countsObj.requirePositiveInt("symptoms", "manifest.counts.symptoms"),
            associations = countsObj.requirePositiveInt("associations", "manifest.counts.associations"),
            atlasViews = countsObj.requirePositiveInt("atlasViews", "manifest.counts.atlasViews"),
            frontProjections = countsObj.requirePositiveInt("frontProjections", "manifest.counts.frontProjections"),
            backProjections = countsObj.requirePositiveInt("backProjections", "manifest.counts.backProjections"),
            sideProjections = countsObj.requirePositiveInt("sideProjections", "manifest.counts.sideProjections"),
            uniqueProjectedPoints = countsObj.requirePositiveInt(
                "uniqueProjectedPoints",
                "manifest.counts.uniqueProjectedPoints",
            ),
            occludedPoints = countsObj.requirePositiveInt("occludedPoints", "manifest.counts.occludedPoints"),
            illustrations = countsObj.requirePositiveInt("illustrations", "manifest.counts.illustrations"),
        )

        val filesArr = obj.requireArray("files", "manifest.files")
        if (filesArr.isEmpty()) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                "manifest.files must not be empty",
            )
        }
        val seenPaths = HashSet<String>()
        var previousPath: String? = null
        val files = filesArr.mapIndexed { idx, el ->
            val entryObj = el.asObject("manifest.files[$idx]")
            val path = validateSafeRelativePath(
                entryObj.requireNonBlankString("path", "manifest.files[$idx].path"),
                "manifest.files[$idx].path",
            )
            if (!seenPaths.add(path)) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                    "Duplicate file entry in manifest.files: $path",
                )
            }
            val prev = previousPath
            if (prev != null && path < prev) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                    "manifest.files must be sorted lexicographically ($prev appeared before $path)",
                )
            }
            previousPath = path
            val sha256 = entryObj.requireSha256("sha256", "manifest.files[$idx].sha256")
            val bytes = entryObj.requirePositiveLong("bytes", "manifest.files[$idx].bytes")
            EncyclopediaPackFileEntry(path = path, sha256 = sha256, bytes = bytes)
        }

        val expectedRevisionInput = files.joinToString(separator = "") { "${it.path}:${it.sha256}:${it.bytes}\n" }
        val expectedRevision = sha256Hex(expectedRevisionInput.toByteArray(Charsets.UTF_8))
        if (contentRevision != expectedRevision) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.INTEGRITY_MISMATCH,
                "manifest.contentRevision mismatch: expected $expectedRevision, actual $contentRevision",
            )
        }

        return EncyclopediaManifest(
            schemaVersion = schemaVersion,
            packId = packId,
            contentRevision = contentRevision,
            coordinateVersion = coordinateVersion,
            artworkVersion = artworkVersion,
            reviewStatus = reviewStatus,
            upstreamAttribution = attribution,
            counts = counts,
            files = files,
        )
    }

    private fun parseAndValidateCatalog(obj: JsonObject): Pair<List<Meridian>, List<Acupoint>> {
        val meridiansArr = obj.requireArray("meridians", "catalog.meridians")
        if (meridiansArr.size != EncyclopediaPackConstants.EXPECTED_MERIDIAN_COUNT) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Expected ${EncyclopediaPackConstants.EXPECTED_MERIDIAN_COUNT} meridians, got ${meridiansArr.size}",
            )
        }

        val seenMeridians = HashSet<String>()
        val meridians = meridiansArr.mapIndexed { idx, el ->
            val mObj = el.asObject("catalog.meridians[$idx]")
            val code = mObj.requireNonBlankString("code", "catalog.meridians[$idx].code")
            if (!seenMeridians.add(code)) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Duplicate meridian code in catalog.meridians: $code",
                )
            }
            val order = mObj.requirePositiveInt("order", "catalog.meridians[$code].order")
            if (order != idx + 1) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                    "catalog.meridians[$code].order expected ${idx + 1}, got $order",
                )
            }
            Meridian(
                code = code,
                order = order,
                shortName = mObj.requireNonBlankString("shortName", "catalog.meridians[$code].shortName"),
                name = mObj.requireNonBlankString("name", "catalog.meridians[$code].name"),
                pointCount = mObj.requirePositiveInt("pointCount", "catalog.meridians[$code].pointCount"),
                direction = mObj.requireNonBlankString("direction", "catalog.meridians[$code].direction"),
                description = mObj.optionalNonBlankString("description", "catalog.meridians[$code].description"),
            )
        }

        val declaredSum = meridians.sumOf { it.pointCount }
        if (declaredSum != EncyclopediaPackConstants.EXPECTED_POINT_COUNT) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Sum of meridian.pointCount must be ${EncyclopediaPackConstants.EXPECTED_POINT_COUNT}, got $declaredSum",
            )
        }

        val pointsArr = obj.requireArray("points", "catalog.points")
        if (pointsArr.size != EncyclopediaPackConstants.EXPECTED_POINT_COUNT) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Expected ${EncyclopediaPackConstants.EXPECTED_POINT_COUNT} points, got ${pointsArr.size}",
            )
        }

        val meridianByCode = meridians.associateBy { it.code }
        val seenPoints = HashSet<String>()
        val countPerMeridian = meridians.associate { it.code to 0 }.toMutableMap()

        val points = pointsArr.mapIndexed { idx, el ->
            val pObj = el.asObject("catalog.points[$idx]")
            val code = pObj.requireNonBlankString("code", "catalog.points[$idx].code")
            if (!seenPoints.add(code)) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Duplicate point code in catalog.points: $code",
                )
            }
            val meridianCode = pObj.requireNonBlankString("meridianCode", "catalog.points[$code].meridianCode")
            if (meridianCode !in meridianByCode) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Point $code references unknown meridianCode \"$meridianCode\"",
                )
            }
            val orderInMeridian = pObj.requirePositiveInt("orderInMeridian", "catalog.points[$code].orderInMeridian")
            val nextSeq = countPerMeridian.getValue(meridianCode) + 1
            if (orderInMeridian != nextSeq || code != "$meridianCode$orderInMeridian") {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Point $code has invalid meridian sequence $orderInMeridian (expected $meridianCode$nextSeq)",
                )
            }
            countPerMeridian[meridianCode] = nextSeq

            Acupoint(
                code = code,
                meridianCode = meridianCode,
                orderInMeridian = orderInMeridian,
                name = pObj.requireNonBlankString("name", "catalog.points[$code].name"),
                pinyin = pObj.requireNonBlankString("pinyin", "catalog.points[$code].pinyin"),
                clause = pObj.requireNonBlankString("clause", "catalog.points[$code].clause"),
                pdfPage = pObj.requirePositiveInt("pdfPage", "catalog.points[$code].pdfPage"),
                region = pObj.requireNonBlankString("region", "catalog.points[$code].region"),
                special = pObj.requireNonBlankString("special", "catalog.points[$code].special"),
                location = pObj.requireNonBlankString("location", "catalog.points[$code].location"),
                projectionNote = pObj.optionalNonBlankString("projectionNote", "catalog.points[$code].projectionNote"),
            )
        }

        for (meridian in meridians) {
            val actual = countPerMeridian.getValue(meridian.code)
            if (actual != meridian.pointCount) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Meridian ${meridian.code} declared ${meridian.pointCount} points, found $actual",
                )
            }
        }

        return meridians to points
    }

    private fun parseAndValidateAtlas(
        obj: JsonObject,
        meridianByCode: Map<String, Meridian>,
        pointByCode: Map<String, Acupoint>,
        manifestByPath: Map<String, EncyclopediaPackFileEntry>,
    ): AtlasData {
        val frameObj = obj.requireObject("bodyFrame", "atlas.bodyFrame")
        val bodyFrame = EncyclopediaSize(
            width = frameObj.requirePositiveFiniteDouble("width", "atlas.bodyFrame.width"),
            height = frameObj.requirePositiveFiniteDouble("height", "atlas.bodyFrame.height"),
        )
        if (bodyFrame.width != 340.0 || bodyFrame.height != 480.0) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                "atlas.bodyFrame must be 340x480, got ${bodyFrame.width}x${bodyFrame.height}",
            )
        }

        val rectObj = obj.requireObject("artworkRect", "atlas.artworkRect")
        val artworkRect = EncyclopediaRect(
            x = rectObj.requireFiniteDouble("x", "atlas.artworkRect.x"),
            y = rectObj.requireFiniteDouble("y", "atlas.artworkRect.y"),
            width = rectObj.requirePositiveFiniteDouble("width", "atlas.artworkRect.width"),
            height = rectObj.requirePositiveFiniteDouble("height", "atlas.artworkRect.height"),
        )

        val viewsArr = obj.requireArray("views", "atlas.views")
        if (viewsArr.size != EncyclopediaPackConstants.EXPECTED_ATLAS_VIEW_COUNT) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Expected ${EncyclopediaPackConstants.EXPECTED_ATLAS_VIEW_COUNT} atlas views, got ${viewsArr.size}",
            )
        }
        val seenViews = HashSet<AtlasViewId>()
        val views = viewsArr.mapIndexed { idx, el ->
            val vObj = el.asObject("atlas.views[$idx]")
            val idRaw = vObj.requireNonBlankString("id", "atlas.views[$idx].id")
            val id = AtlasViewId.fromWire(idRaw) ?: throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                "atlas.views[$idx].id: unknown view \"$idRaw\"",
            )
            if (!seenViews.add(id)) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                    "Duplicate atlas view: $idRaw",
                )
            }
            val artwork = validateIllustrationPath(
                vObj.requireNonBlankString("artwork", "atlas.views[$idRaw].artwork"),
                "atlas.views[$idRaw].artwork",
                manifestByPath,
            )
            AtlasView(
                id = id,
                name = vObj.requireNonBlankString("name", "atlas.views[$idRaw].name"),
                artwork = artwork,
            )
        }

        val notesObj = obj.requireObject("projectionNotes", "atlas.projectionNotes")
        val projectionNotes = linkedMapOf<AtlasProjectionRating, String>()
        for (rating in AtlasProjectionRating.entries) {
            projectionNotes[rating] = notesObj.requireNonBlankString(
                rating.wireKey,
                "atlas.projectionNotes.${rating.wireKey}",
            )
        }

        val projObj = obj.requireObject("projections", "atlas.projections")
        val projectionsByViewAndMeridian = linkedMapOf<AtlasViewId, Map<String, List<AtlasProjectionPoint>>>()
        val projectedPointCodes = HashSet<String>()
        val expectedCounts = mapOf(
            AtlasViewId.FRONT to EncyclopediaPackConstants.EXPECTED_FRONT_PROJECTION_COUNT,
            AtlasViewId.BACK to EncyclopediaPackConstants.EXPECTED_BACK_PROJECTION_COUNT,
            AtlasViewId.SIDE to EncyclopediaPackConstants.EXPECTED_SIDE_PROJECTION_COUNT,
        )

        for (viewId in AtlasViewId.entries) {
            val viewChannelsObj = projObj.requireObject(viewId.wireKey, "atlas.projections.${viewId.wireKey}")
            val seenInView = HashSet<String>()
            val channelMap = linkedMapOf<String, List<AtlasProjectionPoint>>()
            var viewCount = 0

            for ((channelCode, rowsEl) in viewChannelsObj) {
                if (channelCode !in meridianByCode) {
                    throw EncyclopediaPackException(
                        EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                        "atlas.projections.${viewId.wireKey} references unknown meridian \"$channelCode\"",
                    )
                }
                val rowsArr = rowsEl as? JsonArray ?: throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                    "atlas.projections.${viewId.wireKey}.$channelCode must be an array",
                )
                if (rowsArr.isEmpty()) {
                    throw EncyclopediaPackException(
                        EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                        "atlas.projections.${viewId.wireKey}.$channelCode must not be empty",
                    )
                }
                val parsedRows = rowsArr.mapIndexed { idx, rowEl ->
                    val rowObj = rowEl.asObject("atlas.projections.${viewId.wireKey}.$channelCode[$idx]")
                    val pointCode = rowObj.requireNonBlankString(
                        "pointCode",
                        "atlas.projections.${viewId.wireKey}.$channelCode[$idx].pointCode",
                    )
                    val point = pointByCode[pointCode] ?: throw EncyclopediaPackException(
                        EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                        "Dangling pointCode \"$pointCode\" in atlas.projections.${viewId.wireKey}.$channelCode",
                    )
                    if (point.meridianCode != channelCode) {
                        throw EncyclopediaPackException(
                            EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                            "Point \"$pointCode\" in atlas.projections.${viewId.wireKey}.$channelCode belongs to \"${point.meridianCode}\"",
                        )
                    }
                    if (!seenInView.add(pointCode)) {
                        throw EncyclopediaPackException(
                            EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                            "Duplicate pointCode \"$pointCode\" in atlas.projections.${viewId.wireKey}",
                        )
                    }
                    val x = rowObj.requireFiniteDouble("x", "atlas.projections.${viewId.wireKey}.$pointCode.x")
                    val y = rowObj.requireFiniteDouble("y", "atlas.projections.${viewId.wireKey}.$pointCode.y")
                    if (x < 0.0 || x > bodyFrame.width || y < 0.0 || y > bodyFrame.height) {
                        throw EncyclopediaPackException(
                            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                            "Projection coordinates out of bodyFrame bounds for $pointCode (${viewId.wireKey}): ($x, $y)",
                        )
                    }
                    val ratingRaw = rowObj.requireNonBlankString(
                        "rating",
                        "atlas.projections.${viewId.wireKey}.$pointCode.rating",
                    )
                    val rating = AtlasProjectionRating.fromWire(ratingRaw) ?: throw EncyclopediaPackException(
                        EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                        "Invalid projection rating \"$ratingRaw\" for $pointCode (${viewId.wireKey})",
                    )
                    projectedPointCodes.add(pointCode)
                    viewCount += 1
                    AtlasProjectionPoint(
                        pointCode = pointCode,
                        meridianCode = channelCode,
                        view = viewId,
                        x = x,
                        y = y,
                        rating = rating,
                    )
                }
                channelMap[channelCode] = parsedRows
            }

            val expected = expectedCounts.getValue(viewId)
            if (viewCount != expected) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "atlas.projections.${viewId.wireKey} count mismatch: expected $expected, got $viewCount",
                )
            }
            projectionsByViewAndMeridian[viewId] = channelMap
        }

        if (projectedPointCodes.size != EncyclopediaPackConstants.EXPECTED_UNIQUE_PROJECTED_POINT_COUNT) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Expected ${EncyclopediaPackConstants.EXPECTED_UNIQUE_PROJECTED_POINT_COUNT} unique projected points, got ${projectedPointCodes.size}",
            )
        }

        val occludedArr = obj.requireArray("occluded", "atlas.occluded")
        if (occludedArr.size != EncyclopediaPackConstants.EXPECTED_OCCLUDED_POINT_COUNT) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Expected ${EncyclopediaPackConstants.EXPECTED_OCCLUDED_POINT_COUNT} occluded points, got ${occludedArr.size}",
            )
        }
        val occludedCodes = HashSet<String>()
        val occluded = occludedArr.mapIndexed { idx, el ->
            val oObj = el.asObject("atlas.occluded[$idx]")
            val pointCode = oObj.requireNonBlankString("pointCode", "atlas.occluded[$idx].pointCode")
            if (pointCode !in pointByCode) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Dangling occluded pointCode \"$pointCode\"",
                )
            }
            if (!occludedCodes.add(pointCode)) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Duplicate occluded pointCode \"$pointCode\"",
                )
            }
            if (pointCode in projectedPointCodes) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Occluded pointCode \"$pointCode\" also appears in atlas.projections",
                )
            }
            val viewRaw = oObj.requireNonBlankString("view", "atlas.occluded[$pointCode].view")
            val view = AtlasViewId.fromWire(viewRaw) ?: throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                "Invalid occluded view \"$viewRaw\" for $pointCode",
            )
            AtlasOccludedPoint(
                pointCode = pointCode,
                view = view,
                regionLabel = oObj.requireNonBlankString("regionLabel", "atlas.occluded[$pointCode].regionLabel"),
            )
        }

        val uncovered = pointByCode.keys - projectedPointCodes - occludedCodes
        if (uncovered.isNotEmpty()) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Points missing from both atlas projections and occluded list: ${uncovered.sorted().joinToString(", ")}",
            )
        }

        val lungObj = obj.requireObject("lungFront", "atlas.lungFront")
        val featuredPointCodes = lungObj.requireStringList("featuredPointCodes", "atlas.lungFront.featuredPointCodes")
        featuredPointCodes.forEach { code ->
            if (code !in pointByCode) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "atlas.lungFront.featuredPointCodes references unknown point \"$code\"",
                )
            }
        }
        val wristPointCodes = lungObj.requireStringList("wristPointCodes", "atlas.lungFront.wristPointCodes")
        wristPointCodes.forEach { code ->
            if (code !in pointByCode) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "atlas.lungFront.wristPointCodes references unknown point \"$code\"",
                )
            }
        }
        val lungLabelsArr = lungObj.requireArray("labels", "atlas.lungFront.labels")
        if (lungLabelsArr.size != 11) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "atlas.lungFront.labels expected 11 entries, got ${lungLabelsArr.size}",
            )
        }
        val lungLabels = lungLabelsArr.mapIndexed { idx, el ->
            val lObj = el.asObject("atlas.lungFront.labels[$idx]")
            val code = lObj.requireNonBlankString("pointCode", "atlas.lungFront.labels[$idx].pointCode")
            if (code !in pointByCode) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "atlas.lungFront.labels[$idx] references unknown point \"$code\"",
                )
            }
            LungFrontLabelAnchor(
                pointCode = code,
                x = lObj.requireFiniteDouble("x", "atlas.lungFront.labels[$code].x"),
                y = lObj.requireFiniteDouble("y", "atlas.lungFront.labels[$code].y"),
                labelX = lObj.requireFiniteDouble("labelX", "atlas.lungFront.labels[$code].labelX"),
                labelY = lObj.requireFiniteDouble("labelY", "atlas.lungFront.labels[$code].labelY"),
            )
        }

        val lungFront = LungFrontSpec(
            mainPath = lungObj.requireNonBlankString("mainPath", "atlas.lungFront.mainPath"),
            directionPath = lungObj.requireNonBlankString("directionPath", "atlas.lungFront.directionPath"),
            branchPath = lungObj.requireNonBlankString("branchPath", "atlas.lungFront.branchPath"),
            featuredPointCodes = featuredPointCodes,
            wristPointCodes = wristPointCodes,
            labels = lungLabels,
            textbookUrl = lungObj.requireHttpsUrl("textbookUrl", "atlas.lungFront.textbookUrl"),
            standardUrl = lungObj.requireHttpsUrl("standardUrl", "atlas.lungFront.standardUrl"),
        )

        return AtlasData(
            bodyFrame = bodyFrame,
            artworkRect = artworkRect,
            views = views,
            projectionNotes = projectionNotes,
            projectionsByViewAndMeridian = projectionsByViewAndMeridian,
            occluded = occluded,
            lungFront = lungFront,
        )
    }

    private fun parseAndValidateRegions(
        obj: JsonObject,
        pointByCode: Map<String, Acupoint>,
        manifestByPath: Map<String, EncyclopediaPackFileEntry>,
    ): RegionCatalog {
        val frameObj = obj.requireObject("regionFrame", "regions.regionFrame")
        val regionFrame = EncyclopediaSize(
            width = frameObj.requirePositiveFiniteDouble("width", "regions.regionFrame.width"),
            height = frameObj.requirePositiveFiniteDouble("height", "regions.regionFrame.height"),
        )
        if (regionFrame.width != 360.0 || regionFrame.height != 200.0) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                "regions.regionFrame must be 360x200, got ${regionFrame.width}x${regionFrame.height}",
            )
        }

        val rectObj = obj.requireObject("artworkRect", "regions.artworkRect")
        val artworkRect = EncyclopediaRect(
            x = rectObj.requireFiniteDouble("x", "regions.artworkRect.x"),
            y = rectObj.requireFiniteDouble("y", "regions.artworkRect.y"),
            width = rectObj.requirePositiveFiniteDouble("width", "regions.artworkRect.width"),
            height = rectObj.requirePositiveFiniteDouble("height", "regions.artworkRect.height"),
        )

        val regionsArr = obj.requireArray("regions", "regions.regions")
        if (regionsArr.size != EncyclopediaPackConstants.EXPECTED_POINT_COUNT) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Expected ${EncyclopediaPackConstants.EXPECTED_POINT_COUNT} region entries, got ${regionsArr.size}",
            )
        }

        val seenCodes = HashSet<String>()
        val regions = regionsArr.mapIndexed { idx, el ->
            val rObj = el.asObject("regions.regions[$idx]")
            val pointCode = rObj.requireNonBlankString("pointCode", "regions.regions[$idx].pointCode")
            if (pointCode !in pointByCode) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Dangling pointCode \"$pointCode\" in regions.regions[$idx]",
                )
            }
            if (!seenCodes.add(pointCode)) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Duplicate pointCode \"$pointCode\" in regions.regions",
                )
            }

            val artwork = validateIllustrationPath(
                rObj.requireNonBlankString("artwork", "regions.regions[$pointCode].artwork"),
                "regions.regions[$pointCode].artwork",
                manifestByPath,
            )
            val markerObj = rObj.requireObject("marker", "regions.regions[$pointCode].marker")
            val marker = RegionMarker(
                x = markerObj.requireFiniteDouble("x", "regions.regions[$pointCode].marker.x"),
                y = markerObj.requireFiniteDouble("y", "regions.regions[$pointCode].marker.y"),
                labelX = markerObj.requireFiniteDouble("labelX", "regions.regions[$pointCode].marker.labelX"),
                labelY = markerObj.requireFiniteDouble("labelY", "regions.regions[$pointCode].marker.labelY"),
            )

            val groupPoints = rObj.requireStringList("points", "regions.regions[$pointCode].points")
            if (groupPoints.isEmpty() || pointCode !in groupPoints) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "regions.regions[$pointCode].points must contain \"$pointCode\"",
                )
            }
            for (memberCode in groupPoints) {
                if (memberCode !in pointByCode) {
                    throw EncyclopediaPackException(
                        EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                        "regions.regions[$pointCode].points references unknown point \"$memberCode\"",
                    )
                }
            }

            val labelsArr = rObj.requireArray("labels", "regions.regions[$pointCode].labels")
            if (labelsArr.isEmpty()) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                    "regions.regions[$pointCode].labels must not be empty",
                )
            }
            val labels = labelsArr.mapIndexed { lIdx, lEl ->
                val lObj = lEl.asObject("regions.regions[$pointCode].labels[$lIdx]")
                RegionLandmarkLabel(
                    text = lObj.requireNonBlankString("text", "regions.regions[$pointCode].labels[$lIdx].text"),
                    x = lObj.requireFiniteDouble("x", "regions.regions[$pointCode].labels[$lIdx].x"),
                    y = lObj.requireFiniteDouble("y", "regions.regions[$pointCode].labels[$lIdx].y"),
                    leaderPath = lObj.requireString(
                        "leaderPath",
                        "regions.regions[$pointCode].labels[$lIdx].leaderPath",
                    ),
                )
            }

            RegionView(
                pointCode = pointCode,
                view = rObj.requireNonBlankString("view", "regions.regions[$pointCode].view"),
                title = rObj.requireNonBlankString("title", "regions.regions[$pointCode].title"),
                pose = rObj.requireNonBlankString("pose", "regions.regions[$pointCode].pose"),
                artwork = artwork,
                marker = marker,
                points = groupPoints,
                labels = labels,
                guide = rObj.optionalNonBlankString("guide", "regions.regions[$pointCode].guide"),
                hint = rObj.optionalNonBlankString("hint", "regions.regions[$pointCode].hint"),
                focus = rObj.optionalNonBlankString("focus", "regions.regions[$pointCode].focus"),
                compactLabels = rObj.requireBoolean("compactLabels", "regions.regions[$pointCode].compactLabels"),
                location = rObj.requireNonBlankString("location", "regions.regions[$pointCode].location"),
            )
        }

        val missingRegions = pointByCode.keys - seenCodes
        if (missingRegions.isNotEmpty()) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Missing region entries for points: ${missingRegions.sorted().joinToString(", ")}",
            )
        }

        // Verify shared artwork paths have distinct per-point markers
        val markersByArtwork = HashMap<String, MutableMap<Pair<Double, Double>, String>>()
        for (region in regions) {
            val coordMap = markersByArtwork.getOrPut(region.artwork) { HashMap() }
            val existing = coordMap.put(region.marker.x to region.marker.y, region.pointCode)
            if (existing != null) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Shared artwork ${region.artwork} has duplicate marker coordinates (${region.marker.x}, ${region.marker.y}) for $existing and ${region.pointCode}",
                )
            }
        }

        return RegionCatalog(
            regionFrame = regionFrame,
            artworkRect = artworkRect,
            regions = regions,
        )
    }

    private fun parseAndValidateLearning(
        obj: JsonObject,
        pointByCode: Map<String, Acupoint>,
    ): LearningData {
        val refsObj = obj.requireObject("references", "learning.references")
        val references = LearningReferences(
            standardPdfUrl = refsObj.requireHttpsUrl("standardPdfUrl", "learning.references.standardPdfUrl"),
            acupunctureCourseBaseUrl = refsObj.requireHttpsUrl(
                "acupunctureCourseBaseUrl",
                "learning.references.acupunctureCourseBaseUrl",
            ),
            lungTextbookUrl = refsObj.requireHttpsUrl("lungTextbookUrl", "learning.references.lungTextbookUrl"),
            studyHandbookUrl = refsObj.requireHttpsUrl("studyHandbookUrl", "learning.references.studyHandbookUrl"),
            lungTextbookPreviewUrl = refsObj.requireHttpsUrl(
                "lungTextbookPreviewUrl",
                "learning.references.lungTextbookPreviewUrl",
            ),
        )

        val plArr = obj.requireArray("pointLearning", "learning.pointLearning")
        if (plArr.size != EncyclopediaPackConstants.EXPECTED_POINT_COUNT) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Expected ${EncyclopediaPackConstants.EXPECTED_POINT_COUNT} pointLearning entries, got ${plArr.size}",
            )
        }
        val seenPlCodes = HashSet<String>()
        val pointLearning = plArr.mapIndexed { idx, el ->
            val plObj = el.asObject("learning.pointLearning[$idx]")
            val pointCode = plObj.requireNonBlankString("pointCode", "learning.pointLearning[$idx].pointCode")
            if (pointCode !in pointByCode) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Dangling pointCode \"$pointCode\" in learning.pointLearning[$idx]",
                )
            }
            if (!seenPlCodes.add(pointCode)) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Duplicate pointLearning entry for \"$pointCode\"",
                )
            }
            val groupsArr = plObj.requireArray("groups", "learning.pointLearning[$pointCode].groups")
            if (groupsArr.isEmpty()) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                    "learning.pointLearning[$pointCode].groups must not be empty",
                )
            }
            val groups = groupsArr.mapIndexed { gIdx, gEl ->
                parseStudySectionEntry(gEl, "learning.pointLearning[$pointCode].groups[$gIdx]")
            }
            val source = parseSourceRef(
                plObj.requireObject("source", "learning.pointLearning[$pointCode].source"),
                "learning.pointLearning[$pointCode].source",
            )
            PointLearning(
                pointCode = pointCode,
                heading = plObj.optionalNonBlankString("heading", "learning.pointLearning[$pointCode].heading"),
                groups = groups,
                source = source,
            )
        }
        val missingPl = pointByCode.keys - seenPlCodes
        if (missingPl.isNotEmpty()) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Missing pointLearning entries for points: ${missingPl.sorted().joinToString(", ")}",
            )
        }
        val st17 = pointLearning.firstOrNull { it.pointCode == "ST17" }
        if (st17 == null || st17.heading != EncyclopediaPackConstants.ST17_HEADING) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                "ST17 pointLearning.heading must be \"${EncyclopediaPackConstants.ST17_HEADING}\", got \"${st17?.heading}\"",
            )
        }

        val categories = obj.requireStringList("symptomCategories", "learning.symptomCategories")
        if (categories.size != EncyclopediaPackConstants.EXPECTED_SYMPTOM_CATEGORY_COUNT ||
            categories.toSet().size != EncyclopediaPackConstants.EXPECTED_SYMPTOM_CATEGORY_COUNT
        ) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Expected ${EncyclopediaPackConstants.EXPECTED_SYMPTOM_CATEGORY_COUNT} unique symptomCategories, got ${categories.size}",
            )
        }
        val categorySet = categories.toSet()

        val symptomsArr = obj.requireArray("symptoms", "learning.symptoms")
        if (symptomsArr.size != EncyclopediaPackConstants.EXPECTED_SYMPTOM_COUNT) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Expected ${EncyclopediaPackConstants.EXPECTED_SYMPTOM_COUNT} symptoms, got ${symptomsArr.size}",
            )
        }

        val seenSymptomIds = HashSet<String>()
        val usedCategories = HashSet<String>()
        var totalLinks = 0

        val symptoms = symptomsArr.mapIndexed { idx, el ->
            val sObj = el.asObject("learning.symptoms[$idx]")
            val id = sObj.requireNonBlankString("id", "learning.symptoms[$idx].id")
            if (!seenSymptomIds.add(id)) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Duplicate symptom id: \"$id\"",
                )
            }
            val category = sObj.requireNonBlankString("category", "learning.symptoms[$id].category")
            if (category !in categorySet) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "Symptom \"$id\" references unknown category \"$category\"",
                )
            }
            usedCategories.add(category)

            val aliases = sObj.requireStringList("aliases", "learning.symptoms[$id].aliases")
            val signalsArr = sObj.requireArray("signals", "learning.symptoms[$id].signals")
            if (signalsArr.isEmpty()) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                    "learning.symptoms[$id].signals must not be empty",
                )
            }
            val signals = signalsArr.mapIndexed { pIdx, pEl ->
                parseStudySectionEntry(pEl, "learning.symptoms[$id].signals[$pIdx]")
            }
            val cluesArr = sObj.requireArray("clues", "learning.symptoms[$id].clues")
            if (cluesArr.isEmpty()) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                    "learning.symptoms[$id].clues must not be empty",
                )
            }
            val clues = cluesArr.mapIndexed { cIdx, cEl ->
                parseStudySectionEntry(cEl, "learning.symptoms[$id].clues[$cIdx]")
            }

            val source = parseSourceRef(
                sObj.requireObject("source", "learning.symptoms[$id].source"),
                "learning.symptoms[$id].source",
            )
            val additionalSource = sObj.optionalObject("additionalSource", "learning.symptoms[$id].additionalSource")
                ?.let { parseSourceRef(it, "learning.symptoms[$id].additionalSource") }

            val linksArr = sObj.requireArray("links", "learning.symptoms[$id].links")
            if (linksArr.isEmpty()) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                    "learning.symptoms[$id].links must not be empty",
                )
            }
            val seenPointsInSymptom = HashSet<String>()
            val links = linksArr.mapIndexed { lIdx, lEl ->
                val lObj = lEl.asObject("learning.symptoms[$id].links[$lIdx]")
                val pointCode = lObj.requireNonBlankString(
                    "pointCode",
                    "learning.symptoms[$id].links[$lIdx].pointCode",
                )
                if (pointCode !in pointByCode || pointCode !in seenPlCodes) {
                    throw EncyclopediaPackException(
                        EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                        "Dangling pointCode \"$pointCode\" in symptom \"$id\" link[$lIdx]",
                    )
                }
                if (!seenPointsInSymptom.add(pointCode)) {
                    throw EncyclopediaPackException(
                        EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                        "Duplicate pointCode \"$pointCode\" inside symptom \"$id\"",
                    )
                }
                val reason = lObj.requireNonBlankString("reason", "learning.symptoms[$id].links[$lIdx].reason")
                val dedicatedSource = lObj.optionalObject(
                    "source",
                    "learning.symptoms[$id].links[$lIdx].source",
                )?.let { parseSourceRef(it, "learning.symptoms[$id].links[$lIdx].source") }
                totalLinks += 1
                SymptomPointAssociation(
                    symptomId = id,
                    pointCode = pointCode,
                    reason = reason,
                    dedicatedSource = dedicatedSource,
                )
            }

            SymptomLesson(
                id = id,
                name = sObj.requireNonBlankString("name", "learning.symptoms[$id].name"),
                category = category,
                aliases = aliases,
                summary = sObj.requireNonBlankString("summary", "learning.symptoms[$id].summary"),
                signals = signals,
                clues = clues,
                source = source,
                additionalSource = additionalSource,
                links = links,
            )
        }

        if (usedCategories.size != EncyclopediaPackConstants.EXPECTED_SYMPTOM_CATEGORY_COUNT) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Expected all ${EncyclopediaPackConstants.EXPECTED_SYMPTOM_CATEGORY_COUNT} symptom categories to be used, got ${usedCategories.size}",
            )
        }
        if (totalLinks != EncyclopediaPackConstants.EXPECTED_ASSOCIATION_COUNT) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Expected ${EncyclopediaPackConstants.EXPECTED_ASSOCIATION_COUNT} symptom-point associations, got $totalLinks",
            )
        }

        return LearningData(
            references = references,
            pointLearning = pointLearning,
            symptomCategories = categories,
            symptoms = symptoms,
        )
    }

    private fun verifyManifestCountsMatch(
        counts: EncyclopediaPackCounts,
        meridians: List<Meridian>,
        points: List<Acupoint>,
        atlas: AtlasData,
        regions: RegionCatalog,
        learning: LearningData,
    ) {
        val frontCount = atlas.projectionsByViewAndMeridian[AtlasViewId.FRONT]?.values?.sumOf { it.size } ?: 0
        val backCount = atlas.projectionsByViewAndMeridian[AtlasViewId.BACK]?.values?.sumOf { it.size } ?: 0
        val sideCount = atlas.projectionsByViewAndMeridian[AtlasViewId.SIDE]?.values?.sumOf { it.size } ?: 0
        val uniqueProjected = atlas.projectionsByViewAndMeridian.values.flatMap { it.values.flatten() }
            .map { it.pointCode }
            .toSet()
            .size
        val associationCount = learning.symptoms.sumOf { it.links.size }

        if (counts.meridians != meridians.size ||
            counts.points != points.size ||
            counts.regionViews != regions.regions.size ||
            counts.pointLearning != learning.pointLearning.size ||
            counts.symptomCategories != learning.symptomCategories.size ||
            counts.symptoms != learning.symptoms.size ||
            counts.associations != associationCount ||
            counts.atlasViews != atlas.views.size ||
            counts.frontProjections != frontCount ||
            counts.backProjections != backCount ||
            counts.sideProjections != sideCount ||
            counts.uniqueProjectedPoints != uniqueProjected ||
            counts.occludedPoints != atlas.occluded.size
        ) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.INTEGRITY_MISMATCH,
                "manifest.counts does not match parsed pack totals",
            )
        }
    }

    private fun verifyReferencedAndManifestedIllustrations(
        source: EncyclopediaPackSource,
        atlas: AtlasData,
        regions: RegionCatalog,
        manifestFiles: List<EncyclopediaPackFileEntry>,
        manifestByPath: Map<String, EncyclopediaPackFileEntry>,
    ) {
        val referencedArtworks = LinkedHashSet<String>()
        for (view in atlas.views) referencedArtworks.add(view.artwork)
        for (region in regions.regions) referencedArtworks.add(region.artwork)

        if (referencedArtworks.size != EncyclopediaPackConstants.EXPECTED_ILLUSTRATION_COUNT) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Expected ${EncyclopediaPackConstants.EXPECTED_ILLUSTRATION_COUNT} referenced illustrations, got ${referencedArtworks.size}",
            )
        }

        val manifestedIllustrations = manifestFiles
            .filter { it.path.startsWith("illustrations/") }
            .map { it.path }
            .toSet()
        if (manifestedIllustrations != referencedArtworks) {
            val missingInManifest = referencedArtworks - manifestedIllustrations
            val extraInManifest = manifestedIllustrations - referencedArtworks
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                "Manifest illustrations mismatch (missing=$missingInManifest, extra=$extraInManifest)",
            )
        }

        for (artworkPath in referencedArtworks) {
            val expected = manifestByPath[artworkPath] ?: throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.MISSING_ASSET,
                "Referenced illustration missing from manifest: $artworkPath",
            )
            if (!source.exists(artworkPath)) {
                throw EncyclopediaPackException(
                    EncyclopediaPackErrorCode.MISSING_ASSET,
                    "Missing referenced illustration asset: $artworkPath",
                )
            }
            verifyPngStreamIntegrity(source, expected)
        }
    }

    private fun verifyPngStreamIntegrity(
        source: EncyclopediaPackSource,
        entry: EncyclopediaPackFileEntry,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        val header = ByteArray(PNG_SIGNATURE.size)
        var headerBytesRead = 0
        var totalBytes = 0L

        try {
            source.openStream(entry.path).use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    if (headerBytesRead < PNG_SIGNATURE.size) {
                        val copyLen = minOf(read, PNG_SIGNATURE.size - headerBytesRead)
                        System.arraycopy(buffer, 0, header, headerBytesRead, copyLen)
                        headerBytesRead += copyLen
                    }
                    digest.update(buffer, 0, read)
                    totalBytes += read
                }
            }
        } catch (error: EncyclopediaPackException) {
            throw error
        } catch (error: IOException) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.MISSING_ASSET,
                "Failed to read illustration asset ${entry.path}",
                error,
            )
        }

        if (totalBytes == 0L || headerBytesRead < PNG_SIGNATURE.size || !header.contentEquals(PNG_SIGNATURE)) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.INTEGRITY_MISMATCH,
                "Illustration asset is empty or not a valid PNG: ${entry.path}",
            )
        }
        if (totalBytes != entry.bytes) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.INTEGRITY_MISMATCH,
                "Illustration byte size mismatch for ${entry.path}: expected ${entry.bytes}, actual $totalBytes",
            )
        }
        val actualSha = digest.digest().toHex()
        if (actualSha != entry.sha256) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.INTEGRITY_MISMATCH,
                "Illustration SHA-256 mismatch for ${entry.path}: expected ${entry.sha256}, actual $actualSha",
            )
        }
    }

    private fun validateIllustrationPath(
        rawPath: String,
        fieldPath: String,
        manifestByPath: Map<String, EncyclopediaPackFileEntry>,
    ): String {
        val safe = validateSafeRelativePath(rawPath, fieldPath)
        if (!safe.startsWith("illustrations/") || !safe.endsWith(".png")) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                "$fieldPath: illustration path must be illustrations/*.png, got \"$rawPath\"",
            )
        }
        if (safe !in manifestByPath) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.MISSING_ASSET,
                "$fieldPath: referenced illustration \"$safe\" is not declared in manifest.files",
            )
        }
        return safe
    }

    private fun parseStudySectionEntry(el: JsonElement, fieldPath: String): StudySectionEntry {
        val obj = el.asObject(fieldPath)
        return StudySectionEntry(
            title = obj.requireNonBlankString("title", "$fieldPath.title"),
            content = obj.requireNonBlankString("content", "$fieldPath.content"),
        )
    }

    private fun parseSourceRef(obj: JsonObject, fieldPath: String): EncyclopediaSourceRef =
        EncyclopediaSourceRef(
            title = obj.requireNonBlankString("title", "$fieldPath.title"),
            url = obj.requireHttpsUrl("url", "$fieldPath.url"),
        )

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
}

private fun JsonElement.asObject(fieldPath: String): JsonObject =
    this as? JsonObject ?: throw EncyclopediaPackException(
        EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
        "$fieldPath: expected JSON object",
    )

private fun JsonObject.requireElement(key: String, fieldPath: String): JsonElement =
    this[key] ?: throw EncyclopediaPackException(
        EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
        "$fieldPath: missing required field",
    )

private fun JsonObject.requireObject(key: String, fieldPath: String): JsonObject =
    requireElement(key, fieldPath) as? JsonObject ?: throw EncyclopediaPackException(
        EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
        "$fieldPath: expected JSON object",
    )

private fun JsonObject.optionalObject(key: String, fieldPath: String): JsonObject? {
    val el = this[key] ?: return null
    if (el === JsonNull) return null
    return el as? JsonObject ?: throw EncyclopediaPackException(
        EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
        "$fieldPath: expected JSON object or null",
    )
}

private fun JsonObject.requireArray(key: String, fieldPath: String): JsonArray =
    requireElement(key, fieldPath) as? JsonArray ?: throw EncyclopediaPackException(
        EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
        "$fieldPath: expected JSON array",
    )

private fun JsonObject.requireString(key: String, fieldPath: String): String {
    val prim = requireElement(key, fieldPath) as? JsonPrimitive
    if (prim == null || !prim.isString) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: expected string",
        )
    }
    return prim.content
}

private fun JsonObject.requireNonBlankString(key: String, fieldPath: String): String {
    val value = requireString(key, fieldPath)
    if (value.isBlank()) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: expected non-blank string",
        )
    }
    return value
}

private fun JsonObject.optionalNonBlankString(key: String, fieldPath: String): String? {
    val el = this[key] ?: return null
    if (el === JsonNull) return null
    val prim = el as? JsonPrimitive
    if (prim == null || !prim.isString) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: expected string or null",
        )
    }
    if (prim.content.isBlank()) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: optional string must not be blank (use null instead)",
        )
    }
    return prim.content
}

private fun JsonObject.requireHttpsUrl(key: String, fieldPath: String): String {
    val value = requireNonBlankString(key, fieldPath)
    if (!HTTPS_URL_REGEX.matches(value)) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: expected https:// URL, got \"$value\"",
        )
    }
    return value
}

private fun JsonObject.requireSha256(key: String, fieldPath: String): String {
    val value = requireNonBlankString(key, fieldPath)
    if (!SHA256_HEX_REGEX.matches(value)) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: expected 64-char lowercase hex SHA-256",
        )
    }
    return value
}

private fun JsonObject.requireInt(key: String, fieldPath: String): Int {
    val prim = requireElement(key, fieldPath) as? JsonPrimitive
    val value = if (prim != null && !prim.isString) prim.intOrNull else null
    return value ?: throw EncyclopediaPackException(
        EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
        "$fieldPath: expected integer",
    )
}

private fun JsonObject.requirePositiveInt(key: String, fieldPath: String): Int {
    val value = requireInt(key, fieldPath)
    if (value <= 0) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: expected positive integer, got $value",
        )
    }
    return value
}

private fun JsonObject.requirePositiveLong(key: String, fieldPath: String): Long {
    val prim = requireElement(key, fieldPath) as? JsonPrimitive
    val value = if (prim != null && !prim.isString) prim.longOrNull else null
    if (value == null || value <= 0L) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: expected positive long",
        )
    }
    return value
}

private fun JsonObject.requireFiniteDouble(key: String, fieldPath: String): Double {
    val prim = requireElement(key, fieldPath) as? JsonPrimitive
    val value = if (prim != null && !prim.isString) prim.doubleOrNull else null
    if (value == null || !value.isFinite()) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: expected finite number",
        )
    }
    return value
}

private fun JsonObject.requirePositiveFiniteDouble(key: String, fieldPath: String): Double {
    val value = requireFiniteDouble(key, fieldPath)
    if (value <= 0.0) {
        throw EncyclopediaPackException(
            EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
            "$fieldPath: expected positive finite number, got $value",
        )
    }
    return value
}

private fun JsonObject.requireBoolean(key: String, fieldPath: String): Boolean {
    val prim = requireElement(key, fieldPath) as? JsonPrimitive
    val value = if (prim != null && !prim.isString) prim.booleanOrNull else null
    return value ?: throw EncyclopediaPackException(
        EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
        "$fieldPath: expected boolean",
    )
}

private fun JsonObject.requireStringList(key: String, fieldPath: String): List<String> {
    val arr = requireArray(key, fieldPath)
    return arr.mapIndexed { idx, el ->
        val prim = el as? JsonPrimitive
        if (prim == null || !prim.isString || prim.content.isBlank()) {
            throw EncyclopediaPackException(
                EncyclopediaPackErrorCode.SCHEMA_CONTRACT_VIOLATION,
                "$fieldPath[$idx]: expected non-blank string",
            )
        }
        prim.content
    }
}
