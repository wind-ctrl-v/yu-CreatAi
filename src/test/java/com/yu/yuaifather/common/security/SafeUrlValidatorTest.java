package com.yu.yuaifather.common.security;

import com.yu.yuaifather.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafeUrlValidatorTest {

    @Test
    void rejectsUnsupportedSchemeAndCredentials() {
        assertThrows(BusinessException.class, () -> SafeUrlValidator.validate("file:///etc/passwd"));
        assertThrows(BusinessException.class, () -> SafeUrlValidator.validate("https://user:pass@example.com"));
    }

    @Test
    void rejectsLocalAndConfiguredInternalNetworks() {
        assertThrows(BusinessException.class, () -> SafeUrlValidator.validate("http://127.0.0.1"));
        assertThrows(BusinessException.class, () -> SafeUrlValidator.validate("http://10.0.0.1"));
        assertThrows(BusinessException.class, () -> SafeUrlValidator.validate("http://9.1.2.3"));
        assertThrows(BusinessException.class, () -> SafeUrlValidator.validate("http://[::1]"));
    }

    @Test
    void validatesResolvedAddresses() throws Exception {
        assertDoesNotThrow(() -> SafeUrlValidator.requirePublicAddress(InetAddress.getByName("8.8.8.8")));
        assertThrows(BusinessException.class,
                () -> SafeUrlValidator.requirePublicAddress(InetAddress.getByName("192.168.1.1")));
        assertThrows(BusinessException.class,
                () -> SafeUrlValidator.requirePublicAddress(InetAddress.getByName("2001:db8::1")));
    }
}
