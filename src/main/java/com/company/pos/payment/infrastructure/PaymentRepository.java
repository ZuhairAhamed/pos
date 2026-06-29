package com.company.pos.payment.infrastructure;

import com.company.pos.payment.domain.Payment;
import com.company.pos.payment.domain.PaymentDirection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findBySaleIdAndDirectionOrderByCreatedAtAsc(UUID saleId, PaymentDirection direction);

    List<Payment> findByReturnIdOrderByCreatedAtAsc(UUID returnId);
}
