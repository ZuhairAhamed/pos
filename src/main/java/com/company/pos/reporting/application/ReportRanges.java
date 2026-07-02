package com.company.pos.reporting.application;

import com.company.pos.common.exception.DomainException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

final class ReportRanges {

    private ReportRanges() {
    }

    static void requireValid(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw DomainException.validation("Both from and to dates are required");
        }
        if (from.isAfter(to)) {
            throw DomainException.validation("from must not be after to");
        }
    }

    static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static Instant endOf(LocalDate day) {
        return day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
