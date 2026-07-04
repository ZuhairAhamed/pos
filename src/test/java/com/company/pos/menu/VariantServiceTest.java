package com.company.pos.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.pos.common.exception.DomainException;
import com.company.pos.integration.api.ErpProduct;
import com.company.pos.integration.erp.FakeErpClient;
import com.company.pos.menu.api.AddVariantMemberCommand;
import com.company.pos.menu.api.CreateVariantGroupCommand;
import com.company.pos.menu.api.MenuService;
import com.company.pos.menu.api.VariantGroupView;
import com.company.pos.menu.api.VariantMemberView;
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
class VariantServiceTest {

    @Autowired MenuService menu;
    @Autowired ProductSync productSync;
    @Autowired FakeErpClient fake;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    void seed() {
        cleaner.clean();
        fake.clear();
        fake.addProduct(new ErpProduct("BEER-S", "Draft Beer Small", "BEV", "Bev", "bcBS",
                "EA", new BigDecimal("4.00"), "SAR", 1, true));
        fake.addProduct(new ErpProduct("BEER-L", "Draft Beer Large", "BEV", "Bev", "bcBL",
                "EA", new BigDecimal("7.00"), "SAR", 1, true));
        productSync.sync();
    }

    @AfterEach
    void clean() {
        cleaner.clean();
        fake.clear();
    }

    @Test
    void createGroupAddMembersAndList() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Draft Beer"));
        menu.addVariantMember(g.id(), new AddVariantMemberCommand("BEER-S", "Small"));
        menu.addVariantMember(g.id(), new AddVariantMemberCommand("BEER-L", "Large"));

        assertThat(menu.listVariantGroups()).hasSize(1);
        VariantGroupView loaded = menu.listVariantGroups().get(0);
        assertThat(loaded.name()).isEqualTo("Draft Beer");
        assertThat(loaded.members()).extracting(VariantMemberView::sku)
                .containsExactlyInAnyOrder("BEER-S", "BEER-L");
    }

    @Test
    void memberWithUnknownSkuIsRejected() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("X"));
        assertThatThrownBy(() -> menu.addVariantMember(g.id(), new AddVariantMemberCommand("NOPE", "Nope")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void deactivatedGroupIsExcludedFromList() {
        VariantGroupView g = menu.createVariantGroup(new CreateVariantGroupCommand("Temp"));
        menu.deactivateVariantGroup(g.id());
        assertThat(menu.listVariantGroups()).extracting(VariantGroupView::id).doesNotContain(g.id());
    }
}
