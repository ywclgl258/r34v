package com.r34v.r34v

import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val password = "fixture-backup-pass".toCharArray()
    private fun document() = LibraryBackup(sites = listOf(SiteBackup("r34v", favorites = listOf(Favorite("video", "1", "测试收藏", site = "r34v")))))
    @Test fun encryptedBackupCanBeReadWithoutADeviceKeystore() {
        val original = document(); val bytes = BackupCodec.encode(original, password)
        assertFalse(String(bytes, Charsets.UTF_8).contains("测试收藏"))
        assertEquals(original, BackupCodec.decode(bytes, password))
    }
    @Test fun everyExportUsesFreshSaltAndNonce() {
        val value = document()
        assertFalse(BackupCodec.encode(value, password).contentEquals(BackupCodec.encode(value, password)))
    }
    @Test fun wrongPasswordCannotProduceAPreview() {
        val bytes = BackupCodec.encode(document(), password)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(bytes, "wrong-pass".toCharArray()) }
    }
    @Test fun tamperedPayloadIsRejectedBeforeParsing() {
        val bytes = BackupCodec.encode(document(), password); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(bytes, password) }
    }
    @Test fun shortFilesAndUnknownFormatsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(ByteArray(12), password) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(ByteArray(80), password) }
    }
    @Test fun unsupportedSchemaAndCredentialsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { validateBackup(document().copy(schema = 2)) }
        assertThrows(IllegalArgumentException::class.java) { validateBackup(document().copy(settings = mapOf("native_cookies" to "secret"))) }
    }
    @Test fun crossSiteRecordAndForeignUrlsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { validateBackup(LibraryBackup(sites = listOf(SiteBackup("r34v", watches = listOf(Watch("1", site = "hanime1")))))) }
        assertThrows(IllegalArgumentException::class.java) { validateBackup(LibraryBackup(sites = listOf(SiteBackup("r34v", watches = listOf(Watch("1", path = "https://example.com/1", site = "r34v")))))) }
    }
    @Test fun weakPasswordIsRejectedAtExport() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(document(), "short".toCharArray()) }
    }
    @Test fun localImageFavoritesRoundTripByBookAndPage() {
        val id = "a".repeat(64)
        val video = Video(id, "本地漫画", "r34local://$id", site = "local", mediaKind = MediaKind.GALLERY)
        val favorite = ImageFavorite(video, 2, "${video.url}/2")
        val backup = LibraryBackup(personal = PersonalLibrary(imageFavorites = listOf(favorite)))
        assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup, password), password))
        for (invalid in listOf(favorite.copy(pageUrl = "${video.url}/3"), favorite.copy(pageUrl = "r34local://${"b".repeat(64)}/2"),
            favorite.copy(imageUrl = "file:///private/image.png"))) {
            assertThrows(IllegalArgumentException::class.java) { validateBackup(backup.copy(personal = backup.personal.copy(imageFavorites = listOf(invalid)))) }
        }
    }
}
