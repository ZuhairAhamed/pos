package com.company.pos.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@SpringBootTest
@RecordApplicationEvents
class DomainEventsTest {

    record SampleEvent(String payload) implements DomainEvent {
    }

    @Autowired
    DomainEvents domainEvents;

    @Autowired
    ApplicationEvents recorded;

    @Test
    void publishesDomainEventToListeners() {
        domainEvents.publish(new SampleEvent("hello"));

        assertThat(recorded.stream(SampleEvent.class).count()).isEqualTo(1);
    }
}
