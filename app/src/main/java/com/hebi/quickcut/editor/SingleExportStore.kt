package com.hebi.quickcut.editor

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import org.json.JSONObject

/** A durable output handle and validation contract. Never stores original-deletion consent. */
class SingleExportStore internal constructor(
    private val base: File,
    private val file: AtomicFile = AtomicFile(base),
) : SingleExportJournal {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "quickcut_single_export_v1.json"))

    @Synchronized
    fun hasRecord(): Boolean {
        val directory = base.parentFile
        if (directory == null || !directory.isDirectory || !directory.canRead()) return true
        return listOf(base, File(base.path + ".bak"), File(base.path + ".new")).any { it.exists() }
    }

    @Synchronized
    override fun load(): SingleExportSnapshot? {
        if (!hasRecord()) return null
        // A failed or corrupt read is not absence: the caller must keep retry blocked.
        val data = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(data.getInt("schemaVersion") == 1)
        require(listOf("outputUri", "expectedDurationMs", "expectedDurationUs", "expectedAudio", "expectedDateMs").all(data::has))
        return SingleExportSnapshot(
            outputUri = if (data.isNull("outputUri")) null else data.getString("outputUri"),
            expectedDurationMs = data.getLong("expectedDurationMs"),
            expectedAudio = data.getBoolean("expectedAudio"),
            expectedDateMs = if (data.isNull("expectedDateMs")) null else data.getLong("expectedDateMs"),
            expectedDurationUs = data.getLong("expectedDurationUs"),
        )
    }

    @Synchronized
    override fun save(snapshot: SingleExportSnapshot) {
        val data = JSONObject().apply {
            put("schemaVersion", 1)
            put("outputUri", snapshot.outputUri ?: JSONObject.NULL)
            put("expectedDurationMs", snapshot.expectedDurationMs)
            put("expectedDurationUs", snapshot.expectedDurationUs)
            put("expectedAudio", snapshot.expectedAudio)
            put("expectedDateMs", snapshot.expectedDateMs ?: JSONObject.NULL)
        }
        val stream = file.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            // AtomicFile logs some sync/rename failures instead of throwing.
            // A writer may continue only after the exact new URI is committed.
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
        // Readback can restore the .bak on older Android releases. A second
        // failWrite would then delete the restored committed record.
        if (load() != snapshot) throw IOException("저장 상태 기록을 확인하지 못했습니다.")
    }

    @Synchronized
    override fun clear() {
        file.delete()
        // API 29's AtomicFile does not know about a newer implementation's .new.
        listOf(File(base.path + ".bak"), File(base.path + ".new")).forEach { it.delete() }
        if (hasRecord()) throw IOException("이전 저장 기록을 정리하지 못했습니다.")
    }
}
