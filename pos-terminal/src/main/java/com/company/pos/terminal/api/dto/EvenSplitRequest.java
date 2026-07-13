package com.company.pos.terminal.api.dto;

import java.util.List;

/** Even split close — mirrors the server's EvenSplitInput: one payment-method name per share
 *  ({@code methods.size() == ways}); the server fixes each tender to its exact share. */
public record EvenSplitRequest(int ways, List<String> methods) {
}
