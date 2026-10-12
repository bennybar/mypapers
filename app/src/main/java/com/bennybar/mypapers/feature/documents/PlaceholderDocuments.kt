package com.bennybar.mypapers.feature.documents

/** Stand-in data until the database lands (step 2). */
data class PlaceholderDocument(
    val id: String,
    val vendor: String,
    val date: String,
    val amount: String,
    val type: String,
    val tags: List<String>,
)

val placeholderDocuments = listOf(
    PlaceholderDocument("1", "Shufersal", "08/10/2026", "₪342.90", "Receipt", listOf("Groceries")),
    PlaceholderDocument("2", "Wolt", "06/10/2026", "₪118.00", "Receipt", listOf("Food", "Delivery")),
    PlaceholderDocument("3", "Israel Electric Corp.", "01/10/2026", "₪612.40", "Invoice", listOf("Utilities")),
    PlaceholderDocument("4", "KSP", "27/09/2026", "₪1,249.00", "Warranty", listOf("Electronics")),
    PlaceholderDocument("5", "Paz", "22/09/2026", "₪285.10", "Receipt", listOf("Car", "Fuel")),
    PlaceholderDocument("6", "Amazon", "15/09/2026", "$64.99", "Invoice", listOf("Home")),
)
