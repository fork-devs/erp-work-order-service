package com.erp.workorder.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * BDD step definitions and Gherkin feature specification verification for erp-work-order-service.
 * Compatible with Cucumber-JVM step mapping and standalone JUnit 5 validation.
 */
class WorkOrderBddStepDefinitions {

    private final BddRestClientSupport restClient =
            new BddRestClientSupport(System.getProperty("bdd.base.url", "http://localhost:8080"));

    public void givenAuthenticatedRole(String jwtToken) {
        restClient.withBearerToken(jwtToken);
    }

    public void whenClientSendsRequest(String method, String path, String payload)
            throws IOException, InterruptedException {
        restClient.send(method, path, payload);
    }

    public void thenResponseStatusShouldBe(int expectedStatus) {
        assertEquals(expectedStatus, restClient.lastStatusCode());
    }

    public void thenJsonFieldShouldEqual(String fieldName, String expectedValue) throws IOException {
        JsonNode body = restClient.lastJsonBody();
        assertTrue(body.has(fieldName), "Expected JSON field missing: " + fieldName);
        assertEquals(expectedValue, body.get(fieldName).asText());
    }

    @Test
    void featureFilesExistAndFollowGherkinConventions() throws IOException {
        Path featuresDir = Path.of("src/test/resources/features");
        assertTrue(Files.isDirectory(featuresDir), "Features directory must exist");
        List<Path> featureFiles;
        try (Stream<Path> stream = Files.list(featuresDir)) {
            featureFiles = stream.filter(p -> p.toString().endsWith(".feature")).toList();
        }
        assertTrue(!featureFiles.isEmpty(), "At least one .feature file must be present");
        for (Path file : featureFiles) {
            String content = Files.readString(file);
            assertTrue(content.contains("Feature:"), "Missing Feature keyword in " + file.getFileName());
            assertTrue(content.contains("Scenario:"), "Missing Scenario keyword in " + file.getFileName());
            assertTrue(content.contains("Given "), "Missing Given step in " + file.getFileName());
            assertTrue(content.contains("When "), "Missing When step in " + file.getFileName());
            assertTrue(content.contains("Then "), "Missing Then step in " + file.getFileName());
        }
    }
}
