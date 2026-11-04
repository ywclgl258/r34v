package com.r34v.r34v

import com.r34v.r34v.data.adapter.EhLogin
import org.junit.Assert.*
import org.junit.Test

class EhLoginTest {
    @Test fun onlyOfficialHttpsTopLevelPagesAreAllowed() {
        assertTrue(EhLogin.allowed(EhLogin.officialUrl)); assertTrue(EhLogin.allowed("https://exhentai.org/"))
        assertFalse(EhLogin.allowed("http://forums.e-hentai.org/index.php")); assertFalse(EhLogin.allowed("https://forums.e-hentai.org.attacker.invalid/"))
        assertFalse(EhLogin.allowed("javascript:alert(1)")); assertFalse(EhLogin.allowed("file:///sdcard/file.html"))
    }
    @Test fun collectOnlyRequiredCookieFieldsFromOfficialOrigins() {
        val values = EhLogin.credentials(listOf(null, "ipb_member_id=12345; other=discard; ipb_pass_hash=0123456789abcdef0123456789abcdef", "igneous=fixture"))
        assertEquals(setOf("ipb_member_id", "ipb_pass_hash", "igneous"), values.keys); assertTrue(EhLogin.hasIdentity(values))
    }
    @Test fun blankGuestAndIncompleteCookiesDoNotMeanLoggedIn() {
        assertFalse(EhLogin.hasIdentity(emptyMap())); assertFalse(EhLogin.hasIdentity(mapOf("ipb_member_id" to "0", "ipb_pass_hash" to "0123456789abcdef")))
        assertFalse(EhLogin.hasIdentity(mapOf("ipb_member_id" to "12345")))
        assertFalse(EhLogin.hasIdentity(mapOf("ipb_member_id" to "12345", "ipb_pass_hash" to "bad")))
    }
    @Test fun browserHttp2CookieHeaderCanBePastedInLowercase() {
        val cookies = com.r34v.r34v.data.adapter.EhProtocol.sessionCookies(com.r34v.r34v.data.SiteRegistry.EXHENTAI,
            "cookie: ipb_member_id=12345; ipb_pass_hash=0123456789abcdef0123456789abcdef")
        assertEquals(setOf("ipb_member_id", "ipb_pass_hash"), cookies.map { it.name }.toSet())
    }
}
