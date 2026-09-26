# Classification & re-classification strategy

How Project Vault assigns categories to transactions, how it learns from your corrections, and how
rules are scoped and corrected per vault (a vault = one "project").

## Tiers

1. **Tier 1 — keyword rules** (`:core:classification` `RuleEngine`). Deterministic, offline,
   explainable. Text is diacritics-folded before matching (`TextNormalizer`: `ö→oe`, `é→e`, …), so
   umlaut spelling never affects a match. Two sources of rules:
   - **SEED** rules ship with every vault (`SeedCatalog`: ~19 categories + common German merchants).
   - **USER** rules are learned from your corrections (higher priority — they win over SEED).
2. **Tier 2 — suggestions from two complementary models.** For transactions no rule matches, Tier 2
   *proposes* a category — a **suggestion to confirm, it never auto-commits** (rules are reliable and
   commit; Tier 2 only proposes):
   - **Statistical** (`StatisticalClassifier`) — **always on**. A lightweight TF-IDF + multinomial
     Naive Bayes model trained **on-device** from your labeled transactions plus the seed keywords, so
     it learns the vocabulary of *your* merchants. No bundled model, no download; retrained in-memory
     on each pass.
   - **Semantic embeddings** (`EmbeddingClassifier`) — only when an embedding model is provisioned.
     Scores the text against category *prototypes* (zero-shot) and your past categorizations (few-shot)
     by cosine similarity.
   When both run they are **merged** (`Categorizer.mergeSuggestion`): if they agree that category wins;
   if they disagree the higher-confidence proposal wins; if only one fires it is used. The embedding
   model is bundled and loads on first classify, so both normally run; a build without it falls back to
   the statistical model alone. On the `ClassifierComparisonTest` benchmark embeddings give much higher
   coverage at near-equal precision, so the merge usually resolves to the embedding proposal — the
   statistical model is the always-on fallback and a high-precision cross-check.
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

Keywords match **whole words**: a keyword glued to a longer word is not a match. Punctuation still
counts as a boundary, so `REWE.Markt/…`, `AMZN.Mktp.DE` and `Booking.com` match as before, while
`NETTO` no longer matches *Nettobezüge* (which used to file a salary under Lebensmittel), `MIETE`
no longer matches *Mieteinnahme*, and `UBER` no longer matches *Überweisung*.

"Whole word" tolerates a **short** continuation — at most two trailing letters, optionally followed
by digits. Requiring a hard boundary on both sides turned out to be too strict against real
statement text, losing `RESTAURANT` on *Restaurante/Restaurantes*, `MCDONALD` on the plural and on a
glued branch number (*McDonalds450*), and `HOTEL` on *Hotels* — about 1% of a real vault. What
separates those from the collisions the boundary exists to stop is length: a plural or inflection
adds a letter or two, whereas *Nettobezüge* and *Mieteinnahme* glue on a whole further word.

### The amount sign constrains every tier

Money coming in can only be **income** or a **transfer** between your own accounts; money going out
can only be an **expense** or such a transfer. Filing a credit under an expense category is not a
weak guess but a category error, so the sign constrains what the classifier may even consider —
`allowedKindsForAmount` in `:core:model` is the one definition, shared by the manual picker and by
every automatic tier (`Categorizer.admissibleFor`).

It filters **candidates**, not the winner. A rejected front-runner therefore falls through to the
next admissible match rather than leaving the transaction unclassified: an incoming
`AMZN Mktp DE … Rückerstattung` resolves to the refund rule (a transfer) instead of to the Amazon
shopping rule that happens to appear earlier in the text. The same text as a debit is still an
ordinary Amazon purchase.

The constraint also bounds how far a correction spreads: teaching "Amazon → Shopping" from a
purchase never relabels that merchant's refunds, and they aren't counted in the "apply to all N"
prompt either. And because a credit can only be income or a transfer, and the catalog holds exactly
three such categories (Gehalt, Weitere Einkünfte, Umbuchung & Sparen), classifying incoming money is
a three-way decision rather than a nineteen-way one.

When a category is disabled its entries are reassigned **by sign** — debits to Sonstiges, credits to
Weitere Einkünfte — so a bulk reassignment cannot be what puts an income into an expense bucket.

### Proposals (Tier 2)

**Sonstiges is never proposed.** It is the only category with no keywords, so its only embedding
prototype is the vector of the word *"Sonstiges"* — which sits nearest to exactly the texts that say
nothing (a private person's name, a bare "Zahlungseingang"), making it the model's default answer
whenever it has no idea. "I don't know" is better expressed by proposing nothing and leaving the
transaction in the uncategorized filter, so Sonstiges is dropped from both models' inputs. It
remains fully available to rules and to you.

A Tier-2 guess (statistical and/or embedding, merged) is stored separately in `txn.suggestedCategoryId`
— a **proposal**, not a committed category. Proposals do **not** count in the dashboard and are shown
as a dimmed "?" chip. In the transaction inspector you **Accept** (commits it, and learns a USER rule
like a manual correction) or **Dismiss** it (leaves the transaction uncategorized). This keeps
low-confidence guesses out of your data until you confirm them — rules stay authoritative, Tier 2 only
assists.

A credit-card statement's settlement debited from the giro (`…KREDITKARTENABRECHNUNG…`) is seeded as a
**transfer**, so paying the card isn't double-counted as spending (the card's own transactions are).

