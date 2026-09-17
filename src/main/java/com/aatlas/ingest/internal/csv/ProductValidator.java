package com.aatlas.ingest.internal.csv;

import com.aatlas.ingest.internal.csv.ImportReport.RowIssue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Validates a product master.
 *
 * <p>Almost everything here is a warning: a product row is a set of independent facts (a
 * category, a price, a stock count) and one unreadable figure is no reason to lose the
 * others. The only errors are a blank item number and a new item with no description,
 * because a product row with neither cannot be created.
 */
@Component
public class ProductValidator extends AbstractValidator<ProductRow> {

    /** Spellings of a commodity that mean one the platform tracks. */
    private static final Map<String, String> COMMODITY_ALIASES = Map.ofEntries(
            Map.entry("cpvc", "pvc"), Map.entry("plastic", "pvc"), Map.entry("polymer", "pvc"),
            Map.entry("stainless", "steel"),
            Map.entry("castiron", "iron"), Map.entry("ductile", "iron"),
            Map.entry("hvac", "equipment"), Map.entry("equip", "equipment"));

    @Override
    public ImportKind kind() {
        return ImportKind.PRODUCTS;
    }

    @Override
    protected List<RowIssue> checkRow(List<String> row, ColumnMapping m, ValidationContext ctx, int line,
            FileState state) {
        List<RowIssue> issues = new ArrayList<>();

        String item = cell(row, m, ProductField.ITEM);
        String itemKey = item.toLowerCase(Locale.ROOT);
        if (item.isEmpty()) {
            issues.add(RowIssue.error(line, ProductField.ITEM, "Item number is blank"));
        }

        String description = cell(row, m, ProductField.DESCRIPTION);
        boolean known = ctx.knowsItem(item) || state.describedItems.contains(itemKey);
        if (description.isEmpty() && !item.isEmpty()) {
            if (known) {
                issues.add(RowIssue.warning(line, ProductField.DESCRIPTION,
                        "Description is blank — existing description kept"));
            } else {
                issues.add(RowIssue.error(line, ProductField.DESCRIPTION, "Description is blank for a new item"));
            }
        }

        String commodity = cell(row, m, ProductField.COMMODITY);
        if (!commodity.isEmpty() && resolveCommodity(commodity, ctx) == null) {
            issues.add(RowIssue.warning(line, ProductField.COMMODITY,
                    "Commodity \"" + commodity + "\" is not tracked — set to none"));
        }

        Optional<BigDecimal> listPrice = money(row, m, ProductField.LIST_PRICE, "List price", line, issues);
        if (listPrice.isPresent() && listPrice.get().signum() == 0) {
            issues.add(RowIssue.warning(line, ProductField.LIST_PRICE, "List price is zero"));
        }
        Optional<BigDecimal> unitCost = money(row, m, ProductField.UNIT_COST, "Cost", line, issues);
        if (unitCost.isPresent() && listPrice.isPresent() && listPrice.get().signum() > 0
                && unitCost.get().compareTo(listPrice.get()) > 0) {
            issues.add(RowIssue.warning(line, ProductField.UNIT_COST,
                    "Cost " + plain(unitCost.get()) + " is above list price " + plain(listPrice.get())));
        }

        Optional<BigDecimal> onHand = Optional.empty();
        String rawOnHand = cell(row, m, ProductField.ON_HAND);
        if (!rawOnHand.isEmpty()) {
            onHand = ValueParsers.parseNumber(rawOnHand);
            if (onHand.isEmpty()) {
                issues.add(RowIssue.warning(line, ProductField.ON_HAND, "On hand is not a number — skipped"));
            } else if (onHand.get().signum() < 0) {
                issues.add(RowIssue.warning(line, ProductField.ON_HAND, "On hand is negative — skipped"));
                onHand = Optional.empty();
            }
        }

        String supplier = cell(row, m, ProductField.SUPPLIER);
        String rawSupplierCost = cell(row, m, ProductField.SUPPLIER_COST);
        if (!rawSupplierCost.isEmpty()) {
            if (ValueParsers.parseNumber(rawSupplierCost).isEmpty()) {
                issues.add(RowIssue.warning(line, ProductField.SUPPLIER_COST,
                        "Supplier cost is not a number — ignored"));
            } else if (supplier.isEmpty()) {
                issues.add(RowIssue.warning(line, ProductField.SUPPLIER_COST,
                        "Supplier cost given without a supplier — ignored"));
            }
        }

        String rawLead = cell(row, m, ProductField.LEAD_TIME);
        if (!rawLead.isEmpty()) {
            Optional<Integer> lead = ValueParsers.parseWholeNumber(rawLead);
            if (lead.isEmpty() || lead.get() < 0 || lead.get() > 365) {
                issues.add(RowIssue.warning(line, ProductField.LEAD_TIME, "Lead time \"" + rawLead
                        + "\" is not a whole number of days between 0 and 365 — ignored"));
            }
        }

        String branch = cell(row, m, ProductField.BRANCH);
        if (!branch.isEmpty() && onHand.isEmpty() && listPrice.isEmpty() && unitCost.isEmpty()) {
            issues.add(RowIssue.warning(line, ProductField.BRANCH,
                    "Branch given with no stock, price or cost — nothing to record for it"));
        }

        if (!item.isEmpty()) {
            String pair = itemKey + "|" + branch.toLowerCase(Locale.ROOT);
            if (!state.seenPairs.add(pair)) {
                issues.add(RowIssue.warning(line, ProductField.ITEM,
                        "Duplicate row for this item and branch — the last one wins"));
            }
            if (!description.isEmpty()) {
                state.describedItems.add(itemKey);
            }
        }
        return issues;
    }

