package com.company.pos.customer.api;

public record RegisterCustomerCommand(String name, String phone, String email,
        String address, String notes) {
}
