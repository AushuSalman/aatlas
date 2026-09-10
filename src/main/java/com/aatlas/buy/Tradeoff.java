package com.aatlas.buy;

import java.util.List;

/** What choosing a different option than the recommendation would cost and buy. */
public record Tradeoff(String key, String title, List<String> plus, List<String> minus) {
}
