package com.hebi.quickcut.settings

import android.content.Context
import com.hebi.quickcut.batch.BatchClipMode
import com.hebi.quickcut.media.SaveDateMode

data class QuickTrimSettings(
    val leadingMs: Long = 4_000L,
    val trailingMs: Long = 0L,
)

data class BatchDraftSettings(
    val name: String = "앞뒤 3초 삭제",
    val mode: BatchClipMode = BatchClipMode.REMOVE_EDGES,
    val leadingMs: Long = 3_000L,
    val trailingMs: Long = 3_000L,
    val muteAudio: Boolean = false,
    val selectedTemplateId: String? = null,
)

/**
 * Last-used non-destructive preferences. Source deletion is deliberately excluded.
 * Named batch templates remain in the existing TemplateStore.
 */
class QuickCutSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "quickcut_last_settings_v1", Context.MODE_PRIVATE,
    )

    fun loadQuick(): QuickTrimSettings = QuickTrimSettings(
        leadingMs = preferences.getLong("quick_leading", 4_000L).coerceIn(0L, 86_400_000L),
        trailingMs = preferences.getLong("quick_trailing", 0L).coerceIn(0L, 86_400_000L),
    )

    fun saveQuick(value: QuickTrimSettings) {
        preferences.edit()
            .putLong("quick_leading", value.leadingMs.coerceIn(0L, 86_400_000L))
            .putLong("quick_trailing", value.trailingMs.coerceIn(0L, 86_400_000L))
            .apply()
    }

    fun loadSaveDate(): SaveDateMode = runCatching {
        SaveDateMode.valueOf(
            preferences.getString("save_date_mode", SaveDateMode.CURRENT.name)!!
        )
    }.getOrDefault(SaveDateMode.CURRENT)

    fun saveSaveDate(mode: SaveDateMode) {
        preferences.edit().putString("save_date_mode", mode.name).apply()
    }

    fun loadBatch(): BatchDraftSettings = BatchDraftSettings(
        name = preferences.getString("batch_name", "앞뒤 3초 삭제")
            ?.take(40)?.takeIf { it.isNotBlank() } ?: "앞뒤 3초 삭제",
        mode = runCatching {
            BatchClipMode.valueOf(
                preferences.getString("batch_mode", BatchClipMode.REMOVE_EDGES.name)!!
            )
        }.getOrDefault(BatchClipMode.REMOVE_EDGES),
        leadingMs = preferences.getLong("batch_leading", 3_000L).coerceIn(0L, 7_200_000L),
        trailingMs = preferences.getLong("batch_trailing", 3_000L).coerceIn(0L, 7_200_000L),
        muteAudio = preferences.getBoolean("batch_mute", false),
        selectedTemplateId = preferences.getString("batch_template_id", null),
    )

    fun saveBatch(value: BatchDraftSettings) {
        preferences.edit()
            .putString("batch_name", value.name.take(40))
            .putString("batch_mode", value.mode.name)
            .putLong("batch_leading", value.leadingMs.coerceIn(0L, 7_200_000L))
            .putLong("batch_trailing", value.trailingMs.coerceIn(0L, 7_200_000L))
            .putBoolean("batch_mute", value.muteAudio)
            .apply {
                if (value.selectedTemplateId == null) remove("batch_template_id")
                else putString("batch_template_id", value.selectedTemplateId)
            }
            .apply()
    }
}
