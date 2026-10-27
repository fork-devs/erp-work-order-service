package com.erp.workorder.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * The test credential issuer: it serves a public key set over HTTP and signs
 * tokens with the matching private key.
 *
 * <p>The service validates the token against the authentication service's
 * JWKS. Testing the isolation between tenants needs a REALLY verified token,
 * so the tests start their own issuer instead of mocking the validation.
 */
public final class TestJwksServer {

    private static final String KEY_ID = "test-auth-key-v1";

    private static HttpServer server;
    private static RSAKey rsaKey;
    private static String jwksUrl;

    private TestJwksServer() {
    }

    public static synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            rsaKey = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(KEY_ID)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();

            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = new JWKSet(rsaKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();

            jwksUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json";
        } catch (Exception e) {
            throw new IllegalStateException("The test credential issuer could not be started", e);
        }
    }

    public static synchronized String jwksUrl() {
        start();
        return jwksUrl;
    }

    /**
     * Signs an access token for the given tenant, with the given roles.
     */
    public static String signToken(UUID tenantId, List<String> roles) {
        return signWithLifetime(tenantId, roles, Duration.ofSeconds(900));
    }

    /**
     * Signs an EXPIRED token: its signature verifies, its lifetime does not.
     */
    public static String signExpiredToken(UUID tenantId, List<String> roles) {
        return signWithLifetime(tenantId, roles, Duration.ofSeconds(-600));
    }

    /**
     * Signs a token WITHOUT the tenant claim: whoever received it verified
     * nothing about a tenant.
     */
    public static String signTokenWithoutTenant(List<String> roles) {
        return signWithLifetime(null, roles, Duration.ofSeconds(900));
    }

    private static String signWithLifetime(UUID tenantId, List<String> roles, Duration lifetime) {
        start();
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject(UUID.randomUUID().toString())
                    .claim("roles", roles)
                    .issuer("https://auth.test.erp-automotriz.com")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(lifetime)));
            if (tenantId != null) {
                builder.claim("tenant_id", tenantId.toString());
            }

            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(),
                    builder.build());
            jwt.sign(new RSASSASigner(rsaKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("The signed test token could not be produced", e);
        }
    }
}
