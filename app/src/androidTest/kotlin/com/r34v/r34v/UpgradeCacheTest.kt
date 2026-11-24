package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpgradeCacheTest {
    @Test fun oldPackagesAreRemovedWithoutDeletingTheCurrentPackage() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val updates = File(base.cacheDir, "upgrade-regression-${System.nanoTime()}").apply { mkdirs() }
        val pending = PendingUpgrade("v2.12.1", UpgradeAsset(10, 20, 4, "cd".repeat(32)))
        val current = pending.file(updates).apply { parentFile!!.mkdirs(); writeText("keep") }
        val old = File(updates, "apk/10-21/${"ab".repeat(32)}.apk").apply { parentFile!!.mkdirs(); writeText("old") }
        val partial = File(updates, "apk/10-20/download-active.apk").apply { parentFile!!.mkdirs(); writeText("partial") }
        val unrelated = File(updates, "export.cbz").apply { writeText("keep") }
        try {
            pruneUpgradeCache(updates, current, System.currentTimeMillis())
            assertTrue(current.isFile)
            assertFalse(old.exists())
            assertFalse(partial.exists())
            assertTrue(unrelated.isFile)
        } finally { updates.deleteRecursively() }
    }
}
