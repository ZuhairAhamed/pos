package com.company.pos.sales.application;

import com.company.pos.sales.domain.SaleNumberSequence;
import com.company.pos.sales.infrastructure.SaleNumberSequenceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReceiptNumbering {

    private final SaleNumberSequenceRepository sequences;

    ReceiptNumbering(SaleNumberSequenceRepository sequences) {
        this.sequences = sequences;
    }

    @Transactional
    public String nextReceiptNumber(String storeId, String terminalId) {
        String key = storeId + "-" + terminalId;
        SaleNumberSequence sequence = sequences.findById(key)
                .orElseGet(() -> sequences.save(new SaleNumberSequence(key)));
        long value = sequence.takeNext();
        sequences.save(sequence);
        return String.format("%s-%s-%06d", storeId, terminalId, value);
    }
}
