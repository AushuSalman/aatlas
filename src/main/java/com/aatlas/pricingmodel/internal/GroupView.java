package com.aatlas.pricingmodel.internal;

import com.aatlas.history.PricingModel;
import io.swagger.v3.oas.annotations.media.Schema;

/** One section of the settings screen, in display order. */
@Schema(name = "PricingModelGroup")
record GroupView(String key, String label, String blurb) {

    static GroupView of(PricingModel.Group g) {
        return new GroupView(g.key(), g.label(), g.blurb());
    }
}
