package com.company.pos.customer.api;

public record UpdateCustomerCommand(String name, String phone, String email,
        String address, String notes) {
}
