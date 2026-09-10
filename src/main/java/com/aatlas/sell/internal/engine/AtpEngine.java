package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.PricingEngine.marginPercent;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.seed.Seeded;
import com.aatlas.sell.internal.buy.BuySupplierGateway;
import com.aatlas.sell.internal.buy.SupplierRef;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.catalog.CatalogRefs.CustomerRef;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpAllocationDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpFromDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpLineDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpLotDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpOrderDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.CustomerProfileDto;
import com.aatlas.sell.internal.dto.SellDtos.SellIntelDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Available-to-promise: who gets the stock on hand.
 *
 * <p>Port of {@code allocateInventory} in {@code sell2.ts}, EXCEPT for where the stock lots
 * come from. The TypeScript reads {@code getBuyIntel(...).incumbent} / {@code .suppliers} -
 * a landed cost per supplier across freight, duty and commercial terms that Track Buy owns
 * (see {@link BuySupplierGateway}'s own doc comment). This engine still needs a panel and
 * an incumbent to allocate against, so it works out the incumbent the same way {@code
 * currentSupplierFor(item)} does (rank by price index, seed-pick a half of the panel) and
 * approximates "next best" by on-time percent rather than the full effective-cost sort.
 *
 * <p>The order-allocation logic itself (SLA priority, reliable-stock-to-tight-SLA, the
 * reserve taken from the least reliable lot) is ported exactly; only the three input lots
 * are a stand-in. TODO(merge): once Track Buy's engine is in this tree, replace {@link
 * #lotSuppliers} with real {@code getBuyIntel(...).incumbent}/{@code .suppliers}.
 */
@Component
public class AtpEngine {

    private static final double[] SHARES = {0.5, 0.3, 0.2};

    private final CatalogGateway catalog;
    private final BuySupplierGateway suppliers;
    private final Sell2Engine sell2;

    public AtpEngine(CatalogGateway catalog, BuySupplierGateway suppliers, Sell2Engine sell2) {
        this.catalog = catalog;
        this.suppliers = suppliers;
        this.sell2 = sell2;
    }

    /** {@code currentSupplierFor(item)}: rank by price index, seed-pick a half of the panel. */
    private SupplierRef incumbentFor(String itemNumber, List<SupplierRef> panel) {
        List<SupplierRef> ranked = panel.stream()
                .sorted(Comparator.comparingDouble(SupplierRef::priceIndex))
                .toList();
        int half = (int) Math.ceil(ranked.size() / 2.0);
        List<SupplierRef> pool = Seeded.rand(itemNumber, "inc-tier") > 0.3
                ? ranked.subList(0, half) : ranked.subList(half, ranked.size());
        return Seeded.pick(itemNumber, "current-sup", pool);
    }

    private List<SupplierRef> lotSuppliers(String itemNumber) {
        List<SupplierRef> panel = suppliers.seededPanel();
        SupplierRef incumbent = incumbentFor(itemNumber, panel);
        List<SupplierRef> others = panel.stream()
                .filter(s -> !s.supplierId().equals(incumbent.supplierId()))
                .sorted(Comparator.comparingDouble(SupplierRef::otifPct).reversed())
                .limit(2)
                .toList();
        List<SupplierRef> out = new ArrayList<>();
        out.add(incumbent);
        out.addAll(others);
        return out;
    }

    private static final class Lot {
        final String supplierId;
        final String supplierName;
        final double reliabilityPct;
        int units;
        int remaining;

        Lot(String supplierId, String supplierName, double reliabilityPct, int units) {
            this.supplierId = supplierId;
            this.supplierName = supplierName;
            this.reliabilityPct = reliabilityPct;
            this.units = units;
            this.remaining = units;
        }
    }

    public AtpAllocationDto allocate(SellIntelDto intel) {
        int available = intel.inventoryUnits();
        int reserve = (int) Math.round(available * 0.1);

        List<SupplierRef> lotSuppliers = lotSuppliers(intel.itemNumber());
        List<Lot> lots = new ArrayList<>();
        for (int i = 0; i < lotSuppliers.size(); i++) {
            SupplierRef s = lotSuppliers.get(i);
            int units;
            if (i == lotSuppliers.size() - 1) {
                double sumFromI = 0;
                for (int j = i; j < SHARES.length; j++) {
                    sumFromI += SHARES[j];
                }
                units = available - (int) Math.round(available * (1 - sumFromI));
            } else {
                units = (int) Math.round(available * SHARES[i]);
            }
            lots.add(new Lot(s.supplierId(), s.name(), s.otifPct(), units));
        }
        int lotSum = lots.stream().mapToInt(l -> l.units).sum();
        lots.get(0).units += available - lotSum;
        lots.get(0).remaining = lots.get(0).units;

        Lot reserveLot = lots.stream().min(Comparator.comparingDouble(l -> l.reliabilityPct)).orElseThrow();
        reserveLot.remaining = Math.max(0, reserveLot.remaining - reserve);

        CustomerRef c3 = catalog.findCustomer("c-3").orElse(null);
        CustomerRef c2 = catalog.findCustomer("c-2").orElse(null);
        CustomerRef c5 = catalog.findCustomer("c-5").orElse(null);
        List<CustomerRef> wanted = new ArrayList<>();
        for (CustomerRef c : List.of(c3, c2, c5)) {
            if (c != null) {
                wanted.add(c);
            }
        }
        int usable = available - reserve;
        int baseTotal = wanted.stream().mapToInt(CustomerRef::typicalQty).sum();
        if (baseTotal == 0) {
            baseTotal = 1;
        }
        double scale = (usable * 1.12) / baseTotal;

        double recommended = intel.recommended().doubleValue();
        double cost = intel.cost().doubleValue();
        List<AtpOrderDto> orders = new ArrayList<>();
        for (CustomerRef c : wanted) {
            CustomerProfileDto p = sell2.customerProfile(c);
            double price = recommended * (1 - c.agreedDiscountPct().doubleValue() / 100);
            int qty = (int) Math.max(1, Math.round(c.typicalQty() * scale));
            orders.add(new AtpOrderDto(c.code(), c.name(), p.profile(), p.label(), p.slaDays(), qty,
                    bd(marginPercent(price, cost))));
        }

        List<AtpOrderDto> byPriority = orders.stream()
                .sorted(Comparator.comparingInt(AtpOrderDto::slaDays)
                        .thenComparing(Comparator.comparing(AtpOrderDto::marginPct).reversed()))
                .toList();
        List<Lot> lotsByReliability = lots.stream()
                .sorted(Comparator.comparingDouble((Lot l) -> l.reliabilityPct).reversed())
                .toList();

        List<AtpLineDto> lines = new ArrayList<>();
        for (AtpOrderDto o : byPriority) {
            int need = o.qty();
            List<AtpFromDto> from = new ArrayList<>();
            List<Lot> order = o.slaDays() >= 10
                    ? reversedCopy(lotsByReliability) : lotsByReliability;
            for (Lot lot : order) {
                if (need <= 0) {
                    break;
                }
                int take = Math.min(need, lot.remaining);
                if (take > 0) {
                    from.add(new AtpFromDto(lot.supplierName, take, bd(lot.reliabilityPct)));
                    lot.remaining -= take;
                    need -= take;
                }
            }
            int allocated = o.qty() - need;
            String note;
            if (need > 0) {
                note = "Short " + Fmt.groupInt(need) + " units: source them before promising.";
            } else if (o.slaDays() <= 2) {
                double pct = from.isEmpty() ? 0 : from.get(0).reliabilityPct().doubleValue();
                note = "Filled from the most reliable stock (" + Fmt.fixed(pct, 0) + "% on-time supplier) to "
                        + "protect a " + o.slaDays() + "-day SLA.";
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

        String explanation = Fmt.groupInt(available) + " units on hand from " + lots.size() + " suppliers, "
                + Fmt.groupInt(reserve) + " held in reserve. "
                + (tight != null
                        ? tight.order().name() + " (" + tight.order().slaDays() + "-day SLA) is served first from "
                                + "the " + (tight.from().isEmpty() ? "—"
                                        : Fmt.fixed(tight.from().get(0).reliabilityPct().doubleValue(), 0))
                                + "% on-time lot. "
                        : "")
                + (unmet > 0
                        ? Fmt.groupInt(unmet) + " units cannot be promised from stock: the Buy screen has the "
                                + "supplier answer."
                        : "Every order is covered.");

        List<AtpLotDto> lotDtos = lots.stream()
                .map(l -> new AtpLotDto(l.supplierId, l.supplierName, bd(l.reliabilityPct), l.units, l.remaining))
                .toList();

        return new AtpAllocationDto(available, reserve, lotDtos, lines, demanded, unmet, explanation);
    }

    private static List<Lot> reversedCopy(List<Lot> list) {
        List<Lot> copy = new ArrayList<>(list);
        java.util.Collections.reverse(copy);
        return copy;
    }
}
