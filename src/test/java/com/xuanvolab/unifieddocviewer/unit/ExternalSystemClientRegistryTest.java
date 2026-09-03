package com.xuanvolab.unifieddocviewer.unit;

import com.xuanvolab.unifieddocviewer.service.client.ExternalSystemClient;
import com.xuanvolab.unifieddocviewer.service.client.ExternalSystemClientRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalSystemClientRegistryTest {

    @Test
    @DisplayName("Registry initializes and discovers all clients correctly")
    void registry_InitializesCorrectly() {
        ExternalSystemClient client1 = mock(ExternalSystemClient.class);
        when(client1.getSourceSystem()).thenReturn("SALES");

        ExternalSystemClient client2 = mock(ExternalSystemClient.class);
        when(client2.getSourceSystem()).thenReturn("SERVICE");

        ExternalSystemClientRegistry registry = new ExternalSystemClientRegistry(List.of(client1, client2));

        assertThat(registry.getAllClients()).hasSize(2);
        assertThat(registry.getClientBySourceSystem("SALES")).isPresent();
        assertThat(registry.getClientBySourceSystem("SERVICE")).isPresent();
        assertThat(registry.getClientBySourceSystem("UNKNOWN")).isEmpty();
    }
}
