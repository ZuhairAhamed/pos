package com.company.pos.terminal.viewmodel;

import com.company.pos.terminal.api.ApiException;
import com.company.pos.terminal.api.DiningApi;
import com.company.pos.terminal.api.dto.BillRequest;
import com.company.pos.terminal.api.dto.EvenSplitRequest;
import com.company.pos.terminal.api.dto.OrderLineView;
import com.company.pos.terminal.api.dto.QuoteBillInput;
import com.company.pos.terminal.api.dto.QuoteEvenInput;
import com.company.pos.terminal.api.dto.QuoteSplitRequest;
import com.company.pos.terminal.api.dto.SaleView;
import com.company.pos.terminal.api.dto.SplitCloseRequest;
import com.company.pos.terminal.api.dto.SplitQuoteView;
import com.company.pos.terminal.api.dto.TenderInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/**
 * ViewModel for the split-bill screen. Synchronous like the other VMs — the controller runs it
 * off the FX thread via FxTasks. Plain fields hold ALL control-flow state (the recurring
 * off-FX-thread lesson); the only observable is {@link #errorMessage()}, mirrored through the
 * injected {@code ui} dispatcher.
 *
 * <p><b>Amounts are never computed client-side.</b> {@link #billAmount} and the close request
 * come exclusively from the stored quote-split response; a partition/mode/ways change clears the
 * quote so stale amounts can never be tendered ({@link #canCloseAll} requires {@link #quoted()}).
 */
public class SplitViewModel {

    public static final String BY_ITEM = "BY_ITEM";
    public static final String EVEN = "EVEN";

    private static final int MIN_WAYS = 2;
    private static final int MAX_WAYS = 8;
    private static final int MIN_GUESTS = 2;
    private static final int MAX_GUESTS = 6;

    private final DiningApi dining;
    private final Consumer<Runnable> ui;

    // --- partition state (plain fields = truth) ---
    private volatile String mode = BY_ITEM;
    private volatile int ways = MIN_WAYS;
    private volatile int guestCount = MIN_GUESTS;
    private volatile int activeGuest = 0;
    private volatile List<OrderLineView> lines = List.of();
    private final Map<UUID, Integer> assignment = new LinkedHashMap<>(); // lineId -> guest index

    // --- quote state (captured at quote time so close always matches the quote) ---
    private volatile SplitQuoteView quote;
    private volatile List<List<UUID>> quotedBills;   // BY_ITEM: lineIds per bill
    private volatile List<Integer> quotedGuests;     // BY_ITEM: original guest index per bill
    private volatile String quotedMode;
    private volatile int quotedWays;

    // --- waiver state ---
    private boolean waiveServiceCharge;
    private String approvalToken;

    // --- tender state (index-aligned with bills/shares) ---
    private final List<String> methods = new ArrayList<>();
    private final List<BigDecimal> cashTendered = new ArrayList<>();

    private volatile List<SaleView> results = List.of();

    private final ReadOnlyStringWrapper errorMessage = new ReadOnlyStringWrapper("");

    public SplitViewModel(DiningApi dining) {
        this(dining, Runnable::run);
    }

    public SplitViewModel(DiningApi dining, Consumer<Runnable> ui) {
        this.dining = dining;
        this.ui = ui;
    }

    public ReadOnlyStringProperty errorMessage() { return errorMessage.getReadOnlyProperty(); }

    public void setWaiveServiceCharge(boolean waive) { this.waiveServiceCharge = waive; }

    public void setApprovalToken(String token) { this.approvalToken = token; }

    /** True when the current split quote includes a non-zero service charge (any bill / the order). */
    public boolean quoteHasServiceCharge() {
        if (quote == null) {
            return false;
        }
        if (quote.order() != null && quote.order().serviceChargeAmount() != null) {
            return quote.order().serviceChargeAmount().signum() > 0;
        }
        return quote.bills() != null && quote.bills().stream()
                .anyMatch(b -> b.serviceChargeAmount() != null && b.serviceChargeAmount().signum() > 0);
    }

    /** Surfaces a message through the bound error property. */
    public void setError(String message) {
        ui.accept(() -> errorMessage.set(message));
    }

    // --- load ---

    /** Fetches the order's lines. Synchronous; run off the FX thread. */
    public void load(UUID orderId) {
        lines = dining.order(orderId).lines();
    }

    public List<OrderLineView> lines() { return lines; }

    // --- partition phase ---

    public String mode() { return mode; }

