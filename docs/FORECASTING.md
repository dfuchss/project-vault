# Recurring detection & cash-flow forecasting

How Project Vault detects recurring series and projects your balance forward — deterministic and
explainable, computed on-device in `:core:analytics` (`Recurring`).

![Forecast card](screenshots/forecast.png)

## Recurring detection

`Recurring.detect` groups transactions by a **merchant key** (first stable alphanumeric token of the
counterparty, uppercased), then splits each payer into **amount clusters** so a fixed salary isn't
merged with the same employer's bonuses. A cluster becomes a series when it has enough occurrences
(default 3) whose gaps form a regular **cadence** (monthly / quarterly / yearly) — and a *majority* of
the actual gaps must match that cadence, not just the median, so noisy "PAYPAL"/"VISA" groups don't
produce bogus series. The **median** amount is taken as typical. **Transfers are excluded** (internal
money movements are never a bill or income).

You can **rename** or **hide** a detected series, or **add your own** (selected from an existing
counterparty, so the amount comes from real data — never free text). Overrides and manual series are
stored in the vault (`recurringOverride` / `recurringManual`).

## The forecast

The forecast projects **net worth** forward six months. Each future month starts from the running
balance and applies:

1. **Fixed items** — every recurring series due that month (`Recurring.forecast`), income positive,
   bills negative.
2. **Variable spending** — everything that *isn't* a fixed bill: groceries, restaurants, one-off
   shopping, fuel, etc. `Recurring.variableMonthlySpending` sums these per calendar month over the
   **last 12 months** and reduces them to a **mean (ø)** and **population standard deviation (σ)**. An
   expense counts as "fixed" (and is excluded here) only when it matches a detected recurring *expense*
   series by merchant key **and** amount (within the detector's tolerance).

The **central line** = balance after fixed net *minus* the mean variable spend — a realistic
trajectory rather than one that pretends only fixed costs exist.

### The cone of uncertainty

Variable spending varies month to month, so a point estimate would be misleading. Treating each
month's variable spend as independent, variances **add**, so the ±1σ half-width of the *cumulative*
spend after `k` months is `σ·√k`. The shaded band is `central ± σ·√k` — a cone that widens the
further out you look. Hovering any month shows that month's **expected min … max**. If the band's
lower edge crosses zero, the card flags a **possible cash shortfall**.

This is deliberately simple and explainable (no hidden model): mean + standard deviation of your own
recent history, rolled forward. It answers "where is my balance likely heading, and how wide is the
plausible range?"

## Limitations

- Needs a few months of history for a meaningful σ; with one month the band collapses to the mean.
- Assumes months are independent and roughly stationary — a deliberate lifestyle change (new rent,
  new job) only shows once it's in the trailing window. The 12-month cap keeps it tracking current
  habits rather than long-gone ones.
- Seasonality (e.g. December spending) isn't modelled yet; it's folded into σ as extra spread.
