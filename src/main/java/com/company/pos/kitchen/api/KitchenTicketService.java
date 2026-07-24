package com.company.pos.kitchen.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KitchenTicketService {

    /** Active tickets (FIRED/PREPARING/READY, plus recently-bumped within the recall window),
     *  optionally filtered to one station, oldest fire first. */
    List<KitchenTicketView> listActiveTickets(Optional<String> station);

    KitchenTicketView advance(UUID ticketId, KitchenTicketState expectedState);

    KitchenTicketView recall(UUID ticketId, KitchenTicketState expectedState);
}
