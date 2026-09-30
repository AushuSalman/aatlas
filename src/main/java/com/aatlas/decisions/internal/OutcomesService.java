package com.aatlas.decisions.internal;

import com.aatlas.decisions.DecisionOutcomes;
import java.util.Collection;
import org.springframework.stereotype.Service;

/** The controller's one door to outcomes: the list, and a manual measurement run. */
@Service
class OutcomesService {

    private final DecisionOutcomes outcomes;
    private final OutcomeMeasurer measurer;

    OutcomesService(DecisionOutcomes outcomes, OutcomeMeasurer measurer) {
        this.outcomes = outcomes;
        this.measurer = measurer;
    }

    Collection<DecisionOutcomes.Outcome> list() {
        return outcomes.byDeal().values();
    }

    OutcomeMeasurer.RunSummary measureNow() {
        return measurer.measureNow();
    }
}
