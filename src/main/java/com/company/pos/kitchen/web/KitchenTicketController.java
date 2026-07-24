package com.company.pos.kitchen.web;

import com.company.pos.kitchen.api.KitchenTicketService;
import com.company.pos.kitchen.api.KitchenTicketView;
import com.company.pos.kitchen.api.TicketTransitionCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class KitchenTicketController {

    private final KitchenTicketService tickets;

    KitchenTicketController(KitchenTicketService tickets) {
        this.tickets = tickets;
    }

    @GetMapping("/kitchen/tickets")
    List<KitchenTicketView> list(@RequestParam(required = false) String station) {
        return tickets.listActiveTickets(Optional.ofNullable(station));
    }

    @PostMapping("/kitchen/tickets/{id}/advance")
    KitchenTicketView advance(@PathVariable UUID id, @RequestBody TicketTransitionCommand body) {
        return tickets.advance(id, body.expectedState());
    }

    @PostMapping("/kitchen/tickets/{id}/recall")
    KitchenTicketView recall(@PathVariable UUID id, @RequestBody TicketTransitionCommand body) {
        return tickets.recall(id, body.expectedState());
    }
}
