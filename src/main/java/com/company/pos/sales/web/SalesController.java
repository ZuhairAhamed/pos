package com.company.pos.sales.web;

import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SalesController {

    private final SalesService sales;

    SalesController(SalesService sales) {
        this.sales = sales;
    }

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.CREATED)
    SaleView checkout(@RequestBody CheckoutCommand command, Authentication authentication) {
        boolean isManager = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_MANAGER".equals(a.getAuthority()));
        // Retail POST /sales must never carry a service charge; force the flag off
        // regardless of what the request body contains (service charge is DINE_IN only).
        CheckoutCommand retailCommand = new CheckoutCommand(command.cartId(), command.tenders(),
                command.lineDiscounts(), command.transactionDiscount(), false);
        return sales.checkout(retailCommand, authentication.getName(), isManager);
    }

    @GetMapping("/sales/{saleId}")
    SaleView get(@PathVariable UUID saleId) {
        return sales.getSale(saleId);
    }

    @PostMapping("/sales/{saleId}/reprint")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reprint(@PathVariable UUID saleId) {
        sales.reprint(saleId);
    }
}
