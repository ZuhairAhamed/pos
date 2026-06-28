package com.company.pos.sales.api;

import java.util.UUID;

public interface SalesService {

    SaleView checkout(CheckoutCommand command, String cashierUsername);

    SaleView getSale(UUID saleId);

    void reprint(UUID saleId);
}
