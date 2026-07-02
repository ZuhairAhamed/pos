package com.company.pos.customer.infrastructure;

import com.company.pos.customer.domain.Customer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    @Query("select c from Customer c where c.active = true and ("
            + "lower(c.name) like lower(concat('%', :q, '%')) "
            + "or lower(c.phone) like lower(concat('%', :q, '%')) "
            + "or lower(c.email) like lower(concat('%', :q, '%')))")
    List<Customer> search(@Param("q") String q);
}
