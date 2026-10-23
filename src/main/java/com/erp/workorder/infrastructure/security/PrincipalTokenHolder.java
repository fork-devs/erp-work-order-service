package com.erp.workorder.infrastructure.security;

/**
 * The verified principal's bearer token of the current request.
 *
 * <p>The JWT authentication filter (group 8) is who establishes it — only
 * from a token it verified — and clears it when the request ends. The saga
 * clients (InventoryClient, FleetClient) re-send this token, so the whole
 * path carries ONE principal: no service credentials, no anonymous internal
 * call trusted because of the network.
 *
 * <p>The token is never logged and never leaves the holder except as part
 * of the outgoing Authorization header.
 */
public final class PrincipalTokenHolder {

    private static final ThreadLocal<String> CURRENT_TOKEN = new ThreadLocal<>();

    private PrincipalTokenHolder() {
    }

    public static void setToken(String token) {
        CURRENT_TOKEN.set(token);
    }

    public static String getToken() {
        return CURRENT_TOKEN.get();
    }

    public static void clear() {
        CURRENT_TOKEN.remove();
    }
}
