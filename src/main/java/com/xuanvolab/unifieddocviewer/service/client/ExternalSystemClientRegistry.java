package com.xuanvolab.unifieddocviewer.service.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Registry holding all active ExternalSystemClient beans discovered in the Spring application context.
 */
@Slf4j
@Component
public class ExternalSystemClientRegistry {

    private final List<ExternalSystemClient> clients;

    public ExternalSystemClientRegistry(List<ExternalSystemClient> clients) {
        this.clients = List.copyOf(clients);
        log.info("Initialized ExternalSystemClientRegistry with {} clients: {}",
                this.clients.size(),
                this.clients.stream().map(ExternalSystemClient::getSourceSystem).toList());
    }

    public List<ExternalSystemClient> getAllClients() {
        return clients;
    }

    public Optional<ExternalSystemClient> getClientBySourceSystem(String sourceSystem) {
        return clients.stream()
                .filter(client -> client.getSourceSystem().equalsIgnoreCase(sourceSystem))
                .findFirst();
    }
}
