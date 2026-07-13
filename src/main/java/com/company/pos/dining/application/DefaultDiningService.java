package com.company.pos.dining.application;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.BillInput;
import com.company.pos.dining.api.CloseOrderCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.EvenSplitInput;
import com.company.pos.dining.api.SplitCloseCommand;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.KitchenTicketsFired;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OpenOrderView;
import com.company.pos.dining.api.OrderLineView;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.dining.api.TableView;
import com.company.pos.dining.domain.DiningOrder;
import com.company.pos.dining.domain.DiningOrderSale;
import com.company.pos.dining.domain.DiningTable;
import com.company.pos.dining.domain.OrderLine;
import com.company.pos.dining.infrastructure.DiningOrderRepository;
import com.company.pos.dining.infrastructure.DiningOrderSaleRepository;
import com.company.pos.dining.infrastructure.DiningTableRepository;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.product.api.ProductView;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.ModifierResolution;
import com.company.pos.menu.api.ResolvedModifier;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.DiscountInput;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import com.company.pos.payment.api.PaymentMethod;
import com.company.pos.sales.api.QuoteView;
import com.company.pos.sales.api.TenderInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultDiningService implements DiningService {

    private final DiningTableRepository tables;
    private final DiningOrderRepository orders;
    private final DiningOrderSaleRepository orderSales;
    private final ConfigurationService config;
    private final ProductCatalog products;
    private final CartService carts;
    private final SalesService sales;
    private final MenuService menu;
    private final DomainEvents events;

    DefaultDiningService(DiningTableRepository tables, DiningOrderRepository orders,
            DiningOrderSaleRepository orderSales,
            ConfigurationService config, ProductCatalog products,
            CartService carts, SalesService sales, MenuService menu, DomainEvents events) {
        this.tables = tables;
        this.orders = orders;
        this.orderSales = orderSales;
        this.config = config;
        this.products = products;
        this.carts = carts;
        this.sales = sales;
        this.menu = menu;
        this.events = events;
    }

    @Override
    public TableView registerTable(RegisterTableCommand command) {
        if (command.label() == null || command.label().isBlank()) {
            throw DomainException.validation("Table label is required");
        }
        String label = command.label().trim();
        tables.findByLabel(label).ifPresent(t -> {
            throw DomainException.conflict("Table " + label + " already exists");
        });
        int seats = command.seats() != null
                ? command.seats()
                : config.getInt(SettingKey.DINING_TABLE_DEFAULT_SEATS);
        DiningTable table = new DiningTable(Identifiers.newId(), label, seats);
        return toTableView(tables.save(table));
    }

    @Override
    public void deactivateTable(UUID tableId) {
        DiningTable table = tables.findById(tableId)
                .orElseThrow(() -> DomainException.notFound("No table " + tableId));
        table.setActive(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TableView> listTables() {
        return tables.findAll().stream().map(this::toTableView).toList();
    }

    // --- orders (Task 3) ---
    @Override
    public OrderView openOrder(OpenOrderCommand command, String openedBy) {
        DiningTable table = tables.findById(command.tableId())
                .orElseThrow(() -> DomainException.notFound("No table " + command.tableId()));
        if (!table.isActive()) {
            throw DomainException.validation("Table " + table.getLabel() + " is inactive");
        }
        if (orders.existsByTableIdAndStatus(table.getId(), OrderStatus.OPEN)) {
            throw DomainException.conflict("Table " + table.getLabel() + " already has an open order");
        }
        ServiceType type = command.serviceType() != null ? command.serviceType() : ServiceType.DINE_IN;
        DiningOrder order = new DiningOrder(Identifiers.newId(), table.getId(), type, openedBy,
                Instant.now());
        return toOrderView(orders.save(order));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView getOrder(UUID orderId) {
        return toOrderView(load(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenOrderView> listOpenOrders() {
        return orders.findByStatus(OrderStatus.OPEN).stream()
                .map(o -> new OpenOrderView(o.getId(), o.getTableId(),
                        tables.findById(o.getTableId()).map(DiningTable::getLabel).orElse(null),
                        o.getOpenedAt(), o.getLines().size()))
                .toList();
    }

    DiningOrder load(UUID orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> DomainException.notFound("No order " + orderId));
    }

    OrderView toOrderView(DiningOrder o) {
        List<OrderLineView> lineViews = o.getLines().stream()
                .map(l -> new OrderLineView(l.getId(), l.getSku(), l.getQty(), l.getNote(),
                        l.getCourse(), l.getFiredAt(),
                        l.getModifiers().stream()
                                .map(m -> new com.company.pos.dining.api.OrderLineModifierView(
                                        m.getOptionId(), m.getName(),
                                        m.getPriceDelta()))
                                .toList()))
                .toList();
        return new OrderView(o.getId(), o.getTableId(), o.getServiceType(), o.getStatus(),
                o.getOpenedBy(), o.getOpenedAt(), o.getClosedAt(), o.getSaleId(), lineViews);
    }

    // --- lines (Task 4) ---
    @Override
    public OrderView addLine(UUID orderId, AddLineCommand command, String addedBy) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (command.qty() == null || command.qty().signum() <= 0) {
            throw DomainException.validation("Line quantity must be positive");
        }
        products.findBySku(command.sku())
                .orElseThrow(() -> DomainException.validation("Unknown sku " + command.sku()));
        OrderLine line = new OrderLine(Identifiers.newId(), order.getId(), command.sku(),
                command.qty(), command.note(), command.course(), addedBy, Instant.now());
        if (!command.modifierOptionIds().isEmpty()) {
            ModifierResolution res = menu.resolveSelections(command.sku(), command.modifierOptionIds());
            for (ResolvedModifier m : res.modifiers()) {
                line.addModifier(m.optionId(), m.name(), m.priceDelta());
            }
        }
        order.addLine(line);
        return toOrderView(order);
    }

    @Override
    public OrderView updateLine(UUID orderId, UUID lineId, BigDecimal qty, String note,
            CourseTag course) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (qty == null || qty.signum() <= 0) {
            throw DomainException.validation("Line quantity must be positive");
        }
        OrderLine line = requireLine(order, lineId);
        if (line.isFired()) {
            throw DomainException.validation("Line " + lineId + " was already sent to the kitchen");
        }
        line.setQty(qty);
        line.setNote(note);
        line.setCourse(course);
        return toOrderView(order);
    }

    @Override
    public OrderView removeLine(UUID orderId, UUID lineId) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        OrderLine line = requireLine(order, lineId);
        if (line.isFired()) {
            throw DomainException.validation("Line " + lineId + " was already sent to the kitchen");
        }
        order.removeLine(line);
        return toOrderView(order);
    }

    private void requireOpen(DiningOrder order) {
        if (order.getStatus() != OrderStatus.OPEN) {
            throw DomainException.validation("Order " + order.getId() + " is not open");
        }
    }

    private OrderLine requireLine(DiningOrder order, UUID lineId) {
        return order.getLines().stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> DomainException.notFound("No line " + lineId));
    }

    // --- fire to kitchen ---
    @Override
    public OrderView fireOrder(UUID orderId, String firedBy) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        List<OrderLine> toFire = order.getLines().stream().filter(l -> !l.isFired()).toList();
        if (toFire.isEmpty()) {
            throw DomainException.validation("Order " + orderId + " has no unfired lines to fire");
        }
        Instant now = Instant.now();
        List<KitchenTicketsFired.FiredLine> firedLines = new ArrayList<>();
        for (OrderLine line : toFire) {
            line.fire(now);
            String name = products.findBySku(line.getSku())
                    .map(ProductView::name).orElse(line.getSku());
            List<KitchenTicketsFired.FiredModifier> mods = line.getModifiers().stream()
                    .map(m -> new KitchenTicketsFired.FiredModifier(m.getName()))
                    .toList();
            firedLines.add(new KitchenTicketsFired.FiredLine(line.getSku(), name, line.getQty(),
                    line.getNote(), line.getCourse(), mods));
        }
        String tableLabel = tables.findById(order.getTableId())
                .map(DiningTable::getLabel).orElse("?");
        events.publish(new KitchenTicketsFired(order.getId(), tableLabel, now, firedLines));
        return toOrderView(order);
    }

    // --- close / void (Task 5) ---
    @Override
    public SaleView closeOrder(UUID orderId, CloseOrderCommand command, String cashierUsername,
            boolean callerIsManager) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw DomainException.validation("Cannot close an empty order");
        }

        // NEW (Phase 11b): one cart line per order line, carrying the modifier deltas
        // SNAPSHOTTED at add-time (no re-resolve — the guest pays the quoted price). The
        // cart's own merge collapses identical plain lines; modified lines stay distinct.
        UUID cartId = priceCartFor(order);

        boolean applyServiceCharge = resolveApplyServiceCharge(order,
                command.waiveServiceCharge(), callerIsManager);
        SaleView sale = sales.checkout(
                new CheckoutCommand(cartId, command.tenders(), command.lineDiscounts(),
                        command.transactionDiscount(), applyServiceCharge),
                cashierUsername, callerIsManager);

        carts.close(cartId);
        order.close(sale.id(), Instant.now());
        return sale;
    }

    /** Builds an ephemeral priced cart from an order's lines, snapshotting modifier deltas at
     *  add-time (the guest pays the quoted price). Shared by closeOrder and quoteOrder so the
     *  quote can never drift from what close charges. */
    private UUID priceCartFor(DiningOrder order) {
        UUID cartId = carts.createCart();
        for (OrderLine line : order.getLines()) {
            java.util.List<com.company.pos.cart.api.CartLineModifierInput> mods =
                    line.getModifiers().stream()
                            .map(m -> new com.company.pos.cart.api.CartLineModifierInput(
                                    m.getOptionId(), m.getName(), m.getPriceDelta()))
                            .toList();
            carts.addLinePreResolved(cartId, line.getSku(), line.getQty(), mods);
        }
        return cartId;
    }

    @Override
    @Transactional
    public QuoteView quoteOrder(UUID orderId) {
        return quoteOrder(orderId, Map.of(), null);
    }

    @Override
    @Transactional
    public QuoteView quoteOrder(UUID orderId, Map<String, DiscountInput> lineDiscounts,
            DiscountInput transactionDiscount) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw DomainException.validation("Cannot quote an empty order");
        }
        UUID cartId = priceCartFor(order);
        boolean applyServiceCharge = resolveApplyServiceCharge(order, false, false);
        QuoteView quote = sales.quote(cartId, lineDiscounts, transactionDiscount, applyServiceCharge);
        carts.close(cartId);
        return quote;
    }

    private boolean resolveApplyServiceCharge(DiningOrder order, boolean waiveServiceCharge,
            boolean callerIsManager) {
        if (waiveServiceCharge && !callerIsManager) {
            throw DomainException.validation("Only a manager can waive the service charge");
        }
        return config.getBoolean(SettingKey.SERVICE_CHARGE_ENABLED)
                && order.getServiceType() == ServiceType.DINE_IN
                && !waiveServiceCharge;
    }

    @Override
    public List<SaleView> closeOrderSplit(UUID orderId, SplitCloseCommand command,
            String cashierUsername, boolean callerIsManager) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw DomainException.validation("Cannot close an empty order");
        }
        if (command == null || command.mode() == null) {
            throw DomainException.validation("Split mode is required");
        }
        boolean applyServiceCharge = resolveApplyServiceCharge(order,
                command.waiveServiceCharge(), callerIsManager);
        List<SaleView> results = switch (command.mode()) {
            case BY_ITEM -> closeByItem(order, command.bills(), cashierUsername, callerIsManager,
                    applyServiceCharge);
            case EVEN -> closeEven(order, command.even(), cashierUsername, callerIsManager,
                    applyServiceCharge);
        };
        order.close(null, Instant.now()); // CLOSED + closedAt; the N sale ids live in dining_order_sale
        for (SaleView sale : results) {
            orderSales.save(new DiningOrderSale(Identifiers.newId(), order.getId(), sale.id()));
        }
        return results;
    }

    private List<SaleView> closeByItem(DiningOrder order, List<BillInput> bills,
            String cashier, boolean isManager, boolean applyServiceCharge) {
        if (bills == null) {
            throw DomainException.validation("At least one bill is required for a by-item split");
        }
        validatePartition(order, bills.stream().map(BillInput::lineIds).toList());

        List<SaleView> results = new ArrayList<>();
        for (BillInput bill : bills) {
            UUID cartId = cartForLines(order, bill.lineIds());
            SaleView sale = sales.checkout(
                    new CheckoutCommand(cartId, bill.tenders(), bill.lineDiscounts(),
                            bill.transactionDiscount(), applyServiceCharge),
                    cashier, isManager);
            carts.close(cartId);
            results.add(sale);
        }
        return results;
    }

    /** Validates a by-item partition: ≥1 bill, ≥1 line per bill, every order line in exactly
     *  one bill, no unknown ids. Shared by close and quote so mistakes fail the same way at
     *  quote time as at close time. Returns the order's lines keyed by id. */
    private Map<UUID, OrderLine> validatePartition(DiningOrder order, List<List<UUID>> billLineIds) {
        if (billLineIds == null || billLineIds.isEmpty()) {
            throw DomainException.validation("At least one bill is required for a by-item split");
        }
        Map<UUID, OrderLine> byId = new LinkedHashMap<>();
        for (OrderLine line : order.getLines()) {
            byId.put(line.getId(), line);
        }
        Set<UUID> assigned = new HashSet<>();
        for (List<UUID> lineIds : billLineIds) {
            if (lineIds == null || lineIds.isEmpty()) {
                throw DomainException.validation("Each bill must contain at least one line");
            }
            for (UUID lineId : lineIds) {
                if (!byId.containsKey(lineId)) {
                    throw DomainException.validation("Line " + lineId + " is not on order " + order.getId());
                }
                if (!assigned.add(lineId)) {
                    throw DomainException.validation("Line " + lineId + " assigned to more than one bill");
                }
            }
        }
        if (assigned.size() != byId.size()) {
            throw DomainException.validation("Every order line must be assigned to exactly one bill");
        }
        return byId;
    }

    /** Builds an ephemeral priced cart from a SUBSET of an order's lines (one split bill),
     *  snapshotting modifier deltas at add-time exactly like {@link #priceCartFor}. */
    private UUID cartForLines(DiningOrder order, List<UUID> lineIds) {
        UUID cartId = carts.createCart();
        for (UUID lineId : lineIds) {
            OrderLine line = requireLine(order, lineId);
            List<com.company.pos.cart.api.CartLineModifierInput> mods = line.getModifiers().stream()
                    .map(m -> new com.company.pos.cart.api.CartLineModifierInput(
                            m.getOptionId(), m.getName(), m.getPriceDelta()))
                    .toList();
            carts.addLinePreResolved(cartId, line.getSku(), line.getQty(), mods);
        }
        return cartId;
    }

    private List<SaleView> closeEven(DiningOrder order, EvenSplitInput even,
            String cashier, boolean isManager, boolean applyServiceCharge) {
        if (even == null) {
            throw DomainException.validation("Even split details are required");
        }
        if (even.ways() < 2) {
            throw DomainException.validation("Even split requires at least 2 ways");
        }
        if (even.methods() == null || even.methods().size() != even.ways()) {
            throw DomainException.validation("Even split requires one payment method per share");
        }

        UUID cartId = priceCartFor(order);
        QuoteView quote = sales.quote(cartId, applyServiceCharge);
        BigDecimal grandTotal = quote.grandTotal();
        if (grandTotal.signum() <= 0) {
            throw DomainException.validation("Cannot evenly split a non-positive total");
        }
        List<BigDecimal> shares = evenShares(grandTotal, even.ways());
        List<TenderInput> tenders = new ArrayList<>();
        for (int i = 0; i < even.ways(); i++) {
            BigDecimal share = shares.get(i);
            tenders.add(new TenderInput(even.methods().get(i), share, share));
        }

        SaleView sale = sales.checkout(new CheckoutCommand(cartId, tenders, Map.of(), null, applyServiceCharge),
                cashier, isManager);
        carts.close(cartId);
        return List.of(sale);
    }

    /** Splits {@code grandTotal} into {@code ways} shares: base = HALF_UP scale-2 division,
     *  the LAST share absorbs the rounding remainder so shares sum exactly to the total
     *  (40.25 / 3 → 13.42, 13.42, 13.41). Shared by closeEven and quoteSplitEven. */
    static List<BigDecimal> evenShares(BigDecimal grandTotal, int ways) {
        BigDecimal base = grandTotal.divide(new BigDecimal(ways), 2, RoundingMode.HALF_UP);
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (int i = 0; i < ways; i++) {
            BigDecimal share = (i == ways - 1) ? grandTotal.subtract(allocated) : base;
            allocated = allocated.add(share);
            shares.add(share);
        }
        return shares;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> listOrderSaleIds(UUID orderId) {
        DiningOrder order = load(orderId);
        List<UUID> ids = new ArrayList<>();
        if (order.getSaleId() != null) {
            ids.add(order.getSaleId());
        }
        for (DiningOrderSale link : orderSales.findByOrderId(orderId)) {
            ids.add(link.getSaleId());
        }
        return ids;
    }

    @Override
    public void voidOrder(UUID orderId, String reason) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        order.voidOrder();
    }

    private TableView toTableView(DiningTable t) {
        return new TableView(t.getId(), t.getLabel(), t.getSeats(), t.isActive());
    }
}
