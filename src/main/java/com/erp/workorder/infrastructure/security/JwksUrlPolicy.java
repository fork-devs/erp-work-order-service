package com.erp.workorder.infrastructure.security;

import java.net.URI;
import java.util.List;

/**
 * The key set's URL policy.
 *
 * <p>The key set is downloaded over HTTP: unencrypted, anyone on the path
 * can swap the keys and, with them, sign tokens the service will accept.
 * That is a complete authentication bypass.
 *
 * <p>The only exception is the local development profile, where the issuer
 * is served on the same machine. Outside that profile, the URL must use an
 * encrypted scheme, and startup fails explicitly when it does not.
 */
public final class JwksUrlPolicy {

    /** The profiles where a cleartext URL is allowed. */
    private static final List<String> LOCAL_DEVELOPMENT_PROFILES = List.of("dev", "local");

    private JwksUrlPolicy() {
    }

    /**
     * Verifies the URL and throws when it breaks the policy.
     */
    public static void verify(String jwksUrl, List<String> activeProfiles) {
        if (jwksUrl == null || jwksUrl.isBlank()) {
            throw new IllegalStateException(
                    "No key set URL configured. Set JWKS_URL to the authentication service's "
                    + "/.well-known/jwks.json address.");
        }

        String scheme;
        try {
            scheme = URI.create(jwksUrl.trim()).getScheme();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The key set URL is not valid: " + jwksUrl, e);
        }

        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }

        boolean localDevelopment = activeProfiles != null
                && activeProfiles.stream().anyMatch(LOCAL_DEVELOPMENT_PROFILES::contains);

        if (localDevelopment) {
            return;
        }

        throw new IllegalStateException(
                "The key set URL must use an encrypted scheme (https); the configured one uses '"
                + scheme + "': " + jwksUrl + ". Unencrypted, anyone on the path can swap the keys "
                + "and sign tokens the service will accept. The only exception is the local "
                + "development profile.");
    }
}
