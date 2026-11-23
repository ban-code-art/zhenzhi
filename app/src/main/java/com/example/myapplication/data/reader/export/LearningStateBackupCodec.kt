package com.example.myapplication.data.reader.export

import com.example.myapplication.data.reader.content.ContentPackageCodec
import kotlinx.serialization.json.*

object LearningStateBackupCodec {
    fun decode(recordsBytes: ByteArray): BackupV2Records {
        v2require(recordsBytes.size <= BackupV2Limits.JSON_BYTES, "LIMIT_EXCEEDED", "records byte limit before snapshot allocation")
        val snapshot = recordsBytes.copyOf()
        val root = BackupV2Json.obj(BackupV2Json.parse(snapshot), setOf("schemaVersion", "tables"))
        v2require(BackupV2Json.integer(root["schemaVersion"]) == 2L, "UNSUPPORTED_RECORDS_VERSION", "records schema must be 2")
        val tables = BackupV2Json.obj(root.getValue("tables"), BackupV2Table.entries.map { it.wireName }.toSet())
        var count = 0L
        BackupV2Table.entries.forEach { table -> count += BackupV2Json.array(tables[table.wireName]).size.toLong()
            v2require(count <= BackupV2Limits.RECORDS, "LIMIT_EXCEEDED", "records count > 100000") }
        return BackupV2Records(BackupV2Table.entries.associateWith { table ->
            BackupV2Json.array(tables[table.wireName]).map { value ->
                val row = value as? JsonObject ?: throw BackupV2Exception("INVALID_RECORDS", "row must be object")
                BackupV2Record(table, row.mapValues { (_, field) -> when {
                    field === JsonNull -> BackupV2Value.Null
                    field is JsonPrimitive && field.isString -> BackupV2Value.Text(field.content)
                    field is JsonPrimitive -> BackupV2Value.Integer(BackupV2Json.integer(field))
                    else -> throw BackupV2Exception("INVALID_RECORDS", "wire fields must be scalar")
                } })
            }
        })
    }
    fun encode(records: BackupV2Records): ByteArray {
        BackupV2RecordRules.checkRelations(records)
        val root = records.json()
        BackupV2Codec.checkEncodedSize(root, BackupV2Limits.JSON_BYTES)
        return ContentPackageCodec.canonicalJson(root).toByteArray(Charsets.UTF_8)
    }
    /** Missing identity mappings block the preview; never retain a source ID as a target identity. */
    fun remap(records: BackupV2Records, idMap: BackupV2IdMap): BackupV2Records {
        return BackupV2Records(records.tables.mapValues { (table, rows) -> rows.map { row ->
            val fields = row.fields.toMutableMap()
            if (table.primaryColumns == listOf("id")) fields["id"] = BackupV2Value.Text(idMap.table(table, row.text("id")!!))
            BackupV2RecordRules.links[table].orEmpty().forEach { relation -> row.text(relation.column)?.let {
                fields[relation.column] = BackupV2Value.Text(idMap.table(relation.target, it))
            } }
            listOf("document_id", "source_document_id").filter { it in fields }.forEach { column -> row.text(column)?.let {
                fields[column] = BackupV2Value.Text(idMap.document(it))
            } }
            if ("section_id" in fields) row.text("section_id")?.let { fields["section_id"] = BackupV2Value.Text(idMap.section(it)) }
            if ("chapter_id" in fields) row.text("chapter_id")?.let { fields["chapter_id"] = BackupV2Value.Text(idMap.chapter(it)) }
            row.fields.forEach { (column, value) -> if (value is BackupV2Value.Text) {
                val changed = BackupV2JsonProjection.remapColumn(table, column, value.value, idMap)
                if (changed != value.value) fields[column] = BackupV2Value.Text(changed)
            } }
            BackupV2Record(table, fields)
        } })
    }
}
