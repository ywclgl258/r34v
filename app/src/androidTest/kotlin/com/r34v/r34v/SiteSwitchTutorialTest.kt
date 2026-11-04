package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SiteSwitchTutorialTest : ComposeUiCase() {
    @Test fun eligibilityDistinguishesNewInstallUpgradeAndLegacyMigration() = ComposeFixture().use { f ->
        val context = f.ins.targetContext; val prefix = "compose-tutorial-${System.nanoTime()}"; val previous = SiteRegistry.current
        val names = listOf("new", "upgrade", "legacy").map { "$prefix-$it" }
        try {
            val fresh = LibraryStore(context, names[0], "${names[0]}-old"); assertTrue(fresh.needsSiteSwitchTutorial)
            assertTrue(LibraryStore(context, names[0], "${names[0]}-old").needsSiteSwitchTutorial)
            assertTrue(fresh.consumeSiteSwitchTutorial()); assertFalse(fresh.consumeSiteSwitchTutorial()); fresh.switchSite("hanime1"); assertFalse(fresh.needsSiteSwitchTutorial)
            assertFalse(LibraryStore(context, names[0], "${names[0]}-old").needsSiteSwitchTutorial)
            context.getSharedPreferences(names[1], 0).edit().putBoolean("kotlin_migration_v1", true).commit(); assertFalse(LibraryStore(context, names[1], "${names[1]}-old").needsSiteSwitchTutorial)
            context.getSharedPreferences("${names[2]}-old", 0).edit().putString("flutter.settings_v1_quality", "720p").commit(); assertFalse(LibraryStore(context, names[2], "${names[2]}-old").needsSiteSwitchTutorial)
        } finally { SiteRegistry.current = previous; names.forEach { context.deleteSharedPreferences(it); context.deleteSharedPreferences("$it-old") } }
    }
    @Test fun firstUseOpensRealSiteListAndNeverReplaysAfterSwitchOrRestart() = ComposeFixture().use { f ->
        f.app.library.prefs.edit().putBoolean(LibraryStore.SITE_SWITCH_TUTORIAL_SEEN, false).commit()
        f.launch(); waitText("这里可以切换站点"); f.shot("13-first-use"); clickText("打开站点列表"); clickText(SiteRegistry.HANIME1.name); waitHome()
        assertFalse(f.app.library.needsSiteSwitchTutorial); f.launch(); waitHome(); ui.onNodeWithText("这里可以切换站点").assertDoesNotExist()
    }
    @Test fun dismissAndBackAreBothFinal() = ComposeFixture().use { f ->
        for (useBack in listOf(false, true)) {
            f.app.library.prefs.edit().putBoolean(LibraryStore.SITE_SWITCH_TUTORIAL_SEEN, false).commit(); f.launch(); waitText("这里可以切换站点")
            if (useBack) back(f) else clickText("知道了")
            ui.waitUntil(5000) { ui.onAllNodesWithText("这里可以切换站点").fetchSemanticsNodes().isEmpty() }
            f.launch(); waitHome(); ui.onNodeWithText("这里可以切换站点").assertDoesNotExist()
        }
    }
    @Test fun recreationDoesNotRepeatAnAlreadyVisibleTip() = ComposeFixture().use { f ->
        f.app.library.prefs.edit().putBoolean(LibraryStore.SITE_SWITCH_TUTORIAL_SEEN, false).commit(); val activity = f.launch(); waitText("这里可以切换站点")
        ui.runOnIdle { activity.recreate() }; waitHome(); ui.onNodeWithText("这里可以切换站点").assertDoesNotExist(); assertFalse(f.app.library.needsSiteSwitchTutorial)
    }
    @Test fun offlineFirstUseStillOffersSiteSwitching() = ComposeFixture().use { f ->
        f.offline = true; f.app.library.prefs.edit().putBoolean(LibraryStore.SITE_SWITCH_TUTORIAL_SEEN, false).commit(); f.launch(); waitText("这里可以切换站点")
        clickText("打开站点列表"); waitText(SiteRegistry.HANIME1.name)
    }
}
