package com.r34v.r34v

import android.app.Activity
import android.os.Build
import android.os.Environment
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.StorageRoot
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoragePermissionUiTest : ComposeUiCase() {
    private fun resumed(f: ComposeFixture, activity: Activity): Boolean {
        var resumed = false
        f.ins.runOnMainSync {
            resumed = activity in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        }
        return resumed
    }

    @Test fun returningWithoutAllFilesPermissionKeepsDownloadLocationDialogUsable() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        assumeTrue("This flow requires all-files access to be denied", !Environment.isExternalStorageManager())
        ComposeFixture().use { f ->
            var activity: MainActivity? = null
            try {
                // Exercise a first-time location choice without moving or deleting real downloads.
                f.app.storage.markApplied(StorageRoot.Mode.PRIVATE)
                f.app.storage.choose(StorageRoot.Mode.PRIVATE)
                val running = f.launch(); activity = running; waitHome()
                ui.onNodeWithContentDescription("设置").performClick()
                val migrate = "改到公共目录（卸载后保留）"
                fun openLocation() {
                    ui.onNodeWithText("当前下载位置").performScrollTo().performClick()
                    waitText(migrate)
                }
                openLocation()
                ui.onNodeWithText(migrate).performScrollTo().performClick()
                ui.waitUntil(15000) { !resumed(f, running) }
                assertTrue(f.ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                ui.waitUntil(15000) { resumed(f, running) }
                waitText(migrate)
                assertFalse(Environment.isExternalStorageManager())
                ui.onNodeWithText("关闭").assertIsEnabled()
                ui.onNodeWithText(migrate).performScrollTo().assertIsEnabled()
                assertFalse(f.app.storage.changing)
                assertFalse(f.app.storage.pendingRestart)
                assertEquals(StorageRoot.Mode.PRIVATE, f.app.storage.applied)
                ui.onNodeWithText("关闭").performClick()
                ui.onNode(isDialog()).assertDoesNotExist()
                openLocation()
                ui.onNodeWithText("关闭").assertIsEnabled()
                ui.onNodeWithText("关闭").performClick()
            } finally {
                activity?.let { if (!resumed(f, it)) f.launch() }
            }
        }
    }
}
