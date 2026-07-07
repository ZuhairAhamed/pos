package com.company.pos.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.dining.api.AddLineCommand;
import com.company.pos.dining.api.CourseTag;
import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.OrderView;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.product.api.ProductSync;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Instant;
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
class DiningFireServiceTest {

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

    private UUID openOrderOnFreshTable() {
        UUID tableId = dining.registerTable(new RegisterTableCommand("L" + UUID.randomUUID(), 4)).id();
        return dining.openOrder(new OpenOrderCommand(tableId, null), "alice").id();
    }

    @Test
    void fireStampsAllUnfiredLines() {
        UUID orderId = openOrderOnFreshTable();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice");

        OrderView fired = dining.fireOrder(orderId, "alice");

        assertThat(fired.lines()).hasSize(1);
        assertThat(fired.lines().get(0).firedAt()).isNotNull();
    }

    @Test
    void secondFireRoutesOnlyNewlyAddedLines() {
        UUID orderId = openOrderOnFreshTable();
        UUID burgerLineId = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice")
                .lines().get(0).id();
        dining.fireOrder(orderId, "alice");
        Instant burgerFiredAt = dining.getOrder(orderId).lines().stream()
                .filter(l -> l.id().equals(burgerLineId)).findFirst().orElseThrow().firedAt();

        dining.addLine(orderId, new AddLineCommand("FRIES", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        OrderView afterSecondFire = dining.fireOrder(orderId, "alice");

        // the burger's firedAt is unchanged; the fries line is now fired too
        assertThat(afterSecondFire.lines()).allSatisfy(l -> assertThat(l.firedAt()).isNotNull());
        Instant burgerAfter = afterSecondFire.lines().stream()
                .filter(l -> l.id().equals(burgerLineId)).findFirst().orElseThrow().firedAt();
        assertThat(burgerAfter).isEqualTo(burgerFiredAt);
    }

    @Test
    void firingWithNothingUnfiredIsRejected() {
        UUID orderId = openOrderOnFreshTable();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        dining.fireOrder(orderId, "alice");

        assertThatThrownBy(() -> dining.fireOrder(orderId, "alice"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void updatingAFiredLineIsRejected() {
        UUID orderId = openOrderOnFreshTable();
        UUID lineId = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice")
                .lines().get(0).id();
        dining.fireOrder(orderId, "alice");

        assertThatThrownBy(() -> dining.updateLine(orderId, lineId, new BigDecimal("2"), null, CourseTag.MAIN))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void removingAFiredLineIsRejected() {
        UUID orderId = openOrderOnFreshTable();
        UUID lineId = dining.addLine(orderId,
                new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice")
                .lines().get(0).id();
        dining.fireOrder(orderId, "alice");

        assertThatThrownBy(() -> dining.removeLine(orderId, lineId))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void unfiredLinesRemainEditableAfterAPartialFire() {
        UUID orderId = openOrderOnFreshTable();
        dining.addLine(orderId, new AddLineCommand("BURGER", new BigDecimal("1"), null, CourseTag.MAIN), "alice");
        dining.fireOrder(orderId, "alice");
        UUID friesLineId = dining.addLine(orderId,
                new AddLineCommand("FRIES", new BigDecimal("1"), null, CourseTag.MAIN), "alice")
                .lines().stream().filter(l -> l.sku().equals("FRIES")).findFirst().orElseThrow().id();

        OrderView updated = dining.updateLine(orderId, friesLineId, new BigDecimal("3"), "crispy", CourseTag.MAIN);

        assertThat(updated.lines().stream().filter(l -> l.id().equals(friesLineId)).findFirst()
                .orElseThrow().qty()).isEqualByComparingTo("3");
    }
}
