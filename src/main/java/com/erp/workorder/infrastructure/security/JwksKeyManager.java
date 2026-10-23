package com.erp.workorder.infrastructure.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client of the authentication service's public key set.
 *
 * <p>Four properties the platform demands of it:
 *
 * <ul>
 *   <li>The cache's freshness is checked BEFORE returning a known key. A
 *   rotated key cannot be accepted for the whole cache window afterwards.</li>
 *   <li>An unknown key identifier is REJECTED. Falling back to "any key"
 *   would accept any token ever signed with any key.</li>
 *   <li>The download has a connect and a read timeout: an authentication
 *   service that hangs never leaves the process waiting forever.</li>
 *   <li>A failed download does NOT empty the cache nor advances the success
 *   marker: the last valid set is kept, and the retry interval after a
 *   failure bounds the download rate, so it does not grow with traffic.</li>
 * </ul>
 */
public class JwksKeyManager {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyManager.class);

    /** The cache's validity after a successful download. */
    private static final long CACHE_TTL_SECONDS = 300;

    /** The interval between retries after a failed download. */
    private static final long RETRY_INTERVAL_SECONDS = 10;

    private static final int CONNECT_TIMEOUT_MILLIS = 2000;
    private static final int READ_TIMEOUT_MILLIS = 3000;

    private final String jwksUrl;
    private final ConcurrentHashMap<String, RSAPublicKey> publicKeyMap = new ConcurrentHashMap<>();

    /** The moment of the last successful download. */
    private volatile Instant lastSuccessTime = Instant.EPOCH;

    /** The moment of the last attempt, successful or not. */
    private volatile Instant lastAttemptTime = Instant.EPOCH;

    /** Whether the last download attempt failed. */
    private volatile boolean lastAttemptFailed = false;

    public JwksKeyManager(String jwksUrl, List<String> activeProfiles) {
        JwksUrlPolicy.verify(jwksUrl, activeProfiles);
        this.jwksUrl = jwksUrl == null ? "" : jwksUrl.trim();
    }

    public RSAPublicKey getPublicKey(String keyId) {
        // The cache's freshness is checked BEFORE returning a known key:
        // otherwise a rotated key would keep being accepted for the whole
        // cache window.
        refreshIfDue(false);

        if (keyId != null) {
            RSAPublicKey key = publicKeyMap.get(keyId);
            if (key != null) {
                return key;
            }
        }

        // Unknown identifier: it may be a recent rotation.
        refreshIfDue(true);

        if (keyId != null) {
            return publicKeyMap.get(keyId);
        }

        // Without an identifier there is no key to return. Verifying
        // against an arbitrary key accepts any token.
        log.warn("A token without a key identifier was presented: it is rejected");
        return null;
    }

    public void registerManualKey(String keyId, RSAPublicKey publicKey) {
        if (keyId != null && publicKey != null) {
            publicKeyMap.put(keyId, publicKey);
        }
    }

    /**
     * Downloads the key set, unless the cache is still fresh or the retry
     * interval after a failure has not elapsed yet.
     */
    private synchronized void refreshIfDue(boolean force) {
        Instant now = Instant.now();

        if (lastAttemptFailed && lastAttemptTime.plusSeconds(RETRY_INTERVAL_SECONDS).isAfter(now)) {
            // The last attempt failed: no retry until the interval has
            // elapsed. Without this bound, every request with an unknown
            // identifier would hit the issuer.
            return;
        }

        if (!force && lastSuccessTime.plusSeconds(CACHE_TTL_SECONDS).isAfter(now)) {
            return;
        }

        lastAttemptTime = now;

        try {
            if (jwksUrl.isEmpty()) {
                log.warn("The JWKS URL is not configured");
                lastAttemptFailed = true;
                return;
            }

            log.debug("Downloading the JWKS from {}", jwksUrl);
            JWKSet jwkSet = download();
            ConcurrentHashMap<String, RSAPublicKey> keys = new ConcurrentHashMap<>();

            for (JWK jwk : jwkSet.getKeys()) {
                if (jwk instanceof RSAKey rsaKey) {
                    try {
                        RSAPublicKey publicKey = rsaKey.toRSAPublicKey();
                        keys.put(rsaKey.getKeyID() != null ? rsaKey.getKeyID() : "default", publicKey);
                    } catch (Exception e) {
                        log.error("The RSA JWK could not be converted to an RSAPublicKey", e);
                    }
                }
            }

            // The valid set is replaced ONLY after a successful download: a
            // failure never leaves the service without any key.
            publicKeyMap.clear();
            publicKeyMap.putAll(keys);
            lastSuccessTime = now;
            lastAttemptFailed = false;

            log.debug("The JWKS was updated. Keys loaded: {}", publicKeyMap.keySet());
        } catch (Exception e) {
            lastAttemptFailed = true;
            log.warn("The JWKS could not be loaded from the issuer (continuing with the existing keys): {}",
                    e.getMessage());
        }
    }

    private JWKSet download() throws Exception {
        URL url = URI.create(jwksUrl).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestMethod("GET");
        try (InputStream input = connection.getInputStream()) {
            return JWKSet.load(input);
        } finally {
            connection.disconnect();
        }
    }
}
