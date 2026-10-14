package com.hebi.quickcut.editor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class EditorSessionSnapshot(
    val timeline: EditTimeline,
    val selectedId: Long?,
    val playPositionMs: Long,
    val quickMode: Boolean,
)

/** Crash/force-stop recovery for local selections with still-readable source URIs. */
class EditorSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "quickcut_editor_session_v1", Context.MODE_PRIVATE,
    )

    fun load(): EditorSessionSnapshot? {
        val raw = preferences.getString("session", null) ?: return null
        return runCatching {
            val data = JSONObject(raw)
            val savedClips = data.getJSONArray("clips")
            require(savedClips.length() in 1..250)
            val clips = (0 until savedClips.length()).map { index ->
                val clip = savedClips.getJSONObject(index)
                VideoClip(
                    id = clip.getLong("id"),
                    uri = clip.getString("uri"),
                    sourceDurationMs = clip.getLong("duration"),
                    startMs = clip.getLong("start"),
                    endMs = clip.getLong("end"),
                    muted = clip.optBoolean("muted", false),
                    hasAudio = clip.optBoolean("hasAudio", true),
                )
            }
            require(clips.map { it.id }.distinct().size == clips.size)
            EditorSessionSnapshot(
                timeline = EditTimeline(clips),
                selectedId = if (data.isNull("selectedId")) null else data.getLong("selectedId"),
                playPositionMs = data.optLong("positionMs", 0L),
                quickMode = data.optBoolean("quickMode", false),
            )
        }.getOrNull()
    }

    fun save(snapshot: EditorSessionSnapshot) {
        val clips = snapshot.timeline.clips
        if (clips.isEmpty()) {
            clear()
            return
        }
        val array = JSONArray()
        clips.forEach {
            array.put(JSONObject().apply {
                put("id", it.id)
                put("uri", it.uri)
                put("duration", it.sourceDurationMs)
                put("start", it.startMs)
                put("end", it.endMs)
                put("muted", it.muted)
                put("hasAudio", it.hasAudio)
            })
        }
        val data = JSONObject().apply {
            put("schemaVersion", 1)
            put("clips", array)
            put("selectedId", snapshot.selectedId ?: JSONObject.NULL)
            put("positionMs", snapshot.playPositionMs)
            put("quickMode", snapshot.quickMode)
        }
        preferences.edit().putString("session", data.toString()).apply()
    }

    fun clear() {
        preferences.edit().remove("session").apply()
    }
}
