package com.company.pos.dining;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.OpenOrderCommand;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.ServiceType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Transactional
class DiningOpenOrdersControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DiningService dining;

    @Test
    void listOpenOrdersJsonIncludesServiceType() throws Exception {
        UUID table = dining.registerTable(
                new RegisterTableCommand("WEB-" + UUID.randomUUID(), 4)).id();
        UUID orderId = dining.openOrder(
                new OpenOrderCommand(table, ServiceType.QUICK_SERVICE), "cashier1").id();

        mvc.perform(get("/dining/orders").with(jwt().jwt(j -> j.subject("cashier1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.orderId=='" + orderId + "')].serviceType")
                        .value(org.hamcrest.Matchers.hasItem("QUICK_SERVICE")));
    }
}
