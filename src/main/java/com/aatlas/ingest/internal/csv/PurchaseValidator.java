package com.aatlas.ingest.internal.csv;

import com.aatlas.ingest.internal.csv.ImportReport.RowIssue;
import com.aatlas.suppliers.SupplierResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Validates a purchase-order line file.
 *
 * <p>The delivery columns are never errors: a promised or received date that cannot be read
 * is dropped with a warning and the line still loads, because the cost and quantity are the
 * facts and the dates only decide whether on-time performance can be measured.
 */
@Component
public class PurchaseValidator extends AbstractValidator<PurchaseRow> {

    private final ObjectProvider<SupplierResolver> suppliers;

    public PurchaseValidator(ObjectProvider<SupplierResolver> suppliers) {
        this.suppliers = suppliers;
    }

    @Override
    public ImportKind kind() {
        return ImportKind.PURCHASES;
    }

    @Override
    protected List<RowIssue> checkRow(List<String> row, ColumnMapping m, ValidationContext ctx, int line,
            FileState state) {
        List<RowIssue> issues = new ArrayList<>();

        if (cell(row, m, PurchaseField.ITEM).isEmpty()) {
            issues.add(RowIssue.error(line, PurchaseField.ITEM, "Item number is blank"));
        }

        String rawOrder = cell(row, m, PurchaseField.ORDER_DATE);
        Optional<LocalDate> orderDate = ValueParsers.parseDate(rawOrder, ctx.dateOrder());
        if (orderDate.isEmpty()) {
            issues.add(RowIssue.error(line, PurchaseField.ORDER_DATE, ctx.dateError(rawOrder)));
        }

        if (cell(row, m, PurchaseField.SUPPLIER).isEmpty()) {
            issues.add(RowIssue.error(line, PurchaseField.SUPPLIER, "Supplier is blank"));
        }

        Optional<BigDecimal> qty = ValueParsers.parseNumber(cell(row, m, PurchaseField.QTY));
        if (qty.isEmpty()) {
            issues.add(RowIssue.error(line, PurchaseField.QTY, "Quantity ordered is not a number"));
        } else if (qty.get().signum() <= 0) {
            issues.add(RowIssue.error(line, PurchaseField.QTY, "Quantity ordered is " + plain(qty.get())
                    + " — cancellations and returns should be excluded"));
        } else if (qty.get().stripTrailingZeros().scale() > 0) {
            issues.add(RowIssue.warning(line, PurchaseField.QTY, "Quantity " + plain(qty.get())
                    + " rounded to " + qty.get().setScale(0, RoundingMode.HALF_UP).toPlainString()
                    + " — the ledger counts whole units"));
        }

        Optional<BigDecimal> unitCost = ValueParsers.parseNumber(cell(row, m, PurchaseField.UNIT_COST));
        if (unitCost.isEmpty()) {
            issues.add(RowIssue.error(line, PurchaseField.UNIT_COST, "Unit cost is not a number"));
        } else if (unitCost.get().signum() < 0) {
            issues.add(RowIssue.error(line, PurchaseField.UNIT_COST, "Unit cost is negative"));
        } else if (unitCost.get().signum() == 0) {
            issues.add(RowIssue.warning(line, PurchaseField.UNIT_COST,
                    "Unit cost is zero — free-of-charge lines should be excluded"));
        }

        String country = cell(row, m, PurchaseField.SUPPLIER_COUNTRY);
        SupplierResolver resolver = suppliers == null ? null : suppliers.getIfAvailable();
        if (!country.isEmpty() && resolver != null && !resolver.hasLane(resolver.canonicalCountry(country))) {
            issues.add(RowIssue.warning(line, PurchaseField.SUPPLIER_COUNTRY, "Country \"" + country
                    + "\" has no shipping lane — freight and duty will use the default estimate"));
        }

        Optional<BigDecimal> freight = numberOrWarn(row, m, PurchaseField.FREIGHT, "Freight", line, issues);
        Optional<BigDecimal> duty = numberOrWarn(row, m, PurchaseField.DUTY, "Duty", line, issues);
        Optional<BigDecimal> landed = numberOrWarn(row, m, PurchaseField.LANDED_COST, "Landed cost", line, issues);
        if (landed.isPresent() && unitCost.isPresent() && landed.get().compareTo(unitCost.get()) < 0) {
            issues.add(RowIssue.warning(line, PurchaseField.LANDED_COST, "Landed cost " + plain(landed.get())
                    + " is below ex-works " + plain(unitCost.get()) + " — kept as written"));
        }

        checkCurrency(row, m, PurchaseField.CURRENCY, ctx, line).ifPresent(issues::add);

        String rawPromised = cell(row, m, PurchaseField.PROMISED_DATE);
        Optional<LocalDate> promised = Optional.empty();
        if (!rawPromised.isEmpty()) {
            promised = ValueParsers.parseDate(rawPromised, ctx.dateOrder());
            if (promised.isEmpty()) {
                issues.add(RowIssue.warning(line, PurchaseField.PROMISED_DATE,
                        "Could not read the promised date \"" + rawPromised + "\" — ignored"));
            } else if (orderDate.isPresent() && promised.get().isBefore(orderDate.get())) {
                issues.add(RowIssue.warning(line, PurchaseField.PROMISED_DATE,
                        "Promised date is before the order date — ignored"));
            }
        }

        String rawReceived = cell(row, m, PurchaseField.RECEIVED_DATE);
        if (!rawReceived.isEmpty()) {
            Optional<LocalDate> received = ValueParsers.parseDate(rawReceived, ctx.dateOrder());
            if (received.isEmpty()) {
                issues.add(RowIssue.warning(line, PurchaseField.RECEIVED_DATE,
                        "Could not read the received date \"" + rawReceived + "\" — treated as not yet received"));
            } else if (orderDate.isPresent() && received.get().isBefore(orderDate.get())) {
                issues.add(RowIssue.warning(line, PurchaseField.RECEIVED_DATE,
                        "Received date is before the order date — treated as not yet received"));
            } else if (ctx.today() != null && received.get().isAfter(ctx.today())) {
                issues.add(RowIssue.warning(line, PurchaseField.RECEIVED_DATE,
                        "Received date is in the future — treated as not yet received"));
            }
        }

        String rawQtyReceived = cell(row, m, PurchaseField.QTY_RECEIVED);
        if (!rawQtyReceived.isEmpty()) {
            Optional<BigDecimal> qtyReceived = ValueParsers.parseNumber(rawQtyReceived);
            if (qtyReceived.isEmpty()) {
                issues.add(RowIssue.warning(line, PurchaseField.QTY_RECEIVED,
                        "Quantity received is not a number — ignored"));
            } else if (qty.isPresent() && qtyReceived.get().compareTo(qty.get()) > 0) {
                issues.add(RowIssue.warning(line, PurchaseField.QTY_RECEIVED, "Received quantity "
                        + plain(qtyReceived.get()) + " is above the " + plain(qty.get()) + " ordered"));
            }
        }

        return issues;
    }

