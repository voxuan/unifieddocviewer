package com.xuanvolab.unifieddocviewer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "external-systems")
public class ExternalSystemProperties {

    private int cacheTtlSeconds = 60;
    private String cleanupCron = "0 */5 * * * *";
    private Map<String, SystemConfig> systems = new HashMap<>();

    @Data
    public static class SystemConfig {
        private boolean enabled = true;
        private String sourceName;
        private String baseUrl;
        private int timeoutMs = 3000;
        private String tokenUri;
        private String clientId;
        private String clientSecret;
        private String scope;
    }

    public SystemConfig getSystem(String systemKey) {
        return systems.get(systemKey);
    }
}
