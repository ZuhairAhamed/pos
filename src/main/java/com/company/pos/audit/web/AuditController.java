package com.company.pos.audit.web;

import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.api.AuditVerifyResult;
import com.company.pos.audit.application.DefaultAuditService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('ADMIN')")
class AuditController {

    private static final Instant MIN = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59Z");

    private final DefaultAuditService audit;

    AuditController(DefaultAuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/audit")
    List<AuditRecordView> search(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return audit.search(actor, action, from == null ? MIN : from, to == null ? MAX : to, page, size);
    }

    @GetMapping("/audit/{id}")
    AuditRecordView get(@PathVariable UUID id) {
        return audit.findById(id);
    }

    @PostMapping("/audit/verify")
    AuditVerifyResult verify() {
        return audit.verify();
    }
}
