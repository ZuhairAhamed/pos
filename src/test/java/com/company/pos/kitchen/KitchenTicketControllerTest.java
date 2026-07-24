package com.company.pos.kitchen;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.common.util.Identifiers;
import com.company.pos.kitchen.domain.KitchenTicket;
import com.company.pos.kitchen.infrastructure.KitchenTicketRepository;
import com.company.pos.support.DatabaseCleaner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class KitchenTicketControllerTest {

    @Autowired MockMvc mvc;
    @Autowired KitchenTicketRepository repo;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private UUID seedFired() {
        KitchenTicket t = new KitchenTicket(Identifiers.newId(), UUID.randomUUID(), "5", "Grill",
                Instant.parse("2026-07-24T10:00:00Z"));
        t.addLine("BURGER", "Beef Burger", new BigDecimal("1"), null, "MAIN", List.of());
        return repo.save(t).getId();
    }

    @Test
    void listsActiveTickets() throws Exception {
        seedFired();
        mvc.perform(get("/kitchen/tickets").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].station").value("Grill"))
                .andExpect(jsonPath("$[0].state").value("FIRED"));
    }

    @Test
    void advancesTicket() throws Exception {
        UUID id = seedFired();
        mvc.perform(post("/kitchen/tickets/" + id + "/advance")
                        .contentType("application/json")
                        .content("{\"expectedState\":\"FIRED\"}")
                        .with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PREPARING"));
    }

    @Test
    void staleExpectedStateReturns409() throws Exception {
        UUID id = seedFired();
        mvc.perform(post("/kitchen/tickets/" + id + "/advance")
                        .contentType("application/json").content("{\"expectedState\":\"READY\"}")
                        .with(cashier()))
                .andExpect(status().isConflict());
    }

    @Test
    void recallsTicket() throws Exception {
        UUID id = seedFired();
        mvc.perform(post("/kitchen/tickets/" + id + "/advance")
                        .contentType("application/json").content("{\"expectedState\":\"FIRED\"}")
                        .with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PREPARING"));
        mvc.perform(post("/kitchen/tickets/" + id + "/recall")
                        .contentType("application/json").content("{\"expectedState\":\"PREPARING\"}")
                        .with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FIRED"));
    }

    @Test
    void filtersTicketsByStation() throws Exception {
        seedFired();                 // station "Grill"
        seedFiredAt("Bar");          // second ticket at "Bar"
        mvc.perform(get("/kitchen/tickets").param("station", "Grill").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].station").value("Grill"));
    }

    private void seedFiredAt(String station) {
        KitchenTicket t = new KitchenTicket(Identifiers.newId(), UUID.randomUUID(), "5", station,
                Instant.parse("2026-07-24T10:01:00Z"));
        t.addLine("BURGER", "Beef Burger", new BigDecimal("1"), null, "MAIN", List.of());
        repo.save(t);
    }
}
