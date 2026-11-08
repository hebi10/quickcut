package com.hebi.quickcut.editor

import android.content.Context
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

data class EditorSessionSnapshot(
    val timeline: EditTimeline,
    val selectedId: Long?,
    val playPositionMs: Long,
    val quickMode: Boolean,
)

/** Crash/force-stop recovery for local selections with still-readable source URIs. */
class EditorSessionStore(context: Context, preferencesName: String = "quickcut_editor_session_v1") {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName, Context.MODE_PRIVATE,
    )

    fun load(): EditorSessionSnapshot? {
        val raw = preferences.getString("session", null) ?: return null
        return runCatching {
            val data = JSONObject(raw)
            val savedClips = data.getJSONArray("clips")
            require(savedClips.length() > 0)
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
                    textLayers = clip.optJSONArray("textLayers")?.let { array ->
                        (0 until array.length()).map { i ->
                            val entry = array.getJSONObject(i)
                            TextLayer(
                                id = entry.getLong("id"),
                                text = entry.getString("text"),
                                startMs = entry.getLong("startMs"),
                                endMs = entry.getLong("endMs"),
                                x = entry.optDouble("x", 0.5).toFloat(),
                                y = entry.optDouble("y", 0.78).toFloat(),
                                size = entry.optInt("size", 100),
                                color = TextLayerColor.valueOf(entry.optString("color", "WHITE")),
                                alignment = TextLayerAlignment.valueOf(
                                    entry.optString("alignment", "CENTER"),
                                ),
                                darkBackground = entry.optBoolean("darkBackground", false),
                            )
                        }.also { TextLayerPolicy.validate(it, clip.getLong("end") - clip.getLong("start")) }
                    } ?: emptyList(),
                    preciseRange = if (clip.isNull("preciseStartUs") && clip.isNull("preciseEndUs")) null
                        else PreciseClipRange(clip.getLong("preciseStartUs"), clip.getLong("preciseEndUs")),
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
                put("preciseStartUs", it.preciseRange?.startUs ?: JSONObject.NULL)
                put("preciseEndUs", it.preciseRange?.endUs ?: JSONObject.NULL)
                put("textLayers", JSONArray().apply {
                    it.textLayers.forEach { layer ->
                        put(JSONObject().apply {
                            put("id", layer.id)
                            put("text", layer.text)
                            put("startMs", layer.startMs)
                            put("endMs", layer.endMs)
                            put("x", layer.x.toDouble())
                            put("y", layer.y.toDouble())
                            put("size", layer.size)
                            put("color", layer.color.name)
                            put("alignment", layer.alignment.name)
                            put("darkBackground", layer.darkBackground)
                        })
                    }
                })
            })
        }
        val data = JSONObject().apply {
            put("schemaVersion", 3)
            put("clips", array)
            put("selectedId", snapshot.selectedId ?: JSONObject.NULL)
            put("positionMs", snapshot.playPositionMs)
            put("quickMode", snapshot.quickMode)
        }
        if (!preferences.edit().putString("session", data.toString()).commit())
            throw IOException("편집 내용을 기록하지 못했습니다. 저장 공간을 확인해 주세요.")
    }

    fun clear() {
        if (!preferences.edit().remove("session").commit())
            throw IOException("완료된 편집 기록을 정리하지 못했습니다.")
    }
}

