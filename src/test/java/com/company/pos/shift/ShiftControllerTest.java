package com.company.pos.shift;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
class ShiftControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void openThenCloseShiftReturnsVariance() throws Exception {
        String opened = mvc.perform(post("/shifts").with(jwt().jwt(j -> j.subject("cashier")))
                        .contentType("application/json").content("{\"openingFloat\":100.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        String shiftId = JsonPath.read(opened, "$.shiftId");

        mvc.perform(post("/shifts/" + shiftId + "/close").with(jwt().jwt(j -> j.subject("cashier")))
                        .contentType("application/json").content("{\"countedCash\":97.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.cash.expectedCash").value(100.00))
                .andExpect(jsonPath("$.cash.variance").value(-2.50));
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mvc.perform(post("/shifts").contentType("application/json").content("{\"openingFloat\":50.00}"))
                .andExpect(status().isUnauthorized());
    }
}
