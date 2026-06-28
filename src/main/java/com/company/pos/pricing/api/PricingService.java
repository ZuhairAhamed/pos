package com.company.pos.pricing.api;

import java.util.List;

public interface PricingService {

    List<PricedLine> price(List<PricingInput> inputs);
}
