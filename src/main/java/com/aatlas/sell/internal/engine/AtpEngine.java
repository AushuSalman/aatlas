package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.PricingMath;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.PurchaseHistory.PoStats;
import com.aatlas.history.PurchaseHistory.SupplierPurchases;
import com.aatlas.history.PurchaseHistory.SupplierShare;
import com.aatlas.history.Suppliers;
import com.aatlas.history.Window;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.catalog.CatalogGateway.TopCustomer;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpAllocationDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpFromDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpLineDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpLotDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpOrderDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.CustomerProfileDto;
import com.aatlas.sell.internal.dto.SellDtos.SellIntelDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Available-to-promise: who gets the stock on hand.
 *
 * <p>Port of {@code allocateInventory} in {@code sell2.ts}, over real inputs: the lots come
 * from the item's actual purchase-order suppliers ({@code history.PurchaseHistory}), spend
 * share standing in for "how much of the shelf is theirs" since individual PO lines are not
 * exposed at this layer; the orders come from the real customers who bought this pair
 * ({@link CatalogGateway#topCustomersFor}), not a fixed trio of ids.
 */
@Component
public class AtpEngine {

    private final CatalogGateway catalog;
    private final PurchaseHistory purchases;
    private final Suppliers suppliers;
    private final Sell2Engine sell2;
    private final AatlasClock clock;

    public AtpEngine(CatalogGateway catalog, PurchaseHistory purchases, Suppliers suppliers, Sell2Engine sell2,
            AatlasClock clock) {
        this.catalog = catalog;
        this.purchases = purchases;
        this.suppliers = suppliers;
        this.sell2 = sell2;
        this.clock = clock;
    }

    private static final class Lot {
        final String supplierId;
        final String supplierName;
        final BigDecimal reliabilityPct;
        int units;
        int remaining;

        Lot(String supplierId, String supplierName, BigDecimal reliabilityPct, int units) {
            this.supplierId = supplierId;
            this.supplierName = supplierName;
            this.reliabilityPct = reliabilityPct;
            this.units = units;
            this.remaining = units;
        }
    }

    private BigDecimal reliabilityOf(String supplierKey, LocalDate today) {
        SupplierPurchases sp = purchases.supplier(supplierKey, today);
        PoStats w12 = sp.w12();
        if (w12 != null && w12.received() >= 5 && w12.otifPct() != null) {
            return w12.otifPct();
        }
        return suppliers.supplier(supplierKey).map(Suppliers.SupplierRef::otifPct).orElse(null);
    }

    private List<Lot> buildLots(String itemNumber, int available, LocalDate today) {
        Window w12 = Window.trailingMonths(today, 12);
        List<SupplierShare> shares = purchases.item(itemNumber, w12).suppliers();
        List<Lot> lots = new ArrayList<>();
        if (shares.isEmpty()) {
            lots.add(new Lot("unknown", "On hand — supplier unknown", null, available));
            return lots;
        }
        List<SupplierShare> top = shares.subList(0, Math.min(3, shares.size()));
        BigDecimal totalShare = top.stream().map(SupplierShare::sharePct).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int allocated = 0;
        for (int i = 0; i < top.size(); i++) {
            SupplierShare s = top.get(i);
            int units;
            if (i == top.size() - 1) {
                units = available - allocated;
            } else {
                double weight = totalShare.signum() > 0 && s.sharePct() != null
                        ? s.sharePct().doubleValue() / totalShare.doubleValue() : 1.0 / top.size();
                units = (int) Math.round(available * weight);
                allocated += units;
            }
            lots.add(new Lot(s.supplierKey(), s.name(), reliabilityOf(s.supplierKey(), today), Math.max(0, units)));
        }
        return lots;
    }

    public AtpAllocationDto allocate(SellIntelDto intel) {
        LocalDate today = clock.today();
        int available = intel.inventoryUnits() == null ? 0 : intel.inventoryUnits();
        int reserve = (int) Math.round(available * 0.1);

        List<Lot> lots = buildLots(intel.itemNumber(), available, today);
        int lotSum = lots.stream().mapToInt(l -> l.units).sum();
        if (!lots.isEmpty()) {
            lots.get(0).units += available - lotSum;
            lots.get(0).remaining = lots.get(0).units;
        }

        Lot reserveLot = lots.stream()
                .min(Comparator.comparingDouble(l -> l.reliabilityPct == null ? 0 : l.reliabilityPct.doubleValue()))
                .orElse(null);
        if (reserveLot != null) {
            reserveLot.remaining = Math.max(0, reserveLot.remaining - reserve);
        }

        Optional<com.aatlas.history.Catalogue.ProductRef> product = catalog.findProduct(intel.itemNumber());
        Optional<com.aatlas.history.Catalogue.StoreRef> store = (intel.storeId() == null) ? Optional.empty()
                : catalog.findStore(intel.storeId());
        List<TopCustomer> wanted = product.isEmpty() || store.isEmpty() ? List.of()
                : catalog.topCustomersFor(product.get().id(), store.get().id(), 3,
                        Window.trailingDays(today, 90).from(), today);

        double recommended = intel.recommended() == null ? 0 : intel.recommended().doubleValue();
        BigDecimal cost = intel.cost();
        List<AtpOrderDto> orders = new ArrayList<>();
        for (TopCustomer tc : wanted) {
            var c = tc.customer();
            CustomerProfileDto p = sell2.customerProfile(c);
            double price = recommended * (1 - c.agreedDiscountPct().doubleValue() / 100);
            int qty = tc.orders() > 0 ? (int) Math.max(1, Math.round(tc.units().doubleValue() / tc.orders())) : 1;
            orders.add(new AtpOrderDto(c.code(), c.name(), p.profile(), p.label(), p.slaDays(), qty,
                    PricingMath.marginPct(bd(price), cost)));
        }

        List<AtpOrderDto> byPriority = orders.stream()
                .sorted(Comparator.comparingInt(AtpOrderDto::slaDays)
                        .thenComparing(o -> o.marginPct() == null ? BigDecimal.ZERO : o.marginPct(),
                                Comparator.reverseOrder()))
                .toList();
        List<Lot> lotsByReliability = lots.stream()
                .sorted(Comparator.comparingDouble((Lot l) -> l.reliabilityPct == null ? -1 : l.reliabilityPct.doubleValue())
                        .reversed())
                .toList();

        List<AtpLineDto> lines = new ArrayList<>();
        for (AtpOrderDto o : byPriority) {
            int need = o.qty();
            List<AtpFromDto> from = new ArrayList<>();
            List<Lot> order = o.slaDays() >= 10 ? reversedCopy(lotsByReliability) : lotsByReliability;
            for (Lot lot : order) {
                if (need <= 0) {
                    break;
                }
                int take = Math.min(need, lot.remaining);
                if (take > 0) {
                    from.add(new AtpFromDto(lot.supplierName, take, lot.reliabilityPct));
                    lot.remaining -= take;
                    need -= take;
                }
            }
            int allocated = o.qty() - need;
            String note;
            if (need > 0) {
                note = "Short " + Fmt.groupInt(need) + " units: source them before promising.";
            } else if (o.slaDays() <= 2) {
                BigDecimal pct = from.isEmpty() ? null : from.get(0).reliabilityPct();
                note = "Filled from the most reliable stock (" + (pct != null ? Fmt.fixed(pct.doubleValue(), 0) + "%" : "unknown")
                        + " on-time supplier) to protect a " + o.slaDays() + "-day SLA.";
            } else if (o.slaDays() >= 10) {
                note = "Filled from the lower-cost, slower lots; the delivery window allows it.";
            } else {
                note = "Filled in full.";
            }
            lines.add(new AtpLineDto(o, allocated, from, need, note));
        }

        int demanded = orders.stream().mapToInt(AtpOrderDto::qty).sum();
        int unmet = lines.stream().mapToInt(AtpLineDto::shortUnits).sum();
        AtpLineDto tight = lines.stream().filter(l -> l.order().slaDays() <= 2).findFirst().orElse(null);

        String explanation = orders.isEmpty()
                ? "No customer demand on file for this pair."
                : Fmt.groupInt(available) + " units on hand from " + lots.size() + " supplier(s), "
                        + Fmt.groupInt(reserve) + " held in reserve. "
                        + (tight != null
                                ? tight.order().name() + " (" + tight.order().slaDays() + "-day SLA) is served first. "
                                : "")
                        + (unmet > 0
                                ? Fmt.groupInt(unmet) + " units cannot be promised from stock: the Buy screen has the "
                                        + "supplier answer."
                                : "Every order is covered.");

        List<AtpLotDto> lotDtos = lots.stream()
                .map(l -> new AtpLotDto(l.supplierId, l.supplierName, l.reliabilityPct, l.units, l.remaining))
                .toList();

        return new AtpAllocationDto(available, reserve, lotDtos, lines, demanded, unmet, explanation);
    }

    private static List<Lot> reversedCopy(List<Lot> list) {
        List<Lot> copy = new ArrayList<>(list);
        java.util.Collections.reverse(copy);
        return copy;
    }
}
