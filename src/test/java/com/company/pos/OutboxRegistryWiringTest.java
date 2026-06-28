package com.company.pos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class OutboxRegistryWiringTest {

    @Autowired(required = false)
    EventPublicationRegistry registry;
    @Autowired(required = false)
    IncompleteEventPublications incomplete;

    @Test
    void registryBeansArePresentAndStartEmpty() {
        assertThat(registry).as("EventPublicationRegistry bean").isNotNull();
        assertThat(incomplete).as("IncompleteEventPublications bean").isNotNull();
        // Nothing published in this test -> no incomplete publications.
        assertThat(registry.findIncompletePublications()).isEmpty();
    }
}
