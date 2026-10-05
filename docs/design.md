# Design notes

These are the decisions behind `mule-ring-detector`, written so each one can be defended (or
challenged) in a design review. Numbers quoted here come from `reports/` and were measured on
the IBM AMLworld HI-Small file; see the README for the full tables.

## 1. Problem framing

Money-mule networks move funds through chains of accounts so that no single transfer looks
unusual. The signal is in the *shape* of the flow over hours to days: one account spraying
funds to many (fan-out), many feeding one (fan-in), funds coming back to their origin (cycle),
an origin splitting through parallel intermediaries that reconverge (scatter-gather), and so on.
A per-transaction rule ("amount > X") cannot see shape; a batch graph job sees shape but only
hours later. The engine therefore keeps a **bounded, incremental temporal graph** and evaluates
shape-based detectors on every new edge, in the stream.

Two products come out of each transfer:

1. A feature vector (detector measurements) scored by a small model → alert or not.
2. For alerts, the **evidence**: the concrete edges that made each detector fire, plus the
   top model contributions. The Java service turns this into cases and reports.

## 2. Windowing

* **Event time, not wall time.** Everything keys off transaction timestamps so replays are
  deterministic and results reproducible. Arrivals that are older than the current event time
  are clamped to it (the IBM file is not time-ordered, so `mrd prepare` sorts it first; in
  production a small reorder buffer upstream would do the same job).
* **96 h structural window.** In the HI-Small patterns file the median laundering attempt spans
  1–3 days and the longest span about 4 days for fan/cycle typologies (gather-scatter is longer).
  A 96 h window captures most attempts end-to-end. Velocity uses its own 24 h sub-window.
* **Lazy + periodic eviction.** Adjacency deques are time-ordered, so eviction is `pop_front`
  while stale, done when an account is touched; an hourly (event-time) sweep handles accounts
  that went quiet and releases their buffers. Live-range iteration uses `partition_point`, so
  even a not-yet-evicted deque is read correctly.
* **Per-account cap (256 edges per direction).** Without it, a payroll processor or exchange
  with tens of thousands of transfers per window would dominate memory and latency. The cap
  turns their window into "last 256 edges". This is a deliberate accuracy-for-latency trade:
  hubs are rarely the mules themselves, and the hub exclusion in ring building (below) already
  treats them specially.
* Memory is O(live edges + accounts). On HI-Small the estimated state peaks well under 1 GB
  (see bench report); the account-name interner is the only unbounded part (bounded in practice
  by the customer base; an LRU on dormant accounts would be the production fix).

## 3. Detectors and their complexity

Let *d* be the (capped) live degree of an account.

| Detector | What it measures | Cost per edge |
|---|---|---|
| fan-out / fan-in | distinct counterparties of sender / receiver in window | O(d) |
| gather-scatter | min(distinct in, distinct out) of the sender | O(d) |
| scatter-gather | max over the sender's funders *s* of intermediaries *m* with s→m and m→receiver | O(b·d) with b ≤ 24 funders |
| pass-through | most recent inbound to the sender that the outbound forwards with ≤10 % skim; gap in minutes | O(d) bounded by 24 h look-back |
| velocity | sender's outbound count in 24 h vs its own long-run daily rate | O(d) |
| cycle | does the new edge close a temporal cycle of ≤ 6 edges? | bounded DFS (below) |
| ring | size of the connected component of rule-flagged edges around the sender | α(n) amortised |

**Cycle detection.** A new edge `u→v` at time *t* closes a cycle if there is a path
`v→…→u` with non-decreasing timestamps, all inside the window, before *t*. The search runs
backwards from *u* over incoming edges with timestamps ≤ the current bound, depth-limited to
5 hops, branch-limited to the 24 most recent in-edges per node and capped at 2,000 adjacency
reads in total. A node is re-expanded only if reached with a *later* time bound than before
(a later bound strictly dominates an earlier one). A cheap pre-check (does *v* have any live
outbound edge?) skips most edges outright. Worst case is therefore O(budget) per edge, i.e.
constant, at the price of possibly missing cycles that run through very busy nodes.
Exhaustive temporal cycle enumeration (e.g. Johnson's algorithm adapted to time-respecting
paths) is exponential in the worst case and has no place in a per-transaction hot path.

