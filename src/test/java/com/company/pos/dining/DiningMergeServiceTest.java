package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderStatus;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
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
class DiningMergeServiceTest {

    @Autowired DiningService dining;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BURGER", "Beef Burger", "FOOD", "Food", "bcBURGER",
                "EA", new BigDecimal("30.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("FRIES", "Fries", "FOOD", "Food", "bcFRIES",
                "EA", new BigDecimal("12.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    private UUID freshTable(String label) {
        return dining.registerTable(new RegisterTableCommand(label + UUID.randomUUID(), 4)).id();
    }

    private UUID openWithLine(UUID tableId, String sku, String qty, CourseTag course, String note) {
        UUID orderId = dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
        dining.addLine(orderId, new AddLineCommand(sku, new BigDecimal(qty), note, course), "alice");
        return orderId;
    }

    @Test
    void mergeFoldsAbsorbedLinesOntoSurvivor() {
        UUID survivorTable = freshTable("SURV");
        UUID absorbedTable = freshTable("ABSB");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        UUID absorbed = openWithLine(absorbedTable, "FRIES", "2", CourseTag.STARTER, "extra salt");

        OrderView merged = dining.mergeOrders(survivor, absorbed);

        assertThat(merged.id()).isEqualTo(survivor);
        assertThat(merged.status()).isEqualTo(OrderStatus.OPEN);
        assertThat(merged.lines()).hasSize(2);
        assertThat(merged.lines()).extracting(l -> l.sku()).containsExactlyInAnyOrder("BURGER", "FRIES");
    }

    @Test
    void mergedLinePreservesQtyNoteAndCourse() {
        UUID survivorTable = freshTable("SURV");
        UUID absorbedTable = freshTable("ABSB");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        UUID absorbed = openWithLine(absorbedTable, "FRIES", "2", CourseTag.STARTER, "extra salt");

        OrderView merged = dining.mergeOrders(survivor, absorbed);

        var fries = merged.lines().stream().filter(l -> l.sku().equals("FRIES")).findFirst().orElseThrow();
        assertThat(fries.qty()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(fries.note()).isEqualTo("extra salt");
        assertThat(fries.course()).isEqualTo(CourseTag.STARTER);
    }

    @Test
    void firedAbsorbedLineArrivesStillFired() {
        UUID survivorTable = freshTable("SURV");
        UUID absorbedTable = freshTable("ABSB");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        UUID absorbed = openWithLine(absorbedTable, "FRIES", "2", CourseTag.STARTER, null);
        dining.fireOrder(absorbed, "alice");

        OrderView merged = dining.mergeOrders(survivor, absorbed);

        var fries = merged.lines().stream().filter(l -> l.sku().equals("FRIES")).findFirst().orElseThrow();
        assertThat(fries.firedAt()).isNotNull(); // fired state rides along
    }

    @Test
    void absorbedOrderIsVoidedAndItsTableFreed() {
        UUID survivorTable = freshTable("SURV");
        UUID absorbedTable = freshTable("ABSB");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        UUID absorbed = openWithLine(absorbedTable, "FRIES", "2", CourseTag.STARTER, null);

        dining.mergeOrders(survivor, absorbed);

        assertThat(dining.getOrder(absorbed).status()).isEqualTo(OrderStatus.VOIDED);
        // the absorbed table now has no OPEN order → it would be reusable
        assertThat(dining.listOpenOrders().stream().anyMatch(o -> o.tableId().equals(absorbedTable)))
                .isFalse();
    }

    @Test
    void rejectsMergeIntoItself() {
        UUID t = freshTable("SURV");
        UUID order = openWithLine(t, "BURGER", "1", CourseTag.MAIN, null);
        assertThatThrownBy(() -> dining.mergeOrders(order, order))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsAbsorbedWithNoLines() {
        UUID survivorTable = freshTable("SURV");
        UUID absorbedTable = freshTable("ABSB");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        UUID emptyAbsorbed = dining.openOrder(new OpenOrderCommand(absorbedTable, null), "alice").id();
        assertThatThrownBy(() -> dining.mergeOrders(survivor, emptyAbsorbed))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsMergeOfNonOpenAbsorbed() {
        UUID survivorTable = freshTable("SURV");
        UUID absorbedTable = freshTable("ABSB");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        UUID absorbed = openWithLine(absorbedTable, "FRIES", "1", CourseTag.STARTER, null);
        dining.voidOrder(absorbed, "test");
        assertThatThrownBy(() -> dining.mergeOrders(survivor, absorbed))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsUnknownOrder() {
        UUID survivorTable = freshTable("SURV");
        UUID survivor = openWithLine(survivorTable, "BURGER", "1", CourseTag.MAIN, null);
        assertThatThrownBy(() -> dining.mergeOrders(survivor, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }
}
