package com.erp.workorder.infrastructure.config;

import com.erp.workorder.infrastructure.security.ForbiddenAccessDeniedHandler;
import com.erp.workorder.infrastructure.security.JwksKeyManager;
import com.erp.workorder.infrastructure.security.JwtAuthenticationFilter;
import com.erp.workorder.infrastructure.security.UnauthorizedEntryPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * The work order service's perimeter security.
 *
 * <p>The business endpoints are NOT public: they demand a verified access
 * token, and the tenant is derived from it. Per ENDPOINT roles are declared
 * in the controllers with {@code @PreAuthorize}; the method security is
 * enabled here, so those annotations are enforced, not inert.
 *
 * <p>Nothing on the API path is {@code permitAll} — only the documentation
 * and the health check, which the orchestrator must reach without a token.
 *
 * <p>CORS accepts no arbitrary origin either: the list is configurable and
 * must be explicit.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * The client of the issuer's public key set.
     *
     * <p>The URL must use an encrypted scheme outside the local development
     * profile: downloading the keys in the clear lets anyone on the path
     * swap them and sign tokens the service would accept.
     */
    @Bean
    public JwksKeyManager jwksKeyManager(
            @Value("${erp.auth.jwks-url}") String jwksUrl,
            org.springframework.core.env.Environment environment) {
        return new JwksKeyManager(jwksUrl, Arrays.asList(environment.getActiveProfiles()));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   UnauthorizedEntryPoint unauthorizedEntryPoint,
                                                   ForbiddenAccessDeniedHandler forbiddenAccessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthorizedEntryPoint)
                        .accessDeniedHandler(forbiddenAccessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/actuator/health",
                                "/actuator/health/**"
                        ).permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${erp.cors.allowed-origins:http://localhost:4200}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Tenant-ID"));
        configuration.setExposedHeaders(List.of("Authorization", "X-Tenant-ID"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
