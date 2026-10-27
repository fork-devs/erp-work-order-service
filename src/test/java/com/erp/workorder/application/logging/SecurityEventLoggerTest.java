package com.erp.workorder.application.logging;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The security events' schema: fixed attributes in a fixed order, saga
 * advances and access denials written as events, and NO credential slips
 * in — whoever tries, fails closed.
 */
class SecurityEventLoggerTest {

    @Test
    void theEventCarriesThePlatformSchemaInOrder() {
        String event = SecurityEventLogger.format(
                "apertura_rechazada", UUID.randomUUID(), UUID.randomUUID(), "trace-1",
                "rechazado", Map.of("work_order_id", "1234"));

        int evento = event.indexOf("evento=");
        int tenant = event.indexOf("tenant_id=");
        int user = event.indexOf("user_id=");
        int trace = event.indexOf("trace_id=");
        int resultado = event.indexOf("resultado=");
        int timestamp = event.indexOf("timestamp=");

        assertTrue(evento < tenant);
        assertTrue(tenant < user);
        assertTrue(user < trace);
        assertTrue(trace < resultado);
        assertTrue(resultado < timestamp);
    }

    @Test
    void theSagaAdvancesAndTheAccessDenialsLegitTheSchema() {
        // The saga's progress makes an event; so does the denied access.
        assertDoesNotThrow(() -> SecurityEventLogger.format(
                "cotizacion_emitida", UUID.randomUUID(), UUID.randomUUID(), null, "confirmado",
                Map.of("work_order_id", "o-1", "operacion", "reservar")));

        assertDoesNotThrow(() -> SecurityEventLogger.format(
                "acceso_denegado", UUID.randomUUID(), null, null, "denegado",
                Map.of("ruta", "/api/v1/work-orders", "metodo", "POST")));
    }

    @Test
    void noCredentialCanBeSlippedIntoTheSchema() {
        List<String> forbidden = List.of(
                "password", "contrasena", "passwd", "token", "access_token",
                "bearer", "authorization", "secret", "client_secret", "credencial",
                "api_key", "jwt", "refresh_token", "TOKEN", "Password");

        for (String key : forbidden) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> SecurityEventLogger.registrar(
                            "evento_sospechoso", UUID.randomUUID(), UUID.randomUUID(), null,
                            "confirmado", Map.of(key, "aaaa_bbbb_cccc_dddd")),
                    "the attribute '" + key + "' must not enter the audit log");
            assertTrue(rejected.getMessage().contains(key),
                    "the rejection names the attribute it refused");
        }
    }

    @Test
    void nullOrBlankToolsOfTheSchemaStayClean() {
        // Nulls in the optional positions are normal (anonymous denials);
        // the schema renders them as '-'.
        String event = SecurityEventLogger.format(
                "acceso_denegado", null, null, null, "denegado",
                Map.of("ruta", "/x"));

        assertTrue(event.contains("tenant_id=-"));
        assertTrue(event.contains("user_id=-"));
        assertTrue(event.contains("trace_id=-"));
    }
}
