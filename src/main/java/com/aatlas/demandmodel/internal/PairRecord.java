package com.aatlas.demandmodel.internal;

/**
 * One item-branch pair as stored beside the model: the context a forecast starts from (the
 * pair's last week, price, cost, competitor median, lags) and how the training run scored it.
 * Stored as jsonb keyed by {@code item@store}; dates are ISO strings so the column needs no
 * date module.
 */
record PairRecord(
        String item,
        String store,
        String category,
        String lastWeek,
        double lastPrice,
        double cost,
        double compMedian,
        double[] lags,
        double medianPrice,
        int weeks,
        double units,
        double holdoutUnits,
        /** Error over the held-out weeks as a share of units; NaN is stored as null when there was too little to score. */
        Double errorModel,
        Double errorBaseline,
        boolean beats,
        boolean usable,
        double elasticity,
        Double regressionElasticity,
        String regressionBasis,
        double forecastNext,
        double settledNext,
        String note) {
}
