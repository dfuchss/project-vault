package org.fuchss.projectvault.model

/** Top-level nature of a category, used to drive analytics and forecasting. */
enum class CategoryKind {
    INCOME,
    EXPENSE,
    TRANSFER, // movements between the user's own accounts (net-zero for analysis)
}
