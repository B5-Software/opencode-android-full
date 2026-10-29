package io.github.opencodeandroid.preview

import org.junit.Assert.*
import org.junit.Test

class PreviewTargetTest {
    @Test fun bridgePortsAreNumbersWithoutCoercingStringsOrFractions() {
        assertEquals(5173, PreviewTarget.fromValue(5173L).port)
        assertEquals(5173, PreviewTarget.fromValue(5173.0).port)
        for (value in listOf(null, "5173", true, 5173.5, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE))
            assertTrue("Non-port value accepted", runCatching { PreviewTarget.fromValue(value) }.isFailure)
    }
    @Test fun administrativeAndPrivilegedPortsCannotBeOpenedAsProjects() {
        for (port in listOf(-1, 0, 80, 1023, 49374, 5901, 6080, 65536)) {
            assertTrue("Port $port was accepted", runCatching { PreviewTarget.forPort(port) }.isFailure)
        }
        assertEquals("http://127.0.0.1:5173/", PreviewTarget.forPort(5173).url)
    }
    @Test fun projectNavigationCannotChangeAuthorityOrScheme() {
        val target = PreviewTarget.forPort(5173)
        assertTrue(target.ownsNavigation("http://127.0.0.1:5173/route?value=1#part"))
        for (url in listOf("http://127.0.0.1:49374/", "http://localhost:5173/", "https://127.0.0.1:5173/", "http://user@127.0.0.1:5173/", "http://127.0.0.1:5173.evil.example/", "javascript:alert(1)", "file:///etc/passwd"))
            assertFalse(url, target.ownsNavigation(url))
    }
    @Test fun resourcesBlockAdministrativePortsAndLocalFileSchemesIncludingWorkers() {
        for (host in listOf("127.0.0.1", "localhost", "localhost.", "[::1]", "[::ffff:7f00:1]", "example.test"))
            for (port in listOf(49374, 6080, 5901)) assertFalse("$host:$port", PreviewTarget.resourceAllowed("http://$host:$port/api/info"))
        for (url in listOf("file:///data/user/0/private", "content://private/token", "javascript:alert(1)", "http://user:pass@127.0.0.1:3000/", "http://localhost:80/"))
            assertFalse(url, PreviewTarget.resourceAllowed(url))
    }
    @Test fun projectApiPortsAndSecureCdnResourcesRemainAvailable() {
        for (url in listOf("http://127.0.0.1:5173/", "http://localhost:3001/api", "https://example.test/asset.js", "blob:http://127.0.0.1:5173/id", "data:image/png;base64,AAAA", "about:blank"))
            assertTrue(url, PreviewTarget.resourceAllowed(url))
    }
}
