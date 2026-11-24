package com.erp.workorder.infrastructure.adapter.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Uploads multipart evidence images to erp-integration-hub (Cloudflare R2 storage).
 */
@Component
public class MediaUploadClient {

    private static final String PLATFORM_TENANT = "00000000-0000-0000-0000-000000000000";

    private final String baseUrl;
    private final String internalServiceKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public MediaUploadClient(
            @Value("${erp.integration-hub.base-url:http://localhost:8085}") String baseUrl,
            @Value("${erp.security.internal-service-key:erp_internal_secret_service_key_2026}") String internalServiceKey) {
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim().replaceAll("/+$", "") : "http://localhost:8085";
        this.internalServiceKey = internalServiceKey != null ? internalServiceKey.trim() : "";
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public UploadedMedia upload(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Image file must not be empty");
        }
        try {
            String boundary = "----DriveOSBoundary" + UUID.randomUUID().toString().replace("-", "");
            byte[] payload = buildMultipartBody(boundary, file, folder);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/media/upload"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Accept", "application/json")
                    .header("X-Service-Name", "erp-work-order-service")
                    .header("X-Tenant-ID", PLATFORM_TENANT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            if (!internalServiceKey.isBlank()) {
                builder.header("X-Internal-Service-Key", internalServiceKey);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Media upload failed with HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            return new UploadedMedia(
                    root.path("mediaId").asText(UUID.randomUUID().toString()),
                    root.path("url").asText()
            );
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to upload image to media service", e);
        }
    }

    private byte[] buildMultipartBody(String boundary, MultipartFile file, String folder) throws IOException {
        String safeFolder = (folder != null && !folder.isBlank()) ? folder.trim() : "uploads";
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "image.jpg";
        String contentType = file.getContentType() != null ? file.getContentType() : "image/jpeg";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"folder\"\r\n\r\n" + safeFolder + "\r\n").getBytes(StandardCharsets.UTF_8));

        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(file.getBytes());
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    public record UploadedMedia(String mediaId, String url) {}
}
