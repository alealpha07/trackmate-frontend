package com.example.trackmate.posts

import com.example.trackmate.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinkTest {
    private val base = "https://backend.trackmate.alpha-hub.net"

    @Test
    fun postIdFromALinkToTheBackend() {
        assertEquals(42, sharedPostId("$base/p/42", base))
        assertEquals(42, sharedPostId("$base/p/42/", base))
        assertEquals(42, sharedPostId("$base/p/42?utm_source=chat#top", base))
        assertEquals(42, sharedPostId("http://backend.trackmate.alpha-hub.net/p/42", base))
    }

    @Test
    fun anythingElseIsNull() {
        for (link in listOf(
            "https://example.com/p/42",
            "https://backend.trackmate.alpha-hub.net.example.com/p/42",
            "$base/p/abc",
            "$base/p/",
            "$base/x/42",
            "$base/p/42/image",
            "$base/p/99999999999",
            "not a link",
            "https://bad host/p/1",
            "/p/42",
        )) {
            assertNull(link, sharedPostId(link, base))
        }
    }

    @Test
    fun sharedUrlsOpenInTheApp() {
        assertEquals(7, sharedPostId(sharedPostUrl(7), BuildConfig.BASE_URL))
    }
}
