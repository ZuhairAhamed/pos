package com.company.pos.audit.api;

public record AuditVerifyResult(boolean intact, long recordsChecked, Long firstBrokenSeq) {
}
