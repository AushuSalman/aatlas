package com.aatlas.prices.internal;

/** Rows of a write removed, and rows kept because something newer superseded them. */
record UndoResponse(int deleted, int kept) {
}
