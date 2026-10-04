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
        String note,
        /** The four-week forecast's held-out miss and the baseline's, as shares of units; null when there was too little to score. */
        Double horizonError,
        Double horizonBaseline,
        /** The four-week forecast beat the recent average: the demand step may read it. */
        boolean forecastUsable,
        /** Weekly units expected over the next four weeks at the last price, and the actual weekly units over the last eight. */
        double horizonUnits,
        double trailingUnits,
        /** Scale on the forecast model's level for this pair (actual over predicted on its training rows); 0 on rows stored before it existed. */
        double calibration,
        /** The forecaster the four-week figures are for (a {@link Smoothers} key); null on rows stored before the contest, which were the forest's. */
        String forecastMethod,
        /** Why the forecast is or is not in use for the pair, in a sentence; null on rows stored before the contest. */
        String forecastNote,
        /**
         * Every forecaster's two back-tests and its forecast for the pair, by key: what lets the model chosen in
         * Settings change without retraining. Null on rows stored before the choice existed.
         */
        java.util.Map<String, DemandTrainer.MethodEval> methods) {
}
