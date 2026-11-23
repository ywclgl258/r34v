package com.r34v.r34v

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.r34v.r34v.data.UpgradeAsset
import com.r34v.r34v.data.cachedUpgrade
import com.r34v.r34v.data.downloadUpgrade
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpgradeTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val updates get() = temporary.root

    private fun assetFor(body: String, id: Long = 20, withDigest: Boolean = true) = UpgradeAsset(
        releaseId = 10,
        id = id,
        size = body.length.toLong(),
        sha256 = if (withDigest) body.encodeUtf8().sha256().hex() else null,
    )

    private fun files(asset: UpgradeAsset) = asset.cacheDirectory(updates).listFiles().orEmpty().toList()

    private suspend fun download(
        server: MockWebServer,
        asset: UpgradeAsset,
        client: OkHttpClient = OkHttpClient(),
        validate: (File) -> Unit = {},
    ) = downloadUpgrade(client, server.url("/releases").toString(), updates, asset, validate)

    @Test fun releaseDigestMayBeMissingOrNullButMustBeSha256WhenProvided() {
        val hash = "ab".repeat(32)
        val metadata = JsonObject().apply {
            addProperty("id", 20L)
            addProperty("size", 5L)
        }
        assertNull(UpgradeAsset.fromGitHub(10, metadata).sha256)
        metadata.add("digest", JsonNull.INSTANCE)
        assertNull(UpgradeAsset.fromGitHub(10, metadata).sha256)
        metadata.addProperty("digest", "sha256:${hash.uppercase()}")
        assertEquals(hash, UpgradeAsset.fromGitHub(10, metadata).sha256)
        metadata.addProperty("digest", "sha256:invalid")
        assertThrows(IllegalStateException::class.java) { UpgradeAsset.fromGitHub(10, metadata) }
        metadata.addProperty("digest", "sha512:$hash")
        assertThrows(IllegalStateException::class.java) { UpgradeAsset.fromGitHub(10, metadata) }
    }

    @Test fun progressingTransferCanOutlastThePageCallTimeout() = runBlocking {
        MockWebServer().use { server ->
            val body = "a continuously progressing update"
            val asset = assetFor(body)
            server.enqueue(MockResponse().setBody(body).throttleBody(1, 15, TimeUnit.MILLISECONDS))
            val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.MILLISECONDS).readTimeout(1, TimeUnit.SECONDS).build()
            val started = System.nanoTime()
            val file = download(server, asset, client)
            assertEquals(body, file.readText())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) > 120)
        }
    }

    @Test fun wrongLengthNeverCommitsAnApk() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("short"))
            val asset = assetFor("a much longer APK")
            val result = runCatching { download(server, asset) }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertTrue(files(asset).isEmpty())
        }
    }

    @Test fun wrongDigestAndInvalidPackageNeverCommitAnApk() = runBlocking {
        MockWebServer().use { server ->
            val asset = assetFor("expected")
            server.enqueue(MockResponse().setBody("replaced"))
            val wrongDigest = runCatching { download(server, asset) { error("APK parsed before digest check") } }
            assertEquals("安装包摘要与发布信息不符，请重新检查更新", wrongDigest.exceptionOrNull()?.message)
            assertTrue(files(asset).isEmpty())

            server.enqueue(MockResponse().setBody("expected"))
            val invalidPackage = runCatching { download(server, asset) { error("invalid APK") } }
            assertEquals("invalid APK", invalidPackage.exceptionOrNull()?.message)
            assertTrue(files(asset).isEmpty())
        }
    }

    @Test fun digestCacheRejectsSameSizeChangesAndMismatchedRemoteDigests() = runBlocking {
        MockWebServer().use { server ->
            val asset = assetFor("expected")
            server.enqueue(MockResponse().setBody("expected"))
            val published = download(server, asset)
            assertEquals(published, cachedUpgrade(updates, asset.copy()) { assertEquals(published, it) })
            assertNull(cachedUpgrade(updates, assetFor("replaced")) { fail("Expected a different digest") })
            assertNull(cachedUpgrade(updates, asset) { error("invalid APK") })
            published.writeText("replaced")
            assertNull(cachedUpgrade(updates, asset) { fail("Same-size changed bytes must be checked") })
        }
    }

    @Test fun legacyReleaseUsesThePinnedAssetAndPersistsItsVerifiedBytes() = runBlocking {
        MockWebServer().use { server ->
            val asset = assetFor("expected", withDigest = false)
            updates.resolve("r34v-update.apk").writeText("old-blob")
            val directory = asset.cacheDirectory(updates).apply { mkdirs() }
            val partial = File(directory, "download-orphan.apk").apply { writeText("expected") }
            assertNull(cachedUpgrade(updates, asset) { fail("No finalized APK is available") })

            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/blob")))
            server.enqueue(MockResponse().setBody("expected"))
            val published = download(server, asset)
            assertEquals("expected", published.readText())
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/releases/assets/20", request.path)
            assertEquals("application/octet-stream", request.getHeader("Accept"))
            assertEquals("/blob", requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).path)
            assertEquals(published, cachedUpgrade(updates, asset.copy()) {})
            assertEquals(published, cachedUpgrade(updates, assetFor("expected")) {})
            assertNull(cachedUpgrade(updates, asset.copy(id = 21)) { fail("A same-name reupload has a new asset ID") })
            assertNull(cachedUpgrade(updates, asset.copy(releaseId = 11)) { fail("Expected a different release") })
            published.writeText("replaced")
            assertNull(cachedUpgrade(updates, asset) { fail("Same-size changed bytes must be checked") })
            assertTrue(partial.isFile)
        }
    }

    @Test fun concurrentTransfersPublishOnlyAfterValidation() = runBlocking {
        MockWebServer().use { server ->
            val body = "expected"
            val asset = assetFor(body, withDigest = false)
            repeat(2) { server.enqueue(MockResponse().setBody(body)) }
            val validating = CountDownLatch(2)
            val publish = CountDownLatch(1)
            val validator: (File) -> Unit = { file ->
                check(file.readText() == body)
                validating.countDown()
                check(publish.await(5, TimeUnit.SECONDS))
            }
            val first = async(Dispatchers.IO) { download(server, asset, validate = validator) }
            val second = async(Dispatchers.IO) { download(server, asset, validate = validator) }
            try {
                assertTrue(validating.await(3, TimeUnit.SECONDS))
                assertEquals(2, files(asset).size)
                assertNull(cachedUpgrade(updates, asset) { fail("Validation has not completed") })
            } finally {
                publish.countDown()
            }
            assertEquals(first.await(), second.await())
            assertEquals(listOf(first.await()), files(asset))
        }
    }

    @Test fun cancellationClosesTheCallWhileTheBodyIsStalled() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("waiting").setBodyDelay(3, TimeUnit.SECONDS))
            val asset = assetFor("waiting")
            val canceled = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { canceled.countDown() }
            }).build()
            val job = launch(Dispatchers.IO) { download(server, asset, client) }
            withTimeout(2000) { while (files(asset).isEmpty()) delay(10) }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(canceled.await(1, TimeUnit.SECONDS))
            withTimeout(1000) { while (files(asset).isNotEmpty()) delay(10) }
            assertNull(cachedUpgrade(updates, asset) {})
        }
    }

    @Test fun transferStillTimesOutWhenTheBodyStopsProgressing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("waiting").setBodyDelay(1, TimeUnit.SECONDS))
            val asset = assetFor("waiting")
            val client = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build()
            val result = runCatching { download(server, asset, client) }
            assertTrue(result.exceptionOrNull() is IOException)
            assertTrue(files(asset).isEmpty())
        }
    }
}
