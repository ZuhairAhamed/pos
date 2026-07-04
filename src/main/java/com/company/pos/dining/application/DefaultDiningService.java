package com.company.pos.dining.application;

import com.company.pos.cart.api.CartService;
import com.company.pos.common.exception.DomainException;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CloseOrderCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OpenOrderView;
import com.company.pos.dining.api.OrderLineView;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import com.company.pos.dining.api.TableView;
import com.company.pos.dining.domain.DiningOrder;
import com.company.pos.dining.domain.DiningTable;
import com.company.pos.dining.domain.OrderLine;
import com.company.pos.dining.infrastructure.DiningOrderRepository;
import com.company.pos.dining.infrastructure.DiningTableRepository;
import com.company.pos.product.api.ProductCatalog;
import com.company.pos.sales.api.CheckoutCommand;
import com.company.pos.sales.api.SaleView;
import com.company.pos.sales.api.SalesService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultDiningService implements DiningService {

    private final DiningTableRepository tables;
    private final DiningOrderRepository orders;
    private final ConfigurationService config;
    private final ProductCatalog products;
    private final CartService carts;
    private final SalesService sales;

    DefaultDiningService(DiningTableRepository tables, DiningOrderRepository orders,
            ConfigurationService config, ProductCatalog products,
            CartService carts, SalesService sales) {
        this.tables = tables;
        this.orders = orders;
        this.config = config;
        this.products = products;
        this.carts = carts;
        this.sales = sales;
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
                        l.getCourse()))
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

    // --- close / void (Task 5) ---
    @Override
    public SaleView closeOrder(UUID orderId, CloseOrderCommand command, String cashierUsername,
            boolean callerIsManager) {
        DiningOrder order = load(orderId);
        requireOpen(order);
        if (order.getLines().isEmpty()) {
            throw DomainException.validation("Cannot close an empty order");
        }

        // Aggregate lines by sku so the throwaway cart has one line per sku — this keeps the
        // cart's one-line-per-sku shape and lets sku-keyed line discounts map cleanly.
        Map<String, BigDecimal> bySku = new LinkedHashMap<>();
        for (OrderLine line : order.getLines()) {
            bySku.merge(line.getSku(), line.getQty(), BigDecimal::add);
        }

        UUID cartId = carts.createCart();
        bySku.forEach((sku, qty) -> carts.addLine(cartId, sku, qty));

        SaleView sale = sales.checkout(
                new CheckoutCommand(cartId, command.tenders(), command.lineDiscounts(),
                        command.transactionDiscount()),
                cashierUsername, callerIsManager);

        carts.close(cartId);
        order.close(sale.id(), Instant.now());
        return sale;
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
