package com.company.pos.terminal.api;

import com.company.pos.terminal.api.dto.ReturnCommand;
import com.company.pos.terminal.api.dto.ReturnView;
import com.fasterxml.jackson.core.type.TypeReference;

/** Typed client for {@code POST /returns}. Non-final so view-model tests subclass it. Returns are
 *  MANAGER-gated, so {@code process} always rides a one-shot manager bearer token. */
public class ReturnApi {

    private final ApiClient client;

    public ReturnApi(ApiClient client) {
        this.client = client;
    }

    /** POST /returns with the manager's one-shot token; returns the authoritative ReturnView. */
    public ReturnView process(ReturnCommand command, String managerToken) {
        return client.post("/returns", command, new TypeReference<ReturnView>() {}, managerToken);
    }
}
