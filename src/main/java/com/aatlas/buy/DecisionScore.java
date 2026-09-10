package com.aatlas.buy;

import java.util.List;

/** The recommendation's score, broken into parts a buyer would recognise. */
public record DecisionScore(int total, List<Part> parts, String risk) {

    public record Part(String label, int score) {
    }
}