Amounts are *not* a hard condition for cycles: in AMLworld, hops change currency and fees, so
the min/max amount ratio is passed to the model as a feature instead.

**Ring tracking.** Union-find over edges where a structural detector fired, skipping hub
accounts (more than 100 live edges) that would otherwise glue everything into one giant
component. Union-find cannot delete, so it is rebuilt from the live suspicious edges every
6 h of event time; between rebuilds unions are incremental. A second, separate union-find over
*alerted* edges provides the ring id and member list that travel with each alert — that is what
the case service groups on, so a noisy rule cannot merge unrelated cases.

## 4. Scoring

* **Features (33):** payment-format one-hot, log amount (USD via frozen FX medians derived from
  the file's own cross-currency rows), cross-currency / same-bank / round-amount flags, the
  detector measurements above, and cross-fitted first-stage neighbour-risk scores.
* **Models:** gradient-boosted depth-4 trees (primary) and L2 logistic regression, both trained
  in Rust (~400 lines total). Trees win because the useful signal is interaction-heavy
  ("ACH *and* new counterparty *and* part of a fan-out"), which a linear model cannot express.
* **Class imbalance:** ~0.1 % positives. All training positives are kept; negatives are sampled
  at 10 % with weight 10 so probabilities stay calibrated-ish and training takes seconds.
* **Explainability:** logistic regression contributions are `w_i·z_i`; the trees use Saabas path
  attribution (credit each split's feature with the change in node value), which sums exactly
  to the margin and is cheap enough to compute per alert.
* **Thresholds are chosen on validation only.** The split is by time (train < 2022-09-07 ≤
  val < 2022-09-09 ≤ test); every operating threshold — model and baselines alike — maximises F1
  on validation and is then frozen. Early stopping of the trees uses validation average
  precision. Model selection uses training and validation; `mrd eval` reports the frozen models on the test windows.

## 5. Why explainability matters for AML

* Regulators expect a firm to explain *why* a customer was reported; a Suspicious Transaction
  Report narrative needs concrete transactions, counterparties and a typology, not a score.
* Analyst throughput: an alert that says "closes a 4-hop cycle, here are the 4 transfers" is
  triaged in minutes; a bare 0.93 probability sends the analyst to rebuild the graph by hand.
* Model risk management (SR 11-7-style governance) requires that model drivers be understood
  and monitored; per-alert attributions also make drift visible ("why did pass-through
  suddenly dominate?").
* Evidence edges let the Java service draw the ring and attach the exact transfers to the
  goAML-style report.

## 6. False-positive economics

In AML, false positives are the dominant cost: each alert costs analyst time (industry figures
are commonly quoted in the tens of minutes per alert) and the vast majority of legacy rule
alerts are closed as false positives. Missing a ring has regulatory and reputational cost,
but alert volume is what breaks an operations budget. That is why the evaluation reports
**alerts per 10k transactions** and **average precision** alongside F1, and why the threshold
is a business parameter: F1 on validation is a reasonable default for a portfolio comparison,
but a real deployment would pick the threshold from analyst capacity (alerts/day) and
re-measure precision at that volume. Pattern-level recall (was *any* transfer of a laundering
attempt alerted?) is reported because one good alert usually lets an investigator unwind the
whole ring.

## 7. What I would do next

* Temporal graph neural network or richer ego-graph features (the AMLworld paper's GNN
  baselines reach higher minority-class F1 on this data; see README limitations).
* Feedback loop: analyst dispositions from the case service as labels for retraining.
* Exactly-once ingestion from Kafka with state checkpoints (the engine state is a few hundred
  MB, small enough to snapshot).
* Account-name eviction / LRU and a reorder buffer for late events.
