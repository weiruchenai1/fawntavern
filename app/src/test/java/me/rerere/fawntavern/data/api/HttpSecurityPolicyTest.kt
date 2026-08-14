package me.rerere.fawntavern.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpSecurityPolicyTest {
    @Test
    fun permitsCleartextOnlyForLocalDevelopmentHosts() {
        assertTrue(Http.permitsCleartext("localhost"))
        assertTrue(Http.permitsCleartext("127.0.0.1"))
        assertTrue(Http.permitsCleartext("10.0.2.2"))
        assertFalse(Http.permitsCleartext("192.168.1.20"))
        assertFalse(Http.permitsCleartext("api.example.com"))
    }
}
