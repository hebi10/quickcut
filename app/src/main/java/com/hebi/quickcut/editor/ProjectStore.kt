package com.hebi.quickcut.editor

import android.content.Context
import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class SavedProject(
    val id: String,
    val title: String,
    val modifiedAt: Long,
    val clipCount: Int,
    val thumbnailUri: String,
    val thumbnailTimeMs: Long,
)

/** Preserve the index verbatim if its contents cannot be read safely. */
class ProjectIndexReadException(cause: Throwable) : IOException(
    "프로젝트 목록을 읽지 못했습니다. 기존 프로젝트를 보호하기 위해 저장·삭제를 중단했습니다.", cause,
)

/** Separate durable snapshot per project; never overwrites the crash-recovery session. */
class ProjectStore(context: Context, preferencesName: String = "quickcut_named_projects_v1") {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        preferencesName, Context.MODE_PRIVATE,
    )

    fun list(): List<SavedProject> = try {
        val raw = preferences.getString("index", null)
        if (raw == null && preferences.contains("index")) {
            throw IOException("프로젝트 목록 값이 유효하지 않습니다.")
        }
        val array = JSONArray(raw ?: "[]")
        val items = (0 until array.length()).map { i ->
            val row = array.getJSONObject(i)
            SavedProject(
                id = row.getString("id"),
                title = row.getString("title"),
                modifiedAt = row.getLong("modifiedAt"),
                clipCount = row.getInt("clipCount"),
                thumbnailUri = row.getString("thumbnailUri"),
                thumbnailTimeMs = row.optLong("thumbnailTimeMs", 0L),
            )
        }
        require(items.map { it.id }.distinct().size == items.size) { "중복된 프로젝트 ID입니다." }
        items.sortedByDescending { it.modifiedAt }
    } catch (error: Exception) {
        throw ProjectIndexReadException(error)
    }

    fun activeId(): String? =
        preferences.getString("activeId", null)?.takeIf { id -> list().any { it.id == id } }

    fun setActiveId(id: String?) {
        require(id == null || list().any { it.id == id })
        val edit = preferences.edit()
        if (id == null) edit.remove("activeId") else edit.putString("activeId", id)
        if (!edit.commit()) throw IOException("프로젝트 선택 상태를 저장하지 못했습니다.")
    }

    fun load(id: String): EditorSessionSnapshot? {
        if (list().none { it.id == id }) return null
        return EditorSessionStore(appContext, preferenceKey(id)).load()
    }

    fun save(snapshot: EditorSessionSnapshot, title: String, existingId: String? = null): SavedProject {
        require(!snapshot.timeline.isEmpty)
        val cleanTitle = title.trim().take(80)
        require(cleanTitle.isNotEmpty()) { "프로젝트 이름을 입력해 주세요." }
        val current = list()
        val id = existingId?.takeIf { candidate -> current.any { it.id == candidate } }
            ?: UUID.randomUUID().toString()
        val first = snapshot.timeline.clips.first()
        val item = SavedProject(
            id = id, title = cleanTitle, modifiedAt = System.currentTimeMillis(),
            clipCount = snapshot.timeline.clips.size,
            thumbnailUri = first.uri, thumbnailTimeMs = first.startMs,
        )
        val items = listOf(item) + current.filterNot { it.id == id }
        val stored = EditorSessionStore(appContext, preferenceKey(id))
        stored.save(snapshot)
        try {
            writeIndex(items)
        } catch (error: Exception) {
            if (existingId == null) runCatching { stored.clear() }
            throw error
        }
        return item
    }

    fun delete(id: String) {
        val items = list()
        if (items.none { it.id == id }) return
        val savedSession = EditorSessionStore(appContext, preferenceKey(id))
        savedSession.load()
        writeIndex(items.filterNot { it.id == id })
        if (preferences.getString("activeId", null) == id) setActiveId(null)
        runCatching { savedSession.clear() }
    }

    private fun preferenceKey(id: String): String {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "잘못된 프로젝트 ID입니다." }
        return "quickcut_project_$id"
    }

    private fun writeIndex(items: List<SavedProject>) {
        val array = JSONArray()
        items.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("title", p.title)
                put("modifiedAt", p.modifiedAt)
                put("clipCount", p.clipCount)
                put("thumbnailUri", p.thumbnailUri)
                put("thumbnailTimeMs", p.thumbnailTimeMs)
            })
        }
        if (!preferences.edit().putString("index", array.toString()).commit())
            throw IOException("프로젝트 목록을 저장하지 못했습니다.")
    }
}
