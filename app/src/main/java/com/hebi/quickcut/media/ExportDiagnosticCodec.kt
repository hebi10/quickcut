package com.hebi.quickcut.media

import org.json.JSONArray
import org.json.JSONObject

/** A versioned local format; corrupt/unknown data is ignored, never fed back to export. */
internal object ExportDiagnosticCodec {
    private const val MAX_BYTES = 196_608

    fun encode(history: ExportDiagnosticHistory): String = JSONObject().apply {
        put("version", 1)
        put("recent", JSONArray(history.recent.map(::encodeRecord)))
        put("failures", JSONArray(history.failures.map(::encodeRecord)))
    }.toString()

    fun decode(value: String?): ExportDiagnosticHistory = runCatching {
        require(value != null && value.length <= MAX_BYTES)
        val json = JSONObject(value)
        require(json.getInt("version") == 1)
        fun records(key: String): List<ExportDiagnosticRecord> {
            val array = json.getJSONArray(key)
            require(array.length() <= ExportDiagnosticHistory.RECENT_LIMIT + ExportDiagnosticHistory.FAILURE_LIMIT)
            return (0 until array.length()).mapNotNull { index ->
                runCatching { decodeRecord(array.getJSONObject(index)) }.getOrNull()
            }
        }
        ExportDiagnosticHistory.restore(records("recent"), records("failures"))
    }.getOrDefault(ExportDiagnosticHistory())

    private fun encodeRecord(record: ExportDiagnosticRecord) = JSONObject().apply {
        put("id", record.operationId); put("status", record.status.name); put("stage", record.stage.name)
        put("elapsed", record.elapsedMs)
        put("times", JSONObject().apply { record.stageMs.forEach { (stage, ms) -> put(stage.name, ms) } })
        put("method", record.method); put("error", record.failureType)
        put("engine", record.engine?.name)
        record.failureEvidence?.sanitized()?.let { value ->
            put("failure", JSONObject().apply {
                put("media3", value.media3ErrorCode); put("codec", value.codecName)
                put("kind", value.codecKind?.name); put("codecError", value.codecErrorCode)
                put("recoverable", value.recoverable); put("transient", value.transient)
            })
        }
        put("copyReason", record.reasons.copyReason?.name); put("smartReason", record.reasons.smartCutReason?.name)
        put("facts", JSONObject().apply {
            val f = record.facts
            put("app", f.app); put("device", f.device); put("api", f.androidApi)
            put("sources", f.sourceCount); put("duration", f.sourceDurationMs)
            put("retained", f.retainedDurationUs); put("clips", f.clipCount)
            put("bytes", f.sourceBytes); put("video", f.videoMime); put("width", f.width); put("height", f.height)
            put("fps", f.frameRate); put("standard", f.colorStandard); put("transfer", f.colorTransfer)
            put("audio", f.audioMime); put("channels", f.audioChannels); put("rate", f.audioSampleRate)
            put("cache", f.cacheAvailableBytes); put("gallery", f.galleryAvailableBytes)
        })
    }

    private fun decodeRecord(json: JSONObject): ExportDiagnosticRecord {
        val f = json.getJSONObject("facts")
        val times = json.getJSONObject("times")
        fun JSONObject.string(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
        fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
        fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
        fun JSONObject.boolean(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null
        val evidence = runCatching {
            json.optJSONObject("failure")?.let {
                ExportFailureEvidence(it.int("media3"), it.string("codec"),
                    it.string("kind")?.let(ExportCodecKind::valueOf), it.int("codecError"),
                    it.boolean("recoverable"), it.boolean("transient")).sanitized()
            }
        }.getOrNull()
        return ExportDiagnosticRecord(json.getString("id"), ExportDiagnosticStatus.valueOf(json.getString("status")),
            ExportStage.valueOf(json.getString("stage")), json.getLong("elapsed"),
            times.keys().asSequence().associate { key -> ExportStage.valueOf(key) to times.getLong(key) },
            ExportDiagnosticFacts(f.getString("app"), f.getString("device"), f.getInt("api"),
                f.getInt("sources"), f.getLong("duration"), f.getLong("retained"), f.getInt("clips"),
                f.long("bytes"), f.string("video"), f.int("width"), f.int("height"),
                if (f.has("fps")) f.getDouble("fps") else null, f.int("standard"), f.int("transfer"),
                f.string("audio"), f.int("channels"), f.int("rate"), f.long("cache"), f.long("gallery")),
            ExportDiagnostics(FastExportReason.fromStoredName(json.string("copyReason")),
                FastExportReason.fromStoredName(json.string("smartReason"))), json.string("method"), json.string("error"),
            runCatching { json.string("engine")?.let(ExportEngine::valueOf) }.getOrNull(), evidence)
    }
}
