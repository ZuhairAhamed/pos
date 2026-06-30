package com.company.pos.audit.api;

import java.util.Map;

/** Records a non-transactional security action into the tamper-evident trail in its own
 *  committed transaction. Used by callers (e.g. {@code auth}) that have no business transaction
 *  to ride — a failed login still leaves a record even though the caller then throws. */
public interface AuditService {

    void record(AuditAction action, String actor, String entityRef, Map<String, String> details);
}
