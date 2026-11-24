package com.r34v.r34v

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.StorageRoot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class StorageStartupTest {
    @Test fun failedLocationCommitPreservesCopiesAndPausesWritesUntilARetry() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "startup-storage-${System.nanoTime()}").apply { mkdirs() }
        val failNextCommit = AtomicBoolean(false)
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "private").apply { mkdirs() }

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val prefs = super.getSharedPreferences(name, mode)
                if (name != root.name) return prefs
                return object : SharedPreferences by prefs {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = prefs.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                            override fun remove(key: String) = apply { editor.remove(key) }
                            override fun commit(): Boolean {
                                val committed = editor.commit()
                                return committed && !failNextCommit.getAndSet(false)
                            }
                        }
                    }
                }
            }
        }
        try {
            val store = LibraryStore(context, root.name, "${root.name}-legacy")
            val shared = File(root, "shared")
            val storage = StorageRoot(context, store, shared)
            val oldCopy = File(storage.ensure(StorageRoot.Mode.PRIVATE).comics, "old.fixture").apply { writeText("private") }
            val newCopy = File(storage.ensure(StorageRoot.Mode.SHARED).comics, "new.fixture").apply { writeText("shared") }
            storage.requestOnRestart(StorageRoot.Mode.SHARED)

            failNextCommit.set(true)
            val failedStart = StorageRoot(context, store, shared)
            val notice = failedStart.applyPendingOnStart()
            assertFalse(notice.isNullOrBlank())
            assertEquals(notice, failedStart.status().note)
            assertEquals(StorageRoot.Mode.PRIVATE, failedStart.effective())
            assertFalse(failedStart.canWriteDownloads)
            assertEquals("private", oldCopy.readText())
            assertEquals("shared", newCopy.readText())

            failedStart.requestOnRestart(StorageRoot.Mode.SHARED)
            val retry = StorageRoot(context, store, shared)
            assertNull(retry.applyPendingOnStart())
            assertEquals(StorageRoot.Mode.SHARED, retry.effective())
            assertTrue(retry.canWriteDownloads)
        } finally {
            base.deleteSharedPreferences(root.name)
            base.deleteSharedPreferences("${root.name}-legacy")
            root.deleteRecursively()
        }
    }
}
