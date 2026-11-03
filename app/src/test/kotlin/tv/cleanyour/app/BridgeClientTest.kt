package tv.cleanyour.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeClientTest {
    @Test fun acceptsPrivateLanIpsOnly() {
        assertTrue(BridgeClient.privateIpv4("192.168.100.3"))
        assertTrue(BridgeClient.privateIpv4("10.0.0.2"))
        assertTrue(BridgeClient.privateIpv4("172.31.255.254"))
        assertFalse(BridgeClient.privateIpv4("8.8.8.8"))
        assertFalse(BridgeClient.privateIpv4("127.0.0.1"))
        assertFalse(BridgeClient.privateIpv4("192.168.100.999"))
        assertFalse(BridgeClient.privateIpv4("example.com"))
        assertFalse(BridgeClient.privateIpv4("192.168.1.1;rm -rf /"))
    }

    @Test fun createsStrongEphemeralSessionKeys() {
        val first = BridgeClient.newKey()
        val second = BridgeClient.newKey()
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(first != second)
    }
}
