package com.company.pos.sales.web;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.DiscountPolicyView;
import com.company.pos.sales.api.QuoteView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
    private final ConfigurationService config;

    SalesController(SalesService sales, ConfigurationService config) {
        this.sales = sales;
        this.config = config;
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

    /** Retail cart quote body. Discount fields are optional; nulls mean "no discount". */
    record QuoteRequest(java.util.UUID cartId, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount) {
    }

    @PostMapping("/sales/quote")
    QuoteView quote(@RequestBody QuoteRequest body) {
        // Retail quote: service charge is DINE_IN only, so applyServiceCharge is false.
        return sales.quote(body.cartId(), body.lineDiscounts(), body.transactionDiscount(), false);
    }

    /** Discount policy for terminal UIs: reason-code chips + a local "needs approval" hint.
     *  Advisory only — checkout re-enforces the cap server-side regardless. */
    @GetMapping("/sales/discount-policy")
    DiscountPolicyView discountPolicy() {
        List<String> codes = Arrays.stream(
                        config.getString(SettingKey.DISCOUNT_REASON_CODES).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new DiscountPolicyView(
                new BigDecimal(config.getString(SettingKey.DISCOUNT_CASHIER_MAX_PERCENT)),
                new BigDecimal(config.getString(SettingKey.DISCOUNT_CASHIER_MAX_AMOUNT)),
                codes);
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

    /** Body for POST /sales/{saleId}/send-receipt. */
    record EmailReceiptRequest(String email) {
    }

    @PostMapping("/sales/{saleId}/send-receipt")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendReceipt(@PathVariable UUID saleId, @RequestBody EmailReceiptRequest body) {
        sales.emailReceipt(saleId, body.email());
    }
}
