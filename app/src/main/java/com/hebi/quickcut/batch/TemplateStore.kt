package com.hebi.quickcut.batch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A small local-only template store. Never persists source deletion choices.
 * Recreating the app should not enable source deletion automatically.
 */
class TemplateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "quickcut_batch_templates_v1", Context.MODE_PRIVATE,
    )

    fun load(): List<BatchTemplate> {
        val raw = prefs.getString("templates", null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                runCatching {
                    val item = json.getJSONObject(index)
                    BatchTemplate(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        mode = BatchClipMode.valueOf(item.getString("mode")),
                        leadingMs = item.getLong("leadingMs"),
                        trailingMs = item.getLong("trailingMs"),
                        muteAudio = item.optBoolean("muteAudio"),
                    ).takeIf { it.checkValidity() == null }
                }.getOrNull()
            }.take(30)
        }.getOrDefault(emptyList())
    }

    fun save(template: BatchTemplate): List<BatchTemplate> {
        val error = template.checkValidity()
        require(error == null) { error ?: "잘못된 템플릿입니다." }
        val existing = load()
        val next = if (existing.any { it.id == template.id }) {
            existing.map { if (it.id == template.id) template else it }
        } else {
            require(existing.size < 30) { "템플릿은 최대 30개까지 저장할 수 있습니다." }
            existing + template
        }
        write(next)
        return next
    }

    fun remove(id: String): List<BatchTemplate> {
        val next = load().filterNot { it.id == id }
        write(next)
        return next
    }

    private fun write(templates: List<BatchTemplate>) {
        val array = JSONArray()
        templates.forEach {
            array.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("mode", it.mode.name)
                put("leadingMs", it.leadingMs)
                put("trailingMs", it.trailingMs)
                put("muteAudio", it.muteAudio)
            })
        }
        check(prefs.edit().putString("templates", array.toString()).commit()) {
            "템플릿 저장에 실패했습니다."
        }
    }
}
