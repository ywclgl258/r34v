package com.r34v.r34v

import com.r34v.r34v.data.downloadUpgrade
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpgradeTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = temporary.root.resolve("update.apk")

    @Test fun progressingTransferCanOutlastThePageCallTimeout() = runBlocking {
        MockWebServer().use { server ->
            val body = "a continuously progressing update"
            server.enqueue(MockResponse().setBody(body).throttleBody(1, 15, TimeUnit.MILLISECONDS))
            val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.MILLISECONDS).readTimeout(1, TimeUnit.SECONDS).build()
            val started = System.nanoTime()
            downloadUpgrade(client, server.url("/update").toString(), file(), body.length.toLong())
            assertEquals(body, file().readText())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) > 120)
        }
    }
    @Test fun wrongLengthNeverCommitsAnApk() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("short"))
            val result = runCatching { downloadUpgrade(OkHttpClient(), server.url("/").toString(), file(), 500) }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertFalse(file().exists()); assertFalse(file().resolveSibling("update.apk.part").exists())
        }
    }
    @Test fun cancellationClosesTheCallWhileTheBodyIsStalled() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("waiting").setBodyDelay(1, TimeUnit.SECONDS))
            val headers = CountDownLatch(1); val canceled = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) { headers.countDown() }
                override fun canceled(call: Call) { canceled.countDown() }
            }).build()
            val job = launch(Dispatchers.IO) { downloadUpgrade(client, server.url("/").toString(), file(), 7) }
            assertTrue(headers.await(2, TimeUnit.SECONDS))
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(canceled.await(1, TimeUnit.SECONDS))
            withTimeout(1000) { while (file().resolveSibling("update.apk.part").exists()) delay(10) }
            assertFalse(file().exists())
        }
    }
    @Test fun transferStillTimesOutWhenTheBodyStopsProgressing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("waiting").setBodyDelay(1, TimeUnit.SECONDS))
            val client = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build()
            val result = runCatching { downloadUpgrade(client, server.url("/").toString(), file(), 7) }
            assertTrue(result.exceptionOrNull() is IOException)
            assertFalse(file().exists()); assertFalse(file().resolveSibling("update.apk.part").exists())
        }
    }
}
