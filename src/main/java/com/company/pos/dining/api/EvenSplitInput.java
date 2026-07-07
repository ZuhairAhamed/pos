package com.company.pos.dining.api;

import com.company.pos.payment.api.PaymentMethod;
import java.util.List;

/** An even N-way split: {@code methods.size()} must equal {@code ways} (one payment method per share). */
public record EvenSplitInput(int ways, List<PaymentMethod> methods) {
}
