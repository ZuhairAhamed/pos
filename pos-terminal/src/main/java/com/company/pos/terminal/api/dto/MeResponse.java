package com.company.pos.terminal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MeResponse(String username, Set<String> roles) {
}
