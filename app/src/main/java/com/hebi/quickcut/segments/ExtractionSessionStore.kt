package com.hebi.quickcut.segments

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

enum class ExtractionStatus { WAITING, SAVING, SAVED, FAILED, BLOCKED }

data class ExtractionItem(
    val range: ExtractionRange,
    val status: ExtractionStatus = ExtractionStatus.WAITING,
    val outputUri: String? = null,
    val message: String? = null,
)

data class ExtractionSession(
    val sourceUri: String,
    val sourceName: String,
    val durationMs: Long,
    val hasAudio: Boolean,
    val items: List<ExtractionItem>,
) {
    init {
        require(sourceUri.isNotBlank() && durationMs >= 100 && sourceName.isNotBlank())
        require(items.size in 1..ExtractionPlanner.MAX_SEGMENTS)
        require(items.map { it.range.id }.distinct().size == items.size)
        require(items.all { ExtractionPlanner.isValid(it.range, durationMs) })
    }
}

class ExtractionSessionStore(context: Context, fileName: String = "quickcut_extraction_session.json") {
    private val target = File(context.filesDir, fileName)
    private val file = AtomicFile(target)

    @Synchronized
    fun load(): ExtractionSession? {
        if (!target.exists() && !File(target.path + ".bak").exists()) return null
        val data = file.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        require(data.getInt("schemaVersion") == 1) { "지원하지 않는 구간 저장 기록입니다." }
        val raw = data.getJSONArray("items")
        require(raw.length() in 1..ExtractionPlanner.MAX_SEGMENTS)
        val rows = (0 until raw.length()).map { index ->
            val item = raw.getJSONObject(index)
            ExtractionItem(
                range = ExtractionRange(item.getLong("id"), item.getLong("start"), item.getLong("end")),
                status = ExtractionStatus.valueOf(item.getString("status")),
                outputUri = item.optString("output").takeIf { it.isNotBlank() },
                message = item.optString("message").takeIf { it.isNotBlank() },
            )
        }
        return ExtractionSession(
            sourceUri = data.getString("source"),
            sourceName = data.getString("sourceName"),
            durationMs = data.getLong("duration"),
            hasAudio = data.getBoolean("hasAudio"),
            items = rows,
        )
    }

    @Synchronized
    fun clear() {
        file.delete()
        File(target.path + ".bak").delete()
        File(target.path + ".new").delete()
    }

    @Synchronized
    fun save(snapshot: ExtractionSession) {
        val data = JSONObject().apply {
            put("schemaVersion", 1)
            put("source", snapshot.sourceUri)
            put("sourceName", snapshot.sourceName)
            put("duration", snapshot.durationMs)
            put("hasAudio", snapshot.hasAudio)
            put("items", JSONArray().apply {
                snapshot.items.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.range.id)
                        put("start", item.range.startMs)
                        put("end", item.range.endMs)
                        put("status", item.status.name)
                        put("output", item.outputUri ?: "")
                        put("message", item.message ?: "")
                    })
                }
            })
        }
        val encoded = data.toString()
        val stream = file.startWrite()
        try {
            stream.write(encoded.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
        val readback = file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (readback != encoded) throw IOException("구간 저장 기록을 확인하지 못했습니다.")
    }
}
