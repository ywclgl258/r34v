package com.r34v.r34v

import com.r34v.r34v.data.readResponse
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ResponseReadTest {
    @Test fun stalledBodyFailsInsteadOfReturningAnApparentlySuccessfulResponse() = runBlocking<Unit> {
        val failed = AtomicBoolean()
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("slow body").setBodyDelay(1, TimeUnit.SECONDS)); start() }
        val client = OkHttpClient.Builder().readTimeout(120, TimeUnit.MILLISECONDS).eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) { failed.set(true) }
        }).build()
        try {
            try { client.readResponse(Request.Builder().url(server.url("/detail")).build()) { it.body!!.string() }; fail("Body timeout must propagate") }
            catch (_: IOException) { assertTrue(failed.get()) }
        } finally { server.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun leavingARequestCancelsItsBodyDownloadImmediately() = runBlocking<Unit> {
        val headers = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("slow body").setBodyDelay(1, TimeUnit.SECONDS)); start() }
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) { headers.countDown() }
            override fun canceled(call: Call) { cancelled.countDown() }
        }).build()
        try {
            val work = launch(Dispatchers.Default) { client.readResponse(Request.Builder().url(server.url("/cover")).build()) { it.body!!.string() } }
            assertTrue(headers.await(2,TimeUnit.SECONDS)); work.cancelAndJoin()
            assertTrue("Cancellation must close the network call while its body is pending", cancelled.await(300,TimeUnit.MILLISECONDS))
        } finally { server.shutdown(); client.connectionPool.evictAll() }
    }
}
