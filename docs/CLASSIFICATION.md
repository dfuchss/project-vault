# Classification & re-classification strategy

How Project Vault assigns categories to transactions, how it learns from your corrections, and how
rules are scoped and corrected per vault (a vault = one "project").

## Tiers

1. **Tier 1 — keyword rules** (`:core:classification` `RuleEngine`). Deterministic, offline,
   explainable. Two sources of rules:
   - **SEED** rules ship with every vault (`SeedCatalog`: ~15 categories + common German merchants).
   - **USER** rules are learned from your corrections (higher priority — they win over SEED).
2. **Tier 2 — semantic embeddings** (`EmbeddingClassifier`). For transactions no rule matches, an
   embedding model scores the transaction text against category *prototypes* (zero-shot) and your
   past categorizations (few-shot). The nearest above a confidence threshold is offered as a
   **suggestion to confirm — it never auto-commits** (rules are reliable and commit; embeddings are a
   proposal). Runs only if an embedding model is available; otherwise it is skipped.
3. **Tier 3 — local LLM (Ollama)** — optional, future; for the genuinely ambiguous remainder.

## How a category is chosen

`Categorizer.classifyAccount` only ever touches **uncategorized** transactions and never overwrites
an existing category. **Rules commit** a category; **embeddings only suggest** one. A committed
category records **how** it was set (`txn.categorySource`):

| source | meaning |
|---|---|
| `MANUAL` | you set it explicitly — **sticky**, never changed by automatic classification |
| `USER_RULE` | committed by a rule you taught |
| `SEED_RULE` | committed by a built-in rule |

Within Tier 1, the winning rule is the highest **priority** (USER > SEED), then the keyword that
appears **earliest** in the text (merchant names lead the counterparty, so `REWE` beats a later
`MUELLER`), then the **longest** keyword (`Amazon Prime` beats `Prime`).

### Proposals (embeddings)

A Tier-2 guess is stored separately in `txn.suggestedCategoryId` — a **proposal**, not a committed
category. Proposals do **not** count in the dashboard and are shown as a dimmed "?" chip. In the
transaction inspector you **Accept** (commits it, and learns a USER rule like a manual correction) or
**Dismiss** it (leaves the transaction uncategorized). This keeps low-confidence guesses out of your
data until you confirm them — rules stay authoritative, embeddings only assist.

A credit-card statement's settlement debited from the giro (`…KREDITKARTENABRECHNUNG…`) is seeded as a
**transfer**, so paying the card isn't double-counted as spending (the card's own transactions are).

### Account-type defaults

Some account types are inherently one kind of flow, so they're categorized by **type** before the
keyword rules even run (committed, since it's reliable):

- **Tagesgeld / Festgeld**: every transaction defaults to **Umbuchung** (money moving between your own
  accounts), except interest (`Zins…`) which is **Einkommen**.
- **Depot**: no transactions at all — a Depotauszug is a dated **holdings snapshot**, so there's
  nothing to categorize. (Future depot-transaction import would map dividends → income, buys/sells →
  transfer/investment.)

## Re-classification: what happens when you correct a transaction

The app is deliberately conservative about how far a correction spreads — matching by merchant name
is coarse (one shop sells many things), so a single correction never mass-recategorizes by default:

- **Only this transaction** (the default, `Categorizer.applyToOne`): sets its category `MANUAL`
  (sticky) and touches nothing else — **no rule is learned**. So correcting, say, an Apple Care plan
  that was bought at an electronics store never re-labels that store's unrelated purchases.
- **Apply to all & remember** (explicit opt-in via `Categorizer.setCategory`): offered **only** when
  the correction would change *other* same-merchant transactions, and the app **asks first**. It then:
  1. Sets the transaction `MANUAL`.
  2. **Learns a USER rule**: derives a keyword from the counterparty (first alphanumeric token ≥ 3
     chars, e.g. `AMZN.Mktp.DE…` → `AMZN`), deletes any previous USER rule for that keyword, and adds a
     new USER rule (priority 100) → the corrected category. Future imports auto-apply it.
  3. **Propagates** to other same-merchant transactions — **except** any you set `MANUAL` yourself.
  Competing manual choices are always respected.

Because corrections become USER rules *and* few-shot examples, both tiers get better over time:
Tier 1 immediately (the new rule), Tier 2 gradually (more labeled examples).

## Scope: rules are per vault ("project")

Categories, rules, and labels all live in the vault's SQLite DB, so they travel **with the vault
file** and are **local to that project**. Copying a vault to another machine carries its learned
rules; two different vaults keep independent taxonomies and corrections. Seed rules are installed
once per vault on first open.

## Correcting rules (fixing a bad learned rule)

- **Re-correct a transaction** of that merchant — this replaces the USER rule for that keyword
  (delete-by-keyword + re-add), so a wrong learned rule is overwritten, and the change propagates.
- **Add your own category** from the transaction inspector ("＋ New category…") and assign it; the
  same learning applies.
- **Planned (see BACKLOG / task #10):** a rule editor to list, edit, and delete USER rules directly,
  plus "undo last categorization" — for cases where re-correcting a transaction isn't convenient
  (e.g. an over-broad keyword that grabbed unrelated transactions).

## Tuning & limitations

- Keyword derivation is a heuristic; an over-generic learned keyword can over-match. Mitigations:
  earliest-match ordering, and (planned) the rule editor to prune bad keywords.
- Tier 2 requires an embedding model. The classifier logic is complete and tested; provisioning the
  model (a small multilingual ONNX bundled with the app, or downloaded on first use) is the remaining
  step for Tier 2 to activate. Until then the app runs Tier 1 only — fully functional.
- Confidence threshold (`EmbeddingClassifier.minSimilarity`) trades precision vs. coverage; start
  conservative so embeddings only fill high-confidence gaps and leave the rest for you.
