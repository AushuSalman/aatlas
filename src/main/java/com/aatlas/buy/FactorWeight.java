package com.aatlas.buy;

/** How much one input moved this cost, as a percent of total movement (sums to 100). */
public record FactorWeight(String label, double percent, String direction) {
}
