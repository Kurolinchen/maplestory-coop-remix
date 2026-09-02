package coop.registration;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RegistrationServerSecurityTest {
    @Test
    void boundsRequestBodyWithAndWithoutContentLength() throws Exception {
        byte[] maximum = new byte[16 * 1024];
        byte[] oversized = new byte[maximum.length + 1];
        assertArrayEquals(maximum, RegistrationServerMain.readBoundedBody(
                new ByteArrayInputStream(maximum), Integer.toString(maximum.length)));
        assertNull(RegistrationServerMain.readBoundedBody(
                new ByteArrayInputStream(oversized), Integer.toString(oversized.length)));
        assertNull(RegistrationServerMain.readBoundedBody(new ByteArrayInputStream(oversized), null));
        assertNull(RegistrationServerMain.readBoundedBody(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), "invalid"));
    }

    @Test
    void onlyTrustedProxyCanSupplyClientIp() {
        Set<String> trusted = Set.of("172.20.0.2");
        assertEquals("198.51.100.7", TrustedProxyClientIp.resolve(
                "172.20.0.2", "198.51.100.7", trusted));
        assertEquals("203.0.113.9", TrustedProxyClientIp.resolve(
                "203.0.113.9", "198.51.100.7", trusted));
        assertEquals("172.20.0.2", TrustedProxyClientIp.resolve(
                "172.20.0.2", "198.51.100.7, 203.0.113.1", trusted));
        assertEquals("172.20.0.2", TrustedProxyClientIp.resolve(
                "172.20.0.2", "attacker.example", trusted));
    }
}
