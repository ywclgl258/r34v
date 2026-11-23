@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.LibraryStore
import com.r34v.r34v.data.Network
import com.r34v.r34v.playback.LinkSpeed
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LinkSpeedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = ByteArrayDataSource(byteArrayOf(0))
    private val media = DataSpec.Builder().setUri("https://example.com/media.mp4").build()

    @Test fun media3DefaultsAndShortTransfersDoNotBecomeAppEstimates() {
        val link = LinkSpeed(context) { 0L }
        val meter = link.meter
        assertTrue(meter.bitrateEstimate > 0)
        assertEquals(0, link.estimateKbps())
        val listener = checkNotNull(meter.transferListener)

        fun transfer(spec: DataSpec, bytes: Int, isNetwork: Boolean) {
            listener.onTransferInitializing(source, spec, isNetwork)
            listener.onTransferStart(source, spec, isNetwork)
            listener.onBytesTransferred(source, spec, isNetwork, bytes)
            SystemClock.sleep(10)
            listener.onTransferEnd(source, spec, isNetwork)
        }
        transfer(media, 1024, true)
        transfer(media, 128 * 1024, false)
        transfer(DataSpec.Builder().setUri("https://example.com/master.m3u8").build(), 128 * 1024, true)
        assertEquals(0, link.estimateKbps())
        transfer(media, 128 * 1024, true)
        assertTrue(link.estimateKbps() > 0)
    }

    @Test fun aRouteResetRejectsLatePlaybackAndProbeSamplesAndReplacesTheMedia3Meter() {
        val network = Network(context, LibraryStore(context, "link-speed-${System.nanoTime()}", "link-speed-legacy"))
        val link = LinkSpeed(context, network::routeEpoch)
        val oldClient = network.client
        val oldEpoch = link.routeEpoch()
        val oldMeter = link.meter
        assertSame(oldMeter, link.meter)
        assertTrue(link.seed(1200, oldEpoch))
        val listener = checkNotNull(oldMeter.transferListener)
        // The socket has started opening. Its headers may not arrive until after the route switch.
        listener.onTransferInitializing(source, media, true)
        network.invalidate()
        listener.onTransferStart(source, media, true)
        listener.onBytesTransferred(source, media, true, 128 * 1024)
        SystemClock.sleep(10)
        listener.onTransferEnd(source, media, true)
        assertFalse(link.seed(9000, oldEpoch))
        assertEquals(0, link.estimateKbps())
        assertNotSame(oldClient, network.client)
        assertNotSame(oldMeter, link.meter)
        assertTrue(link.seed(1800, link.routeEpoch()))
        assertEquals(1800, link.estimateKbps())
    }

    @Test fun defaultNetworkCallbacksResetEstimatesOncePerPathChange() {
        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        val active = connectivity.activeNetwork
        assumeNotNull(active)
        val path = checkNotNull(active)
        val network = Network(context, LibraryStore(context, "link-route-${System.nanoTime()}", "link-speed-legacy"))
        val link = LinkSpeed(context, network::routeEpoch)
        network.defaultNetworkAvailable(path)
        val connected = link.routeEpoch()
        assertTrue(link.seed(2400, connected))
        network.defaultNetworkAvailable(path)
        assertEquals(connected, link.routeEpoch())
        assertEquals(2400, link.estimateKbps())
        network.defaultNetworkLost(path)
        assertEquals(0, link.estimateKbps())
        val disconnected = link.routeEpoch()
        assertTrue(disconnected > connected)
        network.defaultNetworkLost(path)
        assertEquals(disconnected, link.routeEpoch())
        network.defaultNetworkAvailable(path)
        assertTrue(link.routeEpoch() > disconnected)
        assertFalse(link.seed(2400, connected))
    }

    @Test fun theProductionPlaybackSourceDeliversNetworkTransferSamples() {
        val app = context.applicationContext as R34Application
        val link = LinkSpeed(context, app.network::routeEpoch)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4")
                .setBody(Buffer().write(ByteArray(128 * 1024)))
                .throttleBody(16 * 1024L, 15, TimeUnit.MILLISECONDS))
            val spec = DataSpec.Builder().setUri(server.url("/link-speed-${System.nanoTime()}.mp4").toString()).build()
            val playbackSource = app.playback.sourceFactory().createDataSource()
            playbackSource.addTransferListener(checkNotNull(link.meter.transferListener))
            try {
                playbackSource.open(spec)
                val buffer = ByteArray(16 * 1024)
                while (playbackSource.read(buffer, 0, buffer.size) != -1) { }
            } finally {
                playbackSource.close()
            }
            assertTrue(link.estimateKbps() > 0)
        }
    }
}
