package com.company.pos.sales.api;

import java.util.UUID;

public interface ReturnService {

    ReturnView processReturn(ReturnCommand command, String managerUsername);

    ReturnView getReturn(UUID returnId);
}
