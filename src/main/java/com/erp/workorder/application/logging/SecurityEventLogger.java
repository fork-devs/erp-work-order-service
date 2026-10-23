package com.erp.workorder.application.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The platform's structured security event log, with the schema the
 * authentication service fixed:
 *
 * <pre>
 * evento=... tenant_id=... user_id=... trace_id=... resultado=... timestamp=...
 * </pre>
 *
 * <p>The schema admits NO passwords, tokens or secrets: it has no parameter
 * for them, so they cannot slip in by accident. An audit log holding
 * credentials is a credential store.
 */
public final class SecurityEventLogger {

    private static final Logger log = LoggerFactory.getLogger("SECURITY");

    private SecurityEventLogger() {
    }

    public static void registrar(String evento,
                                 UUID tenantId,
                                 UUID userId,
                                 String traceId,
                                 String resultado,
                                 Map<String, String> extra) {
        log.info(format(evento, tenantId, userId, traceId, resultado, extra));
    }

    public static String format(String evento,
                                UUID tenantId,
                                UUID userId,
                                String traceId,
                                String resultado,
                                Map<String, String> extra) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("evento", evento);
        attributes.put("tenant_id", tenantId != null ? tenantId.toString() : "-");
        attributes.put("user_id", userId != null ? userId.toString() : "-");
        attributes.put("trace_id", traceId != null && !traceId.isBlank() ? traceId : "-");
        attributes.put("resultado", resultado);
        attributes.put("timestamp", Instant.now().toString());

        if (extra != null) {
            extra.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    attributes.put(key, value);
                }
            });
        }
        return String.join(" ", attributes.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList());
    }
}
