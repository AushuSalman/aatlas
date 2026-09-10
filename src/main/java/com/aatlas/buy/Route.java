package com.aatlas.buy;

/** How a supplier can get the goods here: standard lead time, or expedited at a surcharge. */
public record Route(String mode, String label, int days, double varianceDays, double unitCost, double surchargePct) {
}
