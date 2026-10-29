package com.hebi.quickcut.batch

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TemplateRemovalInstrumentedTest {
    @Test fun failedCommitRetainsTemplatesSelectionAndDraftAndCanBeRetried() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = FailingTemplateContext(base)
        val app = object : Application() {
            init { attachBaseContext(context) }
        }
        var model: BatchViewModel? = null
        try {
            val selected = BatchTemplate(id = "selected", name = "선택한 템플릿",
                mode = BatchClipMode.KEEP_FIRST, leadingMs = 2_000L)
            val other = selected.copy(id = "other", name = "다른 템플릿")
            TemplateStore(context).save(selected)
            TemplateStore(context).save(other)
            withContext(Dispatchers.Main) {
                val subject = BatchViewModel(app).also { model = it }
                subject.loadTemplates(context)
                subject.chooseTemplate(selected.id)
                subject.draftName = "저장하지 않은 편집값"
                subject.draftLeadingSeconds = "4"
                val draft = subject.previewTemplate()

                context.failTemplateCommit = true
                subject.removeTemplate(context)

                assertEquals(listOf(selected, other), subject.templates)
                assertEquals(selected.id, subject.selectedTemplateId)
                assertEquals(draft, subject.previewTemplate())
                assertNotNull("A failed deletion must be reported", subject.notification)
                assertEquals(listOf(selected, other), TemplateStore(context).load())

                context.failTemplateCommit = false
                subject.removeTemplate(context)
                assertEquals(listOf(other), subject.templates)
                assertNull(subject.selectedTemplateId)
                assertEquals("저장하지 않은 편집값", subject.draftName)
                assertEquals("4", subject.draftLeadingSeconds)
                assertEquals(listOf(other), TemplateStore(context).load())
            }
        } finally {
            withContext(Dispatchers.Main) {
                model?.let { ViewModelStore().apply { put("batch", it) }.clear() }
            }
            context.cleanUp()
        }
    }

    /** Only the template disk commit fails; normal preferences and ViewModel code stay real. */
    private class FailingTemplateContext(base: Context) : ContextWrapper(base) {
        private val prefix = "template_failure_${UUID.randomUUID()}_"
        private val preferenceNames = mutableSetOf<String>()
        private val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        var failTemplateCommit = false

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            preferenceNames += isolatedName
            val real = baseContext.getSharedPreferences(isolatedName, mode)
            if (name != "quickcut_batch_templates_v1") return real
            return object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor {
                    val editor = real.edit()
                    return object : SharedPreferences.Editor by editor {
                        // Preserve the wrapper across TemplateStore's fluent putString().commit().
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }
                        override fun commit(): Boolean = !failTemplateCommit && editor.commit()
                    }
                }
            }
        }

        fun cleanUp() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            directory.deleteRecursively()
        }
    }
}
