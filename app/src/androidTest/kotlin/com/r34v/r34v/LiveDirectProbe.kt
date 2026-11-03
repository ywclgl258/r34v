package com.r34v.r34v

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.Network
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveDirectProbe {
    @Test fun freshDirectRequestBypassesCachesAndAppProxy() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LibraryStore(context, "live_direct_probe", "no_legacy_live_probe")
        store.prefs.edit().clear().commit()
        store.set("direct_ip", true); store.set("doh", true); store.set("player_proxy", "")
        val report = Network(context, store).diagnose()
        File(context.filesDir, "direct-probe.txt").writeText(report)
        assertTrue(report, report.contains("实时请求成功") && report.contains("直连"))
    }
}
