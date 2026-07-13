package com.company.pos.terminal.api.dto;

import java.util.List;

/** One proposed bill of a by-item split quote: the order-line ids it covers. */
public record QuoteBillInput(List<java.util.UUID> lineIds) {
}
