package com.xuanvolab.unifieddocviewer.security;

import com.xuanvolab.unifieddocviewer.config.ExternalSystemProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtOutboundInterceptor {

    private final WebClient.Builder webClientBuilder;
    private final ExternalSystemProperties properties;

    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    private record CachedToken(String token, Instant expiresAt) {
        boolean isValid() {
            // Valid if expiry is more than 30 seconds away (safety buffer)
            return expiresAt.isAfter(Instant.now().plusSeconds(30));
        }
    }

    public String getServiceToken(String systemKey) {
        CachedToken cached = tokenCache.get(systemKey);
        if (cached != null && cached.isValid()) {
            return cached.token();
        }

        ExternalSystemProperties.SystemConfig config = properties.getSystem(systemKey);
        if (config == null || config.getTokenUri() == null || config.getClientId() == null) {
            log.debug("No token URI or client ID configured for system '{}', returning fallback token", systemKey);
            return "service-token-" + systemKey;
        }

        try {
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("grant_type", "client_credentials");
            formData.add("client_id", config.getClientId());
            formData.add("client_secret", config.getClientSecret() != null ? config.getClientSecret() : "");
            if (config.getScope() != null) {
                formData.add("scope", config.getScope());
            }

            WebClient webClient = webClientBuilder.build();
            Map<String, Object> response = webClient.post()
                    .uri(config.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(formData))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            if (response != null && response.containsKey("access_token")) {
                String accessToken = (String) response.get("access_token");
                Number expiresIn = response.containsKey("expires_in") ? (Number) response.get("expires_in") : 3600;
                Instant expiresAt = Instant.now().plusSeconds(expiresIn.longValue());

                tokenCache.put(systemKey, new CachedToken(accessToken, expiresAt));
                log.info("Obtained and cached service JWT for external system '{}', expires in {}s", systemKey, expiresIn);
                return accessToken;
            }
        } catch (Exception ex) {
            log.warn("Failed to fetch OAuth2 service token for system '{}': {}. Falling back to default token.", systemKey, ex.getMessage());
        }

        return "service-token-" + systemKey;
    }

    public void invalidateToken(String systemKey) {
        tokenCache.remove(systemKey);
        log.info("Invalidated cached service JWT for external system '{}'", systemKey);
    }
}
