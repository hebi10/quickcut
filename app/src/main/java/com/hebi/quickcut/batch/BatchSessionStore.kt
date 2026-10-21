package com.hebi.quickcut.batch

import android.content.Context
import android.util.AtomicFile
import com.hebi.quickcut.media.OriginalTimestamp
import com.hebi.quickcut.media.SaveDateMode
import com.hebi.quickcut.media.ExportMethod
import com.hebi.quickcut.media.ExportReport
import com.hebi.quickcut.media.ExportDiagnostics
import com.hebi.quickcut.media.FastExportReason
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class BatchSessionSnapshot(
    val files: List<BatchFile>,
    val template: BatchTemplate?,
    val dateMode: SaveDateMode,
    val runState: BatchRunState,
)

class BatchSessionStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "quickcut_batch_session_v1.json"))

    @Synchronized
    fun load(): BatchSessionSnapshot? = runCatching {
        val data = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(data.getInt("schemaVersion") == 1)
        val rows = data.getJSONArray("files")
        require(rows.length() in 1..250)
        val files = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            BatchFile(
                uri = row.getString("uri"),
                displayName = row.getString("name"),
                durationMs = row.getLong("duration"),
                hasAudio = row.getBoolean("hasAudio"),
                originalDate = if (row.isNull("originalDate")) null else
                    OriginalTimestamp(row.getLong("originalDate"), row.optString("dateSource", "복구 기록")),
                status = BatchFileStatus.valueOf(row.getString("status")),
                reason = row.optString("reason").takeIf { it.isNotBlank() && it != "null" },
                savedOutputUri = row.optString("output").takeIf { it.isNotBlank() && it != "null" },
                outputExpectedDurationMs = if (row.isNull("expectedDuration")) null else row.getLong("expectedDuration"),
                outputExpectedAudio = if (row.isNull("expectedAudio")) null else row.getBoolean("expectedAudio"),
                outputExpectedDateMs = if (row.isNull("expectedDate")) null else row.getLong("expectedDate"),
                exportReport = readReport(row.optJSONObject("exportReport")),
            ).also { require(it.durationMs >= 0L && it.uri.isNotBlank()) }
        }
        require(files.map { it.uri }.distinct().size == files.size)
        val plan = data.optJSONObject("template")?.let {
            BatchTemplate(it.getString("id"), it.getString("name"),
                BatchClipMode.valueOf(it.getString("mode")), it.getLong("leading"),
                it.getLong("trailing"), it.getBoolean("mute")).also { template ->
                require(template.checkValidity() == null)
            }
        }
        BatchSessionSnapshot(files, plan, SaveDateMode.valueOf(data.getString("dateMode")),
            BatchRunState.valueOf(data.getString("runState")))
    }.getOrNull()

    @Synchronized
    fun save(snapshot: BatchSessionSnapshot) {
        if (snapshot.files.isEmpty()) {
            clear()
            return
        }
        val rows = JSONArray()
        snapshot.files.forEach { row ->
            rows.put(JSONObject().apply {
                put("uri", row.uri)
                put("name", row.displayName)
                put("duration", row.durationMs)
                put("hasAudio", row.hasAudio)
                put("originalDate", row.originalDate?.epochMs ?: JSONObject.NULL)
                put("dateSource", row.originalDate?.source ?: JSONObject.NULL)
                put("status", row.status.name)
                put("reason", row.reason ?: JSONObject.NULL)
                put("output", row.savedOutputUri ?: JSONObject.NULL)
                put("expectedDuration", row.outputExpectedDurationMs ?: JSONObject.NULL)
                put("expectedAudio", row.outputExpectedAudio ?: JSONObject.NULL)
                put("expectedDate", row.outputExpectedDateMs ?: JSONObject.NULL)
                put("exportReport", row.exportReport?.let { report ->
                    JSONObject().apply {
                        put("method", report.method.name)
                        put("elapsedMs", report.elapsedMs)
                        put("optimizationResult", report.optimizationResult ?: JSONObject.NULL)
                        put("copyReason", report.diagnostics.copyReason?.name ?: JSONObject.NULL)
                        put("smartCutReason", report.diagnostics.smartCutReason?.name ?: JSONObject.NULL)
                    }
                } ?: JSONObject.NULL)
            })
        }
        val data = JSONObject().apply {
            put("schemaVersion", 1)
            put("files", rows)
            put("dateMode", snapshot.dateMode.name)
            put("runState", snapshot.runState.name)
            put("template", snapshot.template?.let { template ->
                JSONObject().apply {
                    put("id", template.id)
                    put("name", template.name)
                    put("mode", template.mode.name)
                    put("leading", template.leadingMs)
                    put("trailing", template.trailingMs)
                    put("mute", template.muteAudio)
                }
            } ?: JSONObject.NULL)
        }
        val stream = file.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun clear() { file.delete() }

    private fun readReport(data: JSONObject?): ExportReport? {
        if (data == null) return null // Version 0.4.2 journals have no report.
        return runCatching {
            ExportReport(
                method = ExportMethod.entries.firstOrNull { it.name == data.optString("method") }
                    ?: ExportMethod.UNKNOWN,
                elapsedMs = data.getLong("elapsedMs").also { require(it >= 0L) },
                optimizationResult = if (data.isNull("optimizationResult")) null
                    else data.getInt("optimizationResult"),
                diagnostics = ExportDiagnostics(
                    copyReason = FastExportReason.fromStoredName(
                        if (data.isNull("copyReason")) null else data.optString("copyReason")),
                    smartCutReason = FastExportReason.fromStoredName(
                        if (data.isNull("smartCutReason")) null else data.optString("smartCutReason")),
                ),
            )
        }.getOrNull() // Optional telemetry must never invalidate a saved-output journal.
    }
}

