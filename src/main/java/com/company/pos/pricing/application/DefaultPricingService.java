package com.company.pos.pricing.application;

import com.company.pos.pricing.api.PricedLine;
import com.company.pos.pricing.api.PricingInput;
import com.company.pos.pricing.api.PricingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DefaultPricingService implements PricingService {

    @Override
    public List<PricedLine> price(List<PricingInput> inputs) {
        return inputs.stream().map(this::priceLine).toList();
    }

    private PricedLine priceLine(PricingInput input) {
        BigDecimal extended = input.unitPrice()
                .multiply(input.quantity())
                .setScale(2, RoundingMode.HALF_UP);
        return new PricedLine(input.sku(), input.name(), input.quantity(),
                input.unitPrice(), input.currencyCode(), extended);
    }
}
