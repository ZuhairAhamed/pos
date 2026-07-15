package com.company.pos.device.api;

/**
 * Port for sending an already-rendered email. The only implementation this phase is the
 * in-memory fake; a real SMTP adapter implements this later with no change to callers.
 */
public interface Emailer {

    void send(EmailMessage message);
}
