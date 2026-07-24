package com.company.pos.kitchen.infrastructure;

import com.company.pos.kitchen.api.KitchenTicketState;
import com.company.pos.kitchen.domain.KitchenTicket;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KitchenTicketRepository extends JpaRepository<KitchenTicket, UUID> {

    Optional<KitchenTicket> findByOrderIdAndStationAndFiredAt(UUID orderId, String station, Instant firedAt);

    List<KitchenTicket> findByOrderId(UUID orderId);

    List<KitchenTicket> findByStateInOrderByFiredAtAsc(Collection<KitchenTicketState> states);

    List<KitchenTicket> findByStateAndBumpedAtGreaterThanEqualOrderByFiredAtAsc(
            KitchenTicketState state, Instant cutoff);
}
