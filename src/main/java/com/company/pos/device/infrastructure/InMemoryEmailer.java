package com.company.pos.device.infrastructure;

import com.company.pos.device.api.EmailMessage;
import com.company.pos.device.api.Emailer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Default {@link Emailer} adapter: logs and records mail in memory. Replaced by an SMTP adapter later. */
@Component
public class InMemoryEmailer implements Emailer {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEmailer.class);

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
        log.info("EMAIL to={} subject={}", message.to(), message.subject());
    }

    public List<EmailMessage> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }
}
