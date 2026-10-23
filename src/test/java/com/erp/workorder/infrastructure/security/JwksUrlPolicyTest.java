package com.erp.workorder.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The key set's URL policy: unencrypted outside local development is a
 * complete authentication bypass, so the policy refuses it explicitly.
 */
class JwksUrlPolicyTest {

    @Test
    void anEncryptedUrlIsAlwaysAccepted() {
        assertDoesNotThrow(() -> JwksUrlPolicy.verify(
                "https://auth.erp-automotriz.com/.well-known/jwks.json", List.of()));
    }

    @Test
    void aCleartextUrlIsAcceptedOnlyInTheLocalDevelopmentProfiles() {
        assertDoesNotThrow(() -> JwksUrlPolicy.verify(
                "http://127.0.0.1:7071/.well-known/jwks.json", List.of("dev", "anything")));

        assertThrows(IllegalStateException.class, () -> JwksUrlPolicy.verify(
                "http://127.0.0.1:7071/.well-known/jwks.json", List.of("prod")));
    }

    @Test
    void aMissingUrlFailsTheStartupExplicitly() {
        assertThrows(IllegalStateException.class, () -> JwksUrlPolicy.verify(null, List.of("prod")));
        assertThrows(IllegalStateException.class, () -> JwksUrlPolicy.verify("   ", List.of("prod")));
    }
}
