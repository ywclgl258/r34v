package com.r34v.r34v

import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.GalleryLoginRequiredException
import com.r34v.r34v.downloads.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class DownloadRecoveryTest {
    private val now = 1_767_225_600_000L

    @Test fun temporaryResponsesRetryButTerminalResponsesDoNot() {
        listOf(500, 502, 503, 504).forEach { status ->
            assertTrue(DownloadRecovery.classify(GalleryResponseException(status, message = "temporary"), GalleryDownloadStage.DOWNLOADING_PAGE).retryable)
        }
        listOf(401, 403, 404, 410, 509).forEach { status ->
            assertFalse(DownloadRecovery.classify(GalleryResponseException(status, message = "terminal"), GalleryDownloadStage.DOWNLOADING_PAGE).retryable)
        }
        assertEquals(GalleryFailureKind.AUTH, DownloadRecovery.classify(GalleryResponseException(403, message = "forbidden"), GalleryDownloadStage.DOWNLOADING_PAGE).kind)
        assertEquals(GalleryFailureKind.NOT_FOUND, DownloadRecovery.classify(GalleryResponseException(404, message = "missing"), GalleryDownloadStage.DOWNLOADING_PAGE).kind)
        assertEquals(GalleryFailureKind.QUOTA, DownloadRecovery.classify(GalleryResponseException(509, message = "quota"), GalleryDownloadStage.DOWNLOADING_PAGE).kind)
    }

    @Test fun retryAfterAcceptsSecondsAndHttpDates() {
        assertEquals(60_000L, DownloadRecovery.retryAfterMs("60", now))
        assertEquals(3_600_000L, DownloadRecovery.retryAfterMs("Thu, 01 Jan 2026 01:00:00 GMT", now))
        assertEquals(0L, DownloadRecovery.retryAfterMs("Thu, 01 Jan 2026 00:00:00 GMT", now))
        assertNull(DownloadRecovery.retryAfterMs("not-a-date", now))
    }

    @Test fun longRateLimitWaitPersistsInsteadOfHoldingTheWorker() {
        val failure = DownloadRecovery.classify(GalleryResponseException(429, "120", "limited"), GalleryDownloadStage.DOWNLOADING_PAGE, now)
        assertEquals(GalleryFailureKind.RATE_LIMIT, failure.kind)
        assertEquals(now + 120_000, failure.retryAt)
        assertNull(DownloadRecovery.automaticDelayMs(failure, now))
        val short = failure.copy(retryAt = now + 5_000)
        assertEquals(5_000L, DownloadRecovery.automaticDelayMs(short, now))
    }

    @Test fun tlsStorageAndCancellationKeepDistinctRecoveryPaths() {
        assertEquals(GalleryFailureKind.TLS, DownloadRecovery.classify(SSLHandshakeException("certificate"), GalleryDownloadStage.DOWNLOADING_PAGE).kind)
        assertTrue(DownloadRecovery.classify(SSLException("record read"), GalleryDownloadStage.DOWNLOADING_PAGE).retryable)
        assertEquals(GalleryFailureKind.STORAGE, DownloadRecovery.classify(IOException("disk full"), GalleryDownloadStage.COMMITTING).kind)
        assertEquals(GalleryFailureKind.AUTH, DownloadRecovery.classify(GalleryLoginRequiredException("login"), GalleryDownloadStage.PREPARING).kind)
        assertThrows(CancellationException::class.java) { throw CancellationException("user cancelled") }
    }

    @Test fun onlyConnectionLevelTlsFailuresCanRefresh() {
        val reset = SSLHandshakeException("Read error: ssl=0x1: I/O error during system call, Connection reset by peer")
        assertTrue(DownloadRecovery.isTlsConnectionInterruption(reset))
        assertTrue(DownloadRecovery.classify(reset, GalleryDownloadStage.DOWNLOADING_PAGE).retryable)
        val closed = SSLHandshakeException("connection closed").initCause(java.io.EOFException("connection closed"))
        assertTrue(DownloadRecovery.isTlsConnectionInterruption(closed))
        assertTrue(DownloadRecovery.classify(closed, GalleryDownloadStage.DOWNLOADING_PAGE).retryable)
        val timeout = SSLHandshakeException("Handshake failed").initCause(java.net.SocketTimeoutException("Read timed out"))
        assertTrue(DownloadRecovery.isTlsConnectionInterruption(timeout))
        val untrusted = SSLHandshakeException("PKIX failure").initCause(java.security.cert.CertPathValidatorException("Untrusted chain"))
        assertFalse(DownloadRecovery.isTlsConnectionInterruption(untrusted))
        assertEquals(GalleryFailureKind.TLS, DownloadRecovery.classify(untrusted, GalleryDownloadStage.DOWNLOADING_PAGE).kind)
        assertFalse(DownloadRecovery.classify(untrusted, GalleryDownloadStage.DOWNLOADING_PAGE).retryable)
        val wrappedCertificate = SSLException("read failed").initCause(java.security.cert.CertificateException("expired"))
        assertFalse(DownloadRecovery.isTlsConnectionInterruption(wrappedCertificate))
        assertFalse(DownloadRecovery.classify(wrappedCertificate, GalleryDownloadStage.DOWNLOADING_PAGE).retryable)
        assertFalse(DownloadRecovery.isTlsConnectionInterruption(IOException("plain network failure")))
    }

    @Test fun boundedBackoffDoesNotGrowWithoutLimit() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L), (1..DownloadRecovery.MAX_ATTEMPTS).map(DownloadRecovery::backoffMs))
        assertEquals(4_000L, DownloadRecovery.backoffMs(100))
    }
}