### Account-type defaults

Some account types are inherently one kind of flow, so they're categorized by **type** before the
keyword rules even run (committed, since it's reliable):

- **Tagesgeld / Festgeld**: every transaction defaults to **Umbuchung** (money moving between your own
  accounts), except interest (`Zins…`) **that is actually credited**, which is **Einkommen** — the
  same statement line also appears negative as withheld Kapitalertragsteuer, and that is not income.
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
  the correction would change *other* matching transactions, and the app **asks first**. The dialog
  shows the keyword it would learn **as an editable field** with a live count of what it would reach —
  the derived keyword is a guess (the merchant when the statement names one, a word from the purpose
  otherwise), and only the user knows whether they mean "everything from this shop", one branch of it,
  or "anything mentioning Versicherung". It then:
  1. Sets the transaction `MANUAL`.
  2. **Learns a USER rule**: derives a keyword from the counterparty — the first alphanumeric token of
     at least **4** characters, e.g. `AMZN.Mktp.DE…` → `AMZN` — falling back to a 3-character token
     only when the counterparty offers nothing longer, and to the *purpose* when it offers nothing at
     all (Lastschrift/Dauerauftrag rows and CSV exports often leave the payee blank). The 4-character
     preference is there because a 3-character first token is usually a bank or product prefix
     (`DKB …`, `ING …`) rather than the merchant, and such a rule matches almost everything: on a real
     vault three learned 3-character rules alone had mis-committed 5% of all transactions. The keyword
     is **editable in the confirmation dialog**, with a live count of how many transactions it would
     reach, so the blast radius is visible — and chosen — before anything is written. Any previous
     USER rule for that keyword is replaced, and the new one (priority 100) applies to future imports. When the
     statement carries **no counterparty** — Lastschrift/Dauerauftrag rows, and card/CSV exports that
     leave the payee blank — the keyword is taken from the **purpose** instead, skipping purely numeric
     tokens (a booking reference identifies one transaction, never a merchant). Matching itself always
     reads counterparty *and* purpose, in every tier.
  3. **Propagates** to every other transaction that keyword matches — **except** any you set `MANUAL`
     yourself.
  Competing manual choices are always respected.

Because corrections become USER rules *and* few-shot examples, both tiers get better over time:
Tier 1 immediately (the new rule), Tier 2 gradually (more labeled examples).

## Seeing why something was classified

**Classification → Explain** answers "why did this row get that category?" for any transaction in the
vault (or for free text plus an amount, since the sign changes the answer). It walks the decision in
the order the classifier runs it:

1. **What the amount allows** — the admissible kinds, and the categories ruled out by the sign.
2. **Tier 1** — every rule whose keyword occurs in the text, ranked exactly as the engine ranks them,
   each marked `wins` / `out-ranked` / `wrong sign` / `category off`. The rules that matched and
   *lost* are usually the real answer.
3. **Tier 2 statistical** — the TF-IDF token evidence, sorted by **lift**: how much each token pushed
   the winner *over the runner-up*. A token can score high while deciding nothing, because every
   category likes it equally (`GMBH`, `SEPA`, `DE`); lift is what separates those. Tokens the model
   has never seen are listed too — "none of these words are known" is the honest explanation for a
   missing suggestion.
4. **Tier 2 embeddings** — the per-category cosines, the winner, the runner-up and the margin between
   them, and whether it cleared the bar.
5. **The merge** — each model's proposal, how far each cleared its own threshold, which won, and what
   was finally committed or proposed.

Each tier says plainly when it did not run (switched off, model not provisioned, untrained), and the
explanation is computed from the same code paths the classifier uses, so it cannot drift from the
decision it describes.

## Re-running classification

`Categorizer.classifyAccount` only ever touches uncategorized rows, so improving a rule does not by
itself reach transactions it already classified. **Classification → Actions** offers an explicit
re-run at two scopes, both of which show the affected count first and neither of which ever touches a
`MANUAL` transaction:

- **Uncategorized only** — exactly what an import does.
- **Re-apply rules to everything** — drops every *automatic* category first, then re-runs, so edited
  rules and catalog improvements reach existing data.

The clear-and-recommit runs in a **single database transaction**, with the slow Tier-2 pass outside
it. This matters: the clear is irreversible for any row whose category no rule explains any more (an
orphan from a rule since edited or deleted), so a failure halfway through must roll back rather than
leave the vault stripped.

Note the second scope legitimately *reduces* coverage: rows whose only matching rule was an
over-broad learned keyword, or whose candidates are all ruled out by the amount sign, come back
uncategorized. That is the intended trade — uncategorized beats miscategorized — but it means the
review inbox will have work in it afterwards.

## Bulk assignment

Selecting several transactions in the review inbox or an account list and assigning a category sets
each one `MANUAL` and **learns nothing**: an arbitrary selection has no merchant to generalize from,
so deriving a keyword rule from it would be a guess. The picker offers only categories admissible for
**every** selected row's sign. The write is undoable, restoring the previous category, its source,
and any suggestion that was cleared.

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
- **The rule editor** (Classification → Rules) lists every rule — built-in and learned — with its
  keyword, target category, priority and **how many of your transactions the keyword currently occurs
  in**, searchable by keyword, category and source. Rules can be added, edited and deleted there, and
  a "Test a rule" box shows which rule would win for a sample text and which category it would commit,
  so the priority / earliest-match / longest-keyword ordering is inspectable rather than folklore.

  Deleting a **built-in** keyword is remembered, not just applied: seeding is a *reconcile* that
  re-installs every missing catalog pair on each open, so a plain delete would come back. A removed
  pair is recorded in `ruleSuppression`, which `ensureSeeded` honours; removed built-ins are listed in
  the editor with a **Restore**. Editing a built-in rule **takes it over** — the original catalog pair
  is suppressed and the row becomes a USER rule, which also keeps the seed prune from deleting it.

## Re-running classification

`classifyAccount` only ever fills in blanks, so improving a rule never reaches transactions that
already have a category. **Classification → Actions** adds an explicit re-run with two scopes, each
showing the number of affected transactions before it runs:

- **Uncategorized only** — exactly what an import does.
- **Re-apply rules to everything** — drops every *automatically* set category first, then classifies
  again, so an edited or deleted rule finally reaches already-labelled transactions. Anything no rule
  matches any more goes back to uncategorized (and may pick up a Tier-2 suggestion).

Neither scope touches a transaction whose `categorySource` is `MANUAL`. That is the promise the
source column exists for, and it holds in both directions: a manual category is never cleared and
never re-labelled.

## Tuning & limitations

- Keyword derivation is a heuristic; an over-generic learned keyword can over-match. Mitigations:
  earliest-match ordering, whole-word matching, the editable keyword in the apply-to-similar dialog
  (with a live count of what it would reach), and the rule editor in **Classification → Rules**.
- The **statistical** Tier-2 model needs enough labeled data to generalize — a brand-new vault leans on
  the seed keywords until you've corrected a few transactions, after which it learns your merchants.
- The **embedding** Tier-2 model (a small multilingual ONNX, ~118 MB) is bundled and loads on first
  classify, so the first import is slower; a build without the model falls back to the statistical
  model alone — still fully functional.
- Confidence thresholds trade precision vs. coverage: `StatisticalClassifier.minConfidence` (a
  margin-based score, default 0.65) and `EmbeddingClassifier.minMargin` (default 0.03).
  Both are tunable in **Classification → Models**, along with an on/off switch per suggester; the
  settings live in the vault's `vaultSetting` table (see `ClassifierSettings`), so they travel with
  the vault file like rules and labels do, and an untouched vault keeps the defaults above.
  `ClassifierComparisonTest` prints the precision/coverage curve for both models — the harness used to
  pick the defaults, and the place to re-tune against real data.

### Why the embedding decision is a lead, not a similarity

The embedding suggester used to accept its nearest prototype whenever the **cosine** cleared a
threshold. That threshold could not work: multilingual-e5-small embeds every German bank string into
a narrow cone, so on the harness fixture the winning cosine runs 0.863–0.981 for in-taxonomy text and
0.822–0.881 for pure noise — overlapping ranges. Coverage stayed at 1.00 across the whole σ sweep
from 0.62 to 0.85, i.e. the slider could not change a single decision, and **every** transaction got a
proposal whether or not the model had an opinion.

What carries signal is the **lead over the runner-up** — `top1 − top2`, taken over *distinct
categories* (with few-shot examples the two nearest vectors are usually two transactions of the same
category, where the difference means nothing). Measured on the fixture, in-taxonomy rows have a lead
of 0.035 at the 25th percentile, while rows that should get no answer at all top out at 0.017. The
default sits at **0.03**, above that noise ceiling:

| criterion | precision | coverage | false fires on rows with no right answer |
|---|---|---|---|
| cosine σ = 0.62 (old default) | 0.75 | 1.00 | 6 of 6 |
| cosine σ = 0.85 | 0.79 | 0.95 | 4 of 6 |
| **lead ≥ 0.03 (current default)** | **1.00** | **0.68** | **0 of 6** |

The suggester now covers less and is right when it speaks, which is the correct trade for something
whose output a user confirms by hand. `minSimilarity` survives only as a sanity floor (0.35, below
anything real text produces) so a degenerate query cannot be decided by a lead between two near-zero
numbers.

Because a lead is not a cosine, the setting uses a **new key**: a vault previously tuned to 0.62
falls back to the retuned default rather than having its old cosine reinterpreted as a lead, which
would have silenced the suggester completely.