    public void setMode(String newMode) {
        if (!BY_ITEM.equals(newMode) && !EVEN.equals(newMode)) {
            return;
        }
        if (!newMode.equals(mode)) {
            mode = newMode;
            invalidateQuote();
        }
    }

    public int ways() { return ways; }

    /** Sets the EVEN way count; rejects values outside [2, 8]. */
    public boolean setWays(int n) {
        if (n < MIN_WAYS || n > MAX_WAYS) {
            return false;
        }
        if (n != ways) {
            ways = n;
            invalidateQuote();
        }
        return true;
    }

    public int guestCount() { return guestCount; }

    /** Adds a guest tab (max 6). Adding a guest alone changes no assignment, so no invalidate. */
    public boolean addGuest() {
        if (guestCount >= MAX_GUESTS) {
            return false;
        }
        guestCount++;
        return true;
    }

    public int activeGuest() { return activeGuest; }

    public void setActiveGuest(int guest) {
        if (guest >= 0 && guest < guestCount) {
            activeGuest = guest;
        }
    }

    /** Tap a line: unassigned or other-guest → assign to the active guest; already the active
     *  guest's → unassign. Any change invalidates the quote. */
    public void toggleAssign(UUID lineId) {
        Integer current = assignment.get(lineId);
        if (current != null && current == activeGuest) {
            assignment.remove(lineId);
        } else {
            assignment.put(lineId, activeGuest);
        }
        invalidateQuote();
    }

    /** The guest index a line is assigned to, or null. */
    public Integer guestOf(UUID lineId) { return assignment.get(lineId); }

    public int unassignedCount() {
        int n = 0;
        for (OrderLineView line : lines) {
            if (!assignment.containsKey(line.id())) {
                n++;
            }
        }
        return n;
    }

    public boolean canContinue() {
        if (lines.isEmpty()) {
            return false;
        }
        return EVEN.equals(mode) || unassignedCount() == 0;
    }

    // --- quote ---

    /** Prices the current partition on the server. Captures the partition it quoted so the
     *  close request is built from exactly what was priced. Returns false (with the error
     *  surfaced) on rejection. */
    public boolean quoteSplit(UUID orderId) {
        if (!canContinue()) {
            ui.accept(() -> errorMessage.set(EVEN.equals(mode)
                    ? "Nothing to split" : "Assign every item to a guest first"));
            return false;
        }
        try {
            SplitQuoteView q;
            if (BY_ITEM.equals(mode)) {
                List<List<UUID>> bills = new ArrayList<>();
                List<Integer> guests = new ArrayList<>();
                for (int g = 0; g < guestCount; g++) {
                    List<UUID> lineIds = linesOfGuest(g);
                    if (!lineIds.isEmpty()) {          // empty guests are dropped
                        bills.add(lineIds);
                        guests.add(g);
                    }
                }
                q = dining.quoteSplit(orderId, new QuoteSplitRequest(BY_ITEM,
                        bills.stream().map(QuoteBillInput::new).toList(), null, waiveServiceCharge));
                quotedBills = bills;
                quotedGuests = guests;
            } else {
                q = dining.quoteSplit(orderId, new QuoteSplitRequest(EVEN, null,
                        new QuoteEvenInput(ways), waiveServiceCharge));
                quotedBills = null;
                quotedGuests = null;
            }
            quotedMode = mode;
            quotedWays = ways;
            quote = q;
            resetTenders(billCount());
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return false;
        }
    }

    private List<UUID> linesOfGuest(int guest) {
        List<UUID> ids = new ArrayList<>();
        for (OrderLineView line : lines) {           // order-of-lines, stable across renders
            Integer g = assignment.get(line.id());
            if (g != null && g == guest) {
                ids.add(line.id());
            }
        }
        return ids;
    }

    public boolean quoted() { return quote != null; }

    /** The mode the current quote was priced under ("BY_ITEM"/"EVEN"), null when not quoted. */
    public String quotedMode() { return quotedMode; }

    private void invalidateQuote() {
        quote = null;
        quotedBills = null;
        quotedGuests = null;
        results = List.of();
    }

    private void resetTenders(int bills) {
        methods.clear();
        cashTendered.clear();
        for (int i = 0; i < bills; i++) {
            methods.add(null);
            cashTendered.add(null);
        }
    }

    // --- tender phase (all amounts from the quote only) ---

    /** Number of bills to tender: BY_ITEM bill count, or EVEN ways. */
    public int billCount() {
        SplitQuoteView q = quote;
        if (q == null) {
            return 0;
        }
        return BY_ITEM.equals(quotedMode) ? q.bills().size() : q.shares().size();
    }