    /** A price-like cell: blank is absent, unreadable or negative is skipped with a warning. */
    private static Optional<BigDecimal> money(List<String> row, ColumnMapping m, ImportFieldSpec field,
            String label, int line, List<RowIssue> issues) {
        String raw = cell(row, m, field);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        Optional<BigDecimal> value = ValueParsers.parseNumber(raw);
        if (value.isEmpty()) {
            issues.add(RowIssue.warning(line, field, label + " \"" + raw + "\" is not a number — skipped"));
            return Optional.empty();
        }
        if (value.get().signum() < 0) {
            issues.add(RowIssue.warning(line, field, label + " is negative — skipped"));
            return Optional.empty();
        }
        return value;
    }

    /** The tracked commodity key for a cell, or null when the platform does not track it. */
    static String resolveCommodity(String raw, ValidationContext ctx) {
        String key = raw.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        String aliased = COMMODITY_ALIASES.getOrDefault(key, key);
        return ctx.commodities().contains(aliased) ? aliased : null;
    }

    @Override
    protected ProductRow toRow(List<String> row, ColumnMapping m, ValidationContext ctx, int line) {
        String rawCommodity = cell(row, m, ProductField.COMMODITY);
        String commodity = rawCommodity.isEmpty() ? null
                : Optional.ofNullable(resolveCommodity(rawCommodity, ctx)).orElse("none");

        String supplier = cell(row, m, ProductField.SUPPLIER);
        BigDecimal supplierCost = supplier.isEmpty() ? null
                : ValueParsers.parseNumber(cell(row, m, ProductField.SUPPLIER_COST)).orElse(null);
        Integer leadTime = ValueParsers.parseWholeNumber(cell(row, m, ProductField.LEAD_TIME))
                .filter(v -> v >= 0 && v <= 365)
                .orElse(null);

        return new ProductRow(
                line,
                cell(row, m, ProductField.ITEM),
                cell(row, m, ProductField.DESCRIPTION),
                cell(row, m, ProductField.CATEGORY),
                cell(row, m, ProductField.SUBCATEGORY),
                commodity,
                nonNegative(cell(row, m, ProductField.LIST_PRICE)),
                nonNegative(cell(row, m, ProductField.UNIT_COST)),
                nonNegative(cell(row, m, ProductField.ON_HAND)),
                cell(row, m, ProductField.BRANCH),
                supplier,
                supplierCost,
                leadTime,
                cell(row, m, ProductField.UOM));
    }

    private static BigDecimal nonNegative(String raw) {
        return ValueParsers.parseNumber(raw).filter(v -> v.signum() >= 0).orElse(null);
    }

    @Override
    protected void tally(ProductRow row, Tally tally) {
        Tally.add(tally.items, row.item());
        Tally.add(tally.branches, row.branch());
        Tally.add(tally.suppliers, row.supplier());
    }
}
