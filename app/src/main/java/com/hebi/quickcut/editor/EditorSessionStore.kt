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

class EditorSessionReadException(cause: Throwable? = null) : IOException(
    "이전 편집 기록을 읽지 못했습니다. 기존 기록을 보호하고 있습니다.", cause,
)

/** Crash/force-stop recovery for local selections with still-readable source URIs. */
class EditorSessionStore(context: Context, preferencesName: String = "quickcut_editor_session_v1") {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName, Context.MODE_PRIVATE,
    )
    private var lastVerifiedRaw: String? = null

    private fun requireReadableRecord() {
        val raw = try {
            preferences.getString("session", null)
        } catch (error: Exception) {
            throw EditorSessionReadException(error)
        }
        if ((raw != null && raw != lastVerifiedRaw) ||
            (raw == null && preferences.contains("session"))
        ) load()
    }

    fun load(): EditorSessionSnapshot? {
        val raw = try {
            preferences.getString("session", null)
        } catch (error: Exception) {
            throw EditorSessionReadException(error)
        }
        if (raw == null) {
            if (preferences.contains("session")) throw EditorSessionReadException()
            lastVerifiedRaw = null
            return null
        }
        val snapshot = try {
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
                    crop = clip.optJSONObject("crop")?.let {
                        CropRegion(
                            it.getDouble("left").toFloat(), it.getDouble("top").toFloat(),
                            it.getDouble("right").toFloat(), it.getDouble("bottom").toFloat(),
                        )
                    },
                    audio = clip.optJSONObject("audio")?.let { entry ->
                        AudioAdjustment(
                            volumePercent = entry.optInt("volumePercent", 100),
                            fadeInMs = entry.optLong("fadeInMs", 0L),
                            fadeOutMs = entry.optLong("fadeOutMs", 0L),
                        )
                    } ?: AudioAdjustment.DEFAULT,
                    textLayers = clip.optJSONArray("textLayers")?.let { arr ->
                        (0 until arr.length()).map { i ->
                            val entry = arr.getJSONObject(i)
                            TextLayer(
                                id = entry.getLong("id"),
                                text = entry.getString("text"),
                                startMs = entry.getLong("startMs"),
                                endMs = entry.getLong("endMs"),
                                x = entry.optDouble("x", 0.5).toFloat(),
                                y = entry.optDouble("y", 0.78).toFloat(),
                                size = entry.optInt("size", 100),
                                color = TextLayerColor.valueOf(entry.optString("color", "WHITE")),
                                alignment = TextLayerAlignment.valueOf(entry.optString("alignment", "CENTER")),
                                darkBackground = entry.optBoolean("darkBackground", false),
                            )
                        }.also { TextLayerPolicy.validate(it,
                            clip.getLong("end") - clip.getLong("start")) }
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
        } catch (error: Exception) {
            throw EditorSessionReadException(error)
        }
        lastVerifiedRaw = raw
        return snapshot
    }

    fun save(snapshot: EditorSessionSnapshot) {
        requireReadableRecord()
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
                put("crop", it.crop?.let { region -> JSONObject().apply {
                    put("left", region.left.toDouble())
                    put("top", region.top.toDouble())
                    put("right", region.right.toDouble())
                    put("bottom", region.bottom.toDouble())
                } } ?: JSONObject.NULL)
                put("audio", JSONObject().apply {
                    put("volumePercent", it.audio.volumePercent)
                    put("fadeInMs", it.audio.fadeInMs)
                    put("fadeOutMs", it.audio.fadeOutMs)
                })
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
            put("schemaVersion", 5)
            put("clips", array)
            put("selectedId", snapshot.selectedId ?: JSONObject.NULL)
            put("positionMs", snapshot.playPositionMs)
            put("quickMode", snapshot.quickMode)
        }
        val encoded = data.toString()
        if (!preferences.edit().putString("session", encoded).commit())
            throw IOException("편집 내용을 기록하지 못했습니다. 저장 공간을 확인해 주세요.")
        lastVerifiedRaw = encoded
    }

    fun clear() {
        requireReadableRecord()
        if (!preferences.edit().remove("session").commit())
            throw IOException("완료된 편집 기록을 정리하지 못했습니다.")
        lastVerifiedRaw = null
    }

    /** The user must explicitly confirm this irreversible action in the recovery UI. */
    fun resetUnreadableRecord() {
        val unreadable = try {
            load()
            false
        } catch (_: EditorSessionReadException) {
            true
        }
        check(unreadable) { "정상 편집 기록은 손상 기록 초기화로 삭제할 수 없습니다." }
        if (!preferences.edit().remove("session").commit())
            throw IOException("손상된 편집 기록을 정리하지 못했습니다.")
        lastVerifiedRaw = null
    }
}

