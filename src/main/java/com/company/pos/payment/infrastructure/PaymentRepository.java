package com.company.pos.payment.infrastructure;

import com.company.pos.payment.domain.Payment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findBySaleId(UUID saleId);
}
