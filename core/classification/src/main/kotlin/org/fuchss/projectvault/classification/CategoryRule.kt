package org.fuchss.projectvault.classification

import org.fuchss.projectvault.model.CategoryKind

/** Where a categorization rule came from. USER rules (learned from corrections) beat SEED rules. */
enum class RuleSource { SEED, USER }

/** A keyword → category rule. If [keyword] appears in a transaction's text, it maps to [categoryId]. */
data class CategoryRule(
    val keyword: String,
    val categoryId: String,
    val priority: Int,
    val source: RuleSource,
)

/** A built-in category shipped with every new vault. Stable [id]s so seed rules can reference them. */
data class SeedCategory(
    val id: String,
    val name: String,
    val kind: CategoryKind,
    val color: String,
)
