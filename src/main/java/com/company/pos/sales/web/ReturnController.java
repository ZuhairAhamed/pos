package com.company.pos.sales.web;

import com.company.pos.sales.api.ReturnCommand;
import com.company.pos.sales.api.ReturnService;
import com.company.pos.sales.api.ReturnView;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReturnController {

    private final ReturnService returns;

    ReturnController(ReturnService returns) {
        this.returns = returns;
    }

    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MANAGER')")
    ReturnView process(@RequestBody ReturnCommand command, Principal principal) {
        return returns.processReturn(command, principal.getName());
    }

    @GetMapping("/returns/{returnId}")
    @PreAuthorize("hasRole('MANAGER')")
    ReturnView get(@PathVariable UUID returnId) {
        return returns.getReturn(returnId);
    }
}