    /** "Guest N" — BY_ITEM keeps the original guest number even after empty guests are
     *  dropped; EVEN numbers shares 1..ways. */
    public String billLabel(int billIdx) {
        if (BY_ITEM.equals(quotedMode)) {
            return "Guest " + (quotedGuests.get(billIdx) + 1);
        }
        return "Guest " + (billIdx + 1);
    }

    /** The server-authoritative amount this guest pays. */
    public BigDecimal billAmount(int billIdx) {
        SplitQuoteView q = quote;
        return BY_ITEM.equals(quotedMode)
                ? q.bills().get(billIdx).grandTotal()
                : q.shares().get(billIdx);
    }

    public String currencyCode() {
        SplitQuoteView q = quote;
        if (q == null) {
            return "";
        }
        return BY_ITEM.equals(quotedMode) ? q.bills().get(0).currencyCode()
                : q.order().currencyCode();
    }

    public void setMethod(int billIdx, String method) { methods.set(billIdx, method); }

    public String methodOf(int billIdx) { return methods.get(billIdx); }

    public void setCashTendered(int billIdx, BigDecimal tendered) {
        cashTendered.set(billIdx, tendered);
    }

    public BigDecimal cashTenderedOf(int billIdx) { return cashTendered.get(billIdx); }

    /** Change preview for a cash bill: tendered − amount (may be negative while short). */
    public BigDecimal changeFor(int billIdx) {
        BigDecimal tendered = cashTendered.get(billIdx);
        if (tendered == null) {
            return BigDecimal.ZERO;
        }
        return tendered.setScale(2, RoundingMode.HALF_UP).subtract(billAmount(billIdx));
    }

    /** Cash-with-change is only possible on BY_ITEM bills; EVEN shares are exact-amount by
     *  design (the server fixes tendered == share). */
    public boolean cashChangeAllowed() { return BY_ITEM.equals(quotedMode); }

    /** Every bill has a method; BY_ITEM cash bills additionally need tendered ≥ amount. */
    public boolean canCloseAll() {
        if (quote == null || billCount() == 0) {
            return false;
        }
        for (int i = 0; i < billCount(); i++) {
            String method = methods.get(i);
            if (method == null) {
                return false;
            }
            if (cashChangeAllowed() && "CASH".equals(method)) {
                BigDecimal tendered = cashTendered.get(i);
                if (tendered == null || tendered.compareTo(billAmount(i)) < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Closes ALL bills in one atomic server call. On failure the server rolls back everything
     *  (order stays OPEN) and the error is surfaced; tender state is preserved for retry. */
    public boolean closeAll(UUID orderId) {
        if (!canCloseAll()) {
            ui.accept(() -> errorMessage.set("Choose a payment for every guest"));
            return false;
        }
        SplitCloseRequest req;
        if (BY_ITEM.equals(quotedMode)) {
            List<BillRequest> bills = new ArrayList<>();
            for (int i = 0; i < quotedBills.size(); i++) {
                String method = methods.get(i);
                BigDecimal amount = billAmount(i);
                BigDecimal tendered = "CASH".equals(method)
                        ? cashTendered.get(i).setScale(2, RoundingMode.HALF_UP) : null;
                bills.add(new BillRequest(quotedBills.get(i),
                        List.of(new TenderInput(method, amount, tendered)), Map.of(), null));
            }
            req = new SplitCloseRequest(BY_ITEM, bills, null, waiveServiceCharge);
        } else {
            req = new SplitCloseRequest(EVEN, null,
                    new EvenSplitRequest(quotedWays, new ArrayList<>(methods)), waiveServiceCharge);
        }
        try {
            results = approvalToken != null
                    ? dining.closeSplit(orderId, req, approvalToken)
                    : dining.closeSplit(orderId, req);   // SYNCHRONOUS — control-flow state
            ui.accept(() -> errorMessage.set(""));
            return true;
        } catch (ApiException e) {
            String msg = messageOf(e);
            ui.accept(() -> errorMessage.set(msg));
            return false;
        }
    }

    public List<SaleView> results() { return results; }

    private String messageOf(ApiException e) {
        if (e.problem() != null) {
            if (e.problem().detail() != null && !e.problem().detail().isBlank()) {
                return e.problem().detail();
            }
            if (e.problem().title() != null && !e.problem().title().isBlank()) {
                return e.problem().title();
            }
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return "Request failed";
    }
}
