package com.company.pos.customer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.pos.cart.api.CartService;
import com.company.pos.cart.api.CartView;
import com.company.pos.customer.api.CustomerService;
import com.company.pos.customer.api.CustomerView;
import com.company.pos.customer.api.RegisterCustomerCommand;
import com.company.pos.support.DatabaseCleaner;
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
class CustomerCartAttachTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    CartService carts;
    @Autowired
    CustomerService customers;
    @Autowired
    DatabaseCleaner cleaner;

    @BeforeEach
    @AfterEach
    void clean() {
        cleaner.clean();
    }

    private static RequestPostProcessor cashier() {
        return jwt().jwt(j -> j.subject("cashier"))
                .authorities(new SimpleGrantedAuthority("ROLE_CASHIER"));
    }

    @Test
    void attachStampsCustomerOntoCart() throws Exception {
        UUID cartId = carts.createCart();
        CustomerView c = customers.register(new RegisterCustomerCommand("Aisha", "0501", null, null, null));

        mvc.perform(post("/customers/" + c.id() + "/cart/" + cartId).with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(c.id().toString()));

        assertThatCartCustomerIs(cartId, c.id());
    }

    @Test
    void attachUnknownCustomerFailsAndLeavesCartUntouched() throws Exception {
        UUID cartId = carts.createCart();

        mvc.perform(post("/customers/" + UUID.randomUUID() + "/cart/" + cartId).with(cashier()))
                .andExpect(status().isNotFound());

        assertThatCartCustomerIs(cartId, null);
    }

    @Test
    void attachInactiveCustomerFails() throws Exception {
        UUID cartId = carts.createCart();
        CustomerView c = customers.register(new RegisterCustomerCommand("Zoya", "0509", null, null, null));
        customers.deactivate(c.id());

        mvc.perform(post("/customers/" + c.id() + "/cart/" + cartId).with(cashier()))
                .andExpect(status().isBadRequest());

        assertThatCartCustomerIs(cartId, null);
    }

    @Test
    void detachClearsCustomer() throws Exception {
        UUID cartId = carts.createCart();
        CustomerView c = customers.register(new RegisterCustomerCommand("Bilal", "0502", null, null, null));
        carts.assignCustomer(cartId, c.id());

        mvc.perform(delete("/carts/" + cartId + "/customer").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").doesNotExist());

        assertThatCartCustomerIs(cartId, null);
    }

    private void assertThatCartCustomerIs(UUID cartId, UUID expected) {
        CartView view = carts.getCart(cartId);
        org.assertj.core.api.Assertions.assertThat(view.customerId()).isEqualTo(expected);
    }
}
