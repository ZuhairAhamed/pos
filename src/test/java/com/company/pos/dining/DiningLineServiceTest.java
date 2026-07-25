package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.common.exception.ErrorCode;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderLineView;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.product.application.ProductAdminService;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class DiningLineServiceTest {

    @Autowired DiningService dining;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;
    @Autowired ProductAdminService productAdmin;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID openOrderOnFreshTable() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        return dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
    }

    @Test
    void addsLineWithNoteAndCourse() {
        UUID orderId = openOrderOnFreshTable();
        OrderView order = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("2"), "no onions", CourseTag.MAIN), "alice");

        assertThat(order.lines()).hasSize(1);
        OrderLineView line = order.lines().get(0);
        assertThat(line.sku()).isEqualTo("BURGER");
        assertThat(line.qty()).isEqualByComparingTo("2");
        assertThat(line.note()).isEqualTo("no onions");
        assertThat(line.course()).isEqualTo(CourseTag.MAIN);
    }

    @Test
    void rejectsUnknownSku() {
        UUID orderId = openOrderOnFreshTable();
        assertThatThrownBy(() -> dining.addLine(orderId,
                new AddLineCommand("NOPE", new BigDecimal("1"), null, null), "alice"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNonPositiveQty() {
        UUID orderId = openOrderOnFreshTable();
        assertThatThrownBy(() -> dining.addLine(orderId,
                new AddLineCommand("BURGER", BigDecimal.ZERO, null, null), "alice"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void updatesLineQtyNoteAndCourse() {
        UUID orderId = openOrderOnFreshTable();
        UUID lineId = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, null), "alice")
                .lines().get(0).id();

        OrderView updated = dining.updateLine(orderId, lineId, new BigDecimal("3"), "extra cheese",
                CourseTag.MAIN);

        OrderLineView line = updated.lines().get(0);
        assertThat(line.qty()).isEqualByComparingTo("3");
        assertThat(line.note()).isEqualTo("extra cheese");
        assertThat(line.course()).isEqualTo(CourseTag.MAIN);
    }

    @Test
    void removesLine() {
        UUID orderId = openOrderOnFreshTable();
        UUID lineId = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, null), "alice")
                .lines().get(0).id();

        OrderView afterRemove = dining.removeLine(orderId, lineId);
        assertThat(afterRemove.lines()).isEmpty();
    }

    @Test
    void rejectsRemoveOfUnknownLine() {
        UUID orderId = openOrderOnFreshTable();
        assertThatThrownBy(() -> dining.removeLine(orderId, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsUpdateOfUnknownLine() {
        UUID orderId = openOrderOnFreshTable();
        assertThatThrownBy(() -> dining.updateLine(orderId, UUID.randomUUID(),
                new BigDecimal("1"), null, null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNegativeQty() {
        UUID orderId = openOrderOnFreshTable();
        assertThatThrownBy(() -> dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("-1"), null, null), "alice"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void addLineRejectsEightySixedSku() {
        productAdmin.setAvailability("BURGER", false);
        UUID orderId = openOrderOnFreshTable();
        assertThatThrownBy(() -> dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "cashier"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).errorCode())
                        .isEqualTo(ErrorCode.CONFLICT));
    }
}
