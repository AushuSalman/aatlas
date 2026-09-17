package com.aatlas.sell.internal.buy;

import com.aatlas.history.Suppliers;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Over {@code history.Suppliers}, the panel's non-custom rows - real names, real facts. */
@Repository
class BuySupplierGatewayImpl implements BuySupplierGateway {

    private final Suppliers suppliers;

    BuySupplierGatewayImpl(Suppliers suppliers) {
        this.suppliers = suppliers;
    }

    @Override
    public List<SupplierRef> seededPanel() {
        return suppliers.panel().stream()
                .filter(s -> !s.custom())
                .map(s -> new SupplierRef(s.supplierKey(), s.name(),
                        s.otifPct() == null ? 0 : s.otifPct().doubleValue(),
                        s.priceIndex() == null ? 0 : s.priceIndex().doubleValue()))
                .toList();
    }
}
