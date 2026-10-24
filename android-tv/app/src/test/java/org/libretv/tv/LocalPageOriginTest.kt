package org.libretv.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPageOriginTest {
    @Test fun freshInstallAndInvalidAddressesUseLocalIdentity() {
        for (value in listOf(null, "", "file:///tmp/index.html", "javascript:alert(1)", "http://user:secret@example.test/tv"))
            assertEquals("http://127.0.0.1:8080", localPageOrigin(value))
    }
    @Test fun oldOriginSurvivesWithoutServerPathOrQuery() {
        assertEquals("http://192.168.1.2:8080", localPageOrigin("http://192.168.1.2:8080/tv?rev=old"))
        assertEquals("https://tv.example.test", localPageOrigin("https://TV.EXAMPLE.TEST:443/tv"))
        assertEquals("http://tv.example.test", localPageOrigin("http://tv.example.test:80/tv"))
        assertEquals("http://[::1]:8080", localPageOrigin("http://[::1]:8080/tv"))
    }
}
