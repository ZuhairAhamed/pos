package com.company.pos.dining.web;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CloseOrderCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.SplitCloseCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OpenOrderView;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.SplitMode;
import com.company.pos.dining.api.UpdateTableCommand;
import com.company.pos.dining.api.SplitQuoteView;
import com.company.pos.dining.api.TableView;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.QuoteView;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DiningController {

    private final DiningService dining;
    private final SalesService sales;

    DiningController(DiningService dining, SalesService sales) {
        this.dining = dining;
        this.sales = sales;
    }

    @PostMapping("/dining/tables")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MANAGER')")
    TableView registerTable(@RequestBody RegisterTableCommand body) {
        return dining.registerTable(body);
    }

    @DeleteMapping("/dining/tables/{tableId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MANAGER')")
    void deactivateTable(@PathVariable UUID tableId) {
        dining.deactivateTable(tableId);
    }

    @PutMapping("/dining/tables/{tableId}")
    @PreAuthorize("hasRole('MANAGER')")
    TableView updateTable(@PathVariable UUID tableId, @RequestBody UpdateTableCommand body) {
        return dining.updateTable(tableId, body);
    }

    @PostMapping("/dining/tables/{tableId}/reactivate")
    @PreAuthorize("hasRole('MANAGER')")
    TableView reactivateTable(@PathVariable UUID tableId) {
        return dining.reactivateTable(tableId);
    }

    @GetMapping("/dining/tables")
    List<TableView> listTables() {
        return dining.listTables();
    }

    @PostMapping("/dining/orders")
    @ResponseStatus(HttpStatus.CREATED)
    OrderView openOrder(@RequestBody OpenOrderCommand body, Authentication authentication) {
        return dining.openOrder(body, authentication.getName());
    }

    @GetMapping("/dining/orders")
    List<OpenOrderView> listOpenOrders() {
        return dining.listOpenOrders();
    }

    @GetMapping("/dining/orders/{orderId}")
    OrderView getOrder(@PathVariable UUID orderId) {
        return dining.getOrder(orderId);
    }

    @GetMapping("/dining/orders/{orderId}/quote")
    QuoteView quoteOrder(@PathVariable UUID orderId) {
        return dining.quoteOrder(orderId);
    }

    /** Body for the discount-aware quote. Nulls (or omitted fields) mean "no discounts". */
    record QuoteOrderRequest(Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount, boolean waiveServiceCharge) {
        QuoteOrderRequest {
            lineDiscounts = lineDiscounts == null ? Map.of() : lineDiscounts;
        }
    }

    @PostMapping("/dining/orders/{orderId}/quote")
    QuoteView quoteOrderWithDiscounts(@PathVariable UUID orderId,
            @RequestBody QuoteOrderRequest body) {
        return dining.quoteOrder(orderId, body.lineDiscounts(), body.transactionDiscount(),
                body.waiveServiceCharge());
    }

    @PostMapping("/dining/orders/{orderId}/lines")
    OrderView addLine(@PathVariable UUID orderId, @RequestBody AddLineCommand body,
            Authentication authentication) {
        return dining.addLine(orderId, body, authentication.getName());
    }

    @PutMapping("/dining/orders/{orderId}/lines/{lineId}")
    OrderView updateLine(@PathVariable UUID orderId, @PathVariable UUID lineId,
            @RequestParam BigDecimal qty,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) CourseTag course) {
        return dining.updateLine(orderId, lineId, qty, note, course);
    }

    @DeleteMapping("/dining/orders/{orderId}/lines/{lineId}")
    @PreAuthorize("hasRole('MANAGER')")
    OrderView removeLine(@PathVariable UUID orderId, @PathVariable UUID lineId) {
        return dining.removeLine(orderId, lineId);
    }

    @PostMapping("/dining/orders/{orderId}/fire")
    OrderView fire(@PathVariable UUID orderId, Authentication authentication) {
        return dining.fireOrder(orderId, authentication.getName());
    }

    @PostMapping("/dining/orders/{orderId}/close")
    @ResponseStatus(HttpStatus.CREATED)
    SaleView closeOrder(@PathVariable UUID orderId, @RequestBody CloseOrderCommand body,
            Authentication authentication) {
        boolean isManager = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_MANAGER".equals(a.getAuthority()));
        return dining.closeOrder(orderId, body, authentication.getName(), isManager);
    }

    @PostMapping("/dining/orders/{orderId}/close-split")
    @ResponseStatus(HttpStatus.CREATED)
    List<SaleView> closeSplit(@PathVariable UUID orderId, @RequestBody SplitCloseCommand body,
            Authentication authentication) {
        boolean isManager = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_MANAGER".equals(a.getAuthority()));
        return dining.closeOrderSplit(orderId, body, authentication.getName(), isManager);
    }

    /** Body for the split quote. BY_ITEM populates {@code bills}; EVEN populates {@code even}. */
    record QuoteSplitRequest(SplitMode mode, List<QuoteBillInput> bills, QuoteEvenInput even,
            boolean waiveServiceCharge) {
    }

    record QuoteBillInput(List<UUID> lineIds) {
    }

    record QuoteEvenInput(int ways) {
    }

    @PostMapping("/dining/orders/{orderId}/quote-split")
    SplitQuoteView quoteSplit(@PathVariable UUID orderId, @RequestBody QuoteSplitRequest body) {
        if (body.mode() == null) {
            throw DomainException.validation("Split mode is required");
        }
        return switch (body.mode()) {
            case BY_ITEM -> dining.quoteSplitByItem(orderId,
                    body.bills() == null ? List.of()
                            : body.bills().stream().map(QuoteBillInput::lineIds).toList(),
                    body.waiveServiceCharge());
            case EVEN -> {
                if (body.even() == null) {
                    throw DomainException.validation("Even split details are required");
                }
                yield dining.quoteSplitEven(orderId, body.even().ways(), body.waiveServiceCharge());
            }
        };
    }

    @GetMapping("/dining/orders/{orderId}/sales")
    List<SaleView> orderSales(@PathVariable UUID orderId) {
        return dining.listOrderSaleIds(orderId).stream().map(sales::getSale).toList();
    }

    @PostMapping("/dining/orders/{orderId}/void")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MANAGER')")
    void voidOrder(@PathVariable UUID orderId,
            @RequestParam(required = false, defaultValue = "") String reason) {
        dining.voidOrder(orderId, reason);
    }

    @PostMapping("/dining/orders/{orderId}/transfer")
    OrderView transferOrder(@PathVariable UUID orderId, @RequestParam UUID targetTableId) {
        return dining.transferOrder(orderId, targetTableId);
    }

    @PostMapping("/dining/orders/{survivorOrderId}/merge")
    OrderView mergeOrders(@PathVariable UUID survivorOrderId, @RequestParam UUID absorbedOrderId) {
        return dining.mergeOrders(survivorOrderId, absorbedOrderId);
    }
}
