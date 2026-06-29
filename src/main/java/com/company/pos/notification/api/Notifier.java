package com.company.pos.notification.api;

/** Delivers operator alerts. The only implementation this phase is the in-memory fake; real
 *  SMS/email/push adapters implement this later with no change to callers. */
public interface Notifier {

    void send(Alert alert);
}
