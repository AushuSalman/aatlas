package com.aatlas.buy;

/**
 * Public reader for {@link ProcurementPlan}. See {@link BuyIntelReader} for why this lives
 * at the package root: so the {@code bulk} module's stand-in for it can be retargeted here
 * at merge time.
 */
public interface ProcurementPlanReader {

    /** @param custom the weights to use when {@code priority} is {@code "custom"}; ignored otherwise */
    ProcurementPlan procurementPlan(BuyIntel intel, int requiredDays, String priority, Weights custom);
}