    private static Optional<BigDecimal> numberOrWarn(List<String> row, ColumnMapping m, ImportFieldSpec field,
            String label, int line, List<RowIssue> issues) {
        String raw = cell(row, m, field);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        Optional<BigDecimal> value = ValueParsers.parseNumber(raw);
        if (value.isEmpty()) {
            issues.add(RowIssue.warning(line, field, label + " \"" + raw + "\" is not a number — ignored"));
        }
        return value;
    }

    @Override
    protected PurchaseRow toRow(List<String> row, ColumnMapping m, ValidationContext ctx, int line) {
        LocalDate orderDate = ValueParsers.parseDate(cell(row, m, PurchaseField.ORDER_DATE), ctx.dateOrder())
                .orElse(null);
        BigDecimal qty = ValueParsers.parseNumber(cell(row, m, PurchaseField.QTY)).orElse(BigDecimal.ONE);
        int wholeQty = Math.max(1, qty.setScale(0, RoundingMode.HALF_UP).intValue());

        LocalDate promised = ValueParsers.parseDate(cell(row, m, PurchaseField.PROMISED_DATE), ctx.dateOrder())
                .filter(d -> orderDate == null || !d.isBefore(orderDate))
                .orElse(null);
        LocalDate received = ValueParsers.parseDate(cell(row, m, PurchaseField.RECEIVED_DATE), ctx.dateOrder())
                .filter(d -> orderDate == null || !d.isBefore(orderDate))
                .filter(d -> ctx.today() == null || !d.isAfter(ctx.today()))
                .orElse(null);
        Integer qtyReceived = ValueParsers.parseNumber(cell(row, m, PurchaseField.QTY_RECEIVED))
                .map(v -> Math.max(0, v.setScale(0, RoundingMode.HALF_UP).intValue()))
                .orElse(null);

        return new PurchaseRow(
                line,
                cell(row, m, PurchaseField.ITEM),
                cell(row, m, PurchaseField.DESCRIPTION),
                orderDate,
                cell(row, m, PurchaseField.SUPPLIER),
                cell(row, m, PurchaseField.SUPPLIER_COUNTRY),
                wholeQty,
                ValueParsers.parseNumber(cell(row, m, PurchaseField.UNIT_COST)).orElse(BigDecimal.ZERO),
                ValueParsers.parseNumber(cell(row, m, PurchaseField.FREIGHT)).orElse(null),
                ValueParsers.parseNumber(cell(row, m, PurchaseField.DUTY)).orElse(null),
                ValueParsers.parseNumber(cell(row, m, PurchaseField.LANDED_COST)).orElse(null),
                writtenCurrency(ctx),
                cell(row, m, PurchaseField.SHIP_TO),
                cell(row, m, PurchaseField.PO_NUMBER),
                promised,
                received,
                qtyReceived);
    }

    @Override
    protected void tally(PurchaseRow row, Tally tally) {
        Tally.add(tally.items, row.item());
        Tally.add(tally.suppliers, row.supplier());
        Tally.add(tally.branches, row.shipTo());
        tally.date(row.orderDate());
    }
}
