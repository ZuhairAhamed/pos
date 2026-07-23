package com.company.pos.menu;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@Import(DatabaseCleaner.class)
class MenuControllerTest {

    @Autowired MockMvc mvc;
    @Autowired DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier")).authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    private static RequestPostProcessor manager() {
        return jwt().jwt(j -> j.subject("manager")).authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    @Test
    void managerCreatesModifierGroup() throws Exception {
        mvc.perform(post("/menu/modifier-groups").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Add-ons\",\"minSelections\":0,\"maxSelections\":2}"))
                .andExpect(status().isCreated());
    }

    @Test
    void cashierCannotCreateModifierGroup() throws Exception {
        mvc.perform(post("/menu/modifier-groups").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Add-ons\",\"minSelections\":0,\"maxSelections\":2}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyCashierCanReadVariantGroups() throws Exception {
        mvc.perform(get("/menu/variant-groups").with(cashier()))
                .andExpect(status().isOk());
    }

    @Test
    void managerCreatesVariantGroup() throws Exception {
        mvc.perform(post("/menu/variant-groups").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Draft Beer\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void cashierCannotCreateVariantGroup() throws Exception {
        mvc.perform(post("/menu/variant-groups").with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Draft Beer\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCanListModifierGroups() throws Exception {
        mvc.perform(get("/menu/modifier-groups").with(manager()))
                .andExpect(status().isOk());
    }

    @Test
    void cashierCannotListModifierGroups() throws Exception {
        mvc.perform(get("/menu/modifier-groups").with(cashier()))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerEditsModifierGroup() throws Exception {
        String created = mvc.perform(post("/menu/modifier-groups").with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Add-ons\",\"minSelections\":0,\"maxSelections\":2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");

        mvc.perform(put("/menu/modifier-groups/" + id).with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Extras\",\"minSelections\":1,\"maxSelections\":3}"))
                .andExpect(status().isOk());
    }

    @Test
    void cashierCannotEditModifierGroup() throws Exception {
        mvc.perform(put("/menu/modifier-groups/" + java.util.UUID.randomUUID()).with(cashier())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Extras\",\"minSelections\":1,\"maxSelections\":3}"))
                .andExpect(status().isForbidden());
    }
}
