package com.company.pos.device.api;

/** A rendered outbound email. The {@code body} is plain text this phase. */
public record EmailMessage(String to, String subject, String body) {
}
