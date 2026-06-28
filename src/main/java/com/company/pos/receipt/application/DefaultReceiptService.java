package com.company.pos.receipt.application;

import com.company.pos.common.util.Monies;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.device.api.PrintLine;
import com.company.pos.device.api.Printer;
import com.company.pos.receipt.api.ReceiptData;
import com.company.pos.receipt.api.ReceiptLineData;
import com.company.pos.receipt.api.ReceiptService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
class DefaultReceiptService implements ReceiptService {

    private final Printer printer;
    private final ConfigurationService config;

    DefaultReceiptService(Printer printer, ConfigurationService config) {
        this.printer = printer;
        this.config = config;
    }

    @Override
    public void print(ReceiptData data) {
        String storeName = config.getString(SettingKey.STORE_NAME);
        Locale locale = Locale.forLanguageTag(config.getString(SettingKey.LOCALE));
        String currency = data.currencyCode();

        List<PrintLine> lines = new ArrayList<>();
        lines.add(new PrintLine(storeName, true));
        lines.add(new PrintLine("Receipt: " + data.receiptNumber(), false));
        lines.add(new PrintLine("Cashier: " + data.cashierName(), false));
        lines.add(new PrintLine("Date: " + data.timestamp(), false));
        lines.add(new PrintLine("--------------------------------", false));
        for (ReceiptLineData line : data.lines()) {
            lines.add(new PrintLine(line.name(), false));
            lines.add(new PrintLine("  " + line.quantity().stripTrailingZeros().toPlainString()
                    + " x " + money(line.unitPrice(), currency, locale)
                    + " = " + money(line.lineTotal(), currency, locale), false));
        }
        lines.add(new PrintLine("--------------------------------", false));
        lines.add(new PrintLine("Subtotal: " + money(data.subtotal(), currency, locale), false));
        lines.add(new PrintLine("Tax:      " + money(data.taxTotal(), currency, locale), false));
        lines.add(new PrintLine("TOTAL:    " + money(data.grandTotal(), currency, locale), true));
        lines.add(new PrintLine("Cash:     " + money(data.amountTendered(), currency, locale), false));
        lines.add(new PrintLine("Change:   " + money(data.changeDue(), currency, locale), false));

        printer.print(lines);
        printer.cut();
    }

    private String money(BigDecimal amount, String currency, Locale locale) {
        return Monies.format(Monies.of(amount, currency), locale);
    }
}
