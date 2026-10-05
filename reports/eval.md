### Split `test` (2022-09-09T00:00:00Z .. end) — 863900 transactions, 1611 laundering

| Method | Threshold (from val) | Precision | Recall | F1 | Avg precision | Alerts | Alerts / 10k txns | Pattern recall (>=1 txn) | Pattern recall (>=50% txns) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| gbdt | 0.7558 | 0.581 | 0.570 | 0.576 | 0.579 | 1579 | 18.3 | 0.940 | 0.836 |
| lr | 0.2669 | 0.563 | 0.516 | 0.539 | 0.523 | 1479 | 17.1 | 0.880 | 0.699 |
| gbdt_txn_only | 0.0324 | 0.148 | 0.333 | 0.205 | 0.100 | 3628 | 42.0 | 0.694 | 0.443 |
| baseline_amount_rule | 16.1339 | 0.009 | 0.017 | 0.011 | 0.004 | 3282 | 38.0 | 0.060 | 0.011 |
| baseline_velocity_rule | 5.5491 | 0.001 | 0.068 | 0.003 | 0.001 | 79950 | 925.5 | 0.000 | 0.000 |
| rules_only | 3.0000 | 0.005 | 0.038 | 0.008 | 0.005 | 13547 | 156.8 | 0.120 | 0.027 |

Patterns ending in split: 183. Laundering transactions not listed in any pattern: 371.

| Typology | Patterns | Pattern recall (gbdt) | Pattern recall (baseline_amount_rule) | Txns | Txn recall (gbdt) | Txn recall (baseline_amount_rule) |
|---|---:|---:|---:|---:|---:|---:|
| BIPARTITE | 20 | 0.800 | 0.050 | 66 | 0.394 | 0.015 |
| CYCLE | 22 | 0.909 | 0.045 | 99 | 0.828 | 0.111 |
| FAN-IN | 19 | 0.947 | 0.053 | 123 | 0.423 | 0.008 |
| FAN-OUT | 21 | 0.952 | 0.000 | 131 | 0.870 | 0.000 |
| GATHER-SCATTER | 37 | 0.946 | 0.081 | 381 | 0.764 | 0.010 |
| RANDOM | 17 | 0.941 | 0.059 | 84 | 0.690 | 0.060 |
| SCATTER-GATHER | 22 | 1.000 | 0.091 | 239 | 0.858 | 0.013 |
| STACK | 25 | 1.000 | 0.080 | 117 | 0.701 | 0.017 |

Alert clusters (gbdt): 771 clusters, 201 contain laundering (cluster precision 0.261); median size 2 accounts, max 32.

### Split `test_until` (2022-09-09T00:00:00Z .. 2022-09-11T00:00:00Z) — 862792 transactions, 956 laundering

| Method | Threshold (from val) | Precision | Recall | F1 | Avg precision | Alerts | Alerts / 10k txns | Pattern recall (>=1 txn) | Pattern recall (>=50% txns) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| gbdt | 0.7558 | 0.398 | 0.439 | 0.417 | 0.394 | 1056 | 12.2 | 0.892 | 0.865 |
| lr | 0.2669 | 0.356 | 0.354 | 0.355 | 0.317 | 949 | 11.0 | 0.824 | 0.730 |
| gbdt_txn_only | 0.0324 | 0.078 | 0.273 | 0.121 | 0.047 | 3349 | 38.8 | 0.527 | 0.459 |
| baseline_amount_rule | 16.1339 | 0.004 | 0.015 | 0.007 | 0.002 | 3260 | 37.8 | 0.014 | 0.014 |
| baseline_velocity_rule | 5.5491 | 0.001 | 0.115 | 0.003 | 0.001 | 79950 | 926.6 | 0.000 | 0.000 |
| rules_only | 3.0000 | 0.003 | 0.044 | 0.006 | 0.004 | 13528 | 156.8 | 0.068 | 0.054 |

Patterns ending in split: 74. Laundering transactions not listed in any pattern: 371.

| Typology | Patterns | Pattern recall (gbdt) | Pattern recall (baseline_amount_rule) | Txns | Txn recall (gbdt) | Txn recall (baseline_amount_rule) |
|---|---:|---:|---:|---:|---:|---:|
| BIPARTITE | 11 | 0.909 | 0.000 | 26 | 0.462 | 0.000 |
| CYCLE | 11 | 0.818 | 0.000 | 21 | 0.714 | 0.000 |
| FAN-IN | 6 | 0.833 | 0.000 | 12 | 0.750 | 0.000 |
| FAN-OUT | 9 | 0.889 | 0.000 | 21 | 0.905 | 0.000 |
| GATHER-SCATTER | 10 | 0.800 | 0.000 | 23 | 0.870 | 0.000 |
| RANDOM | 8 | 0.875 | 0.125 | 19 | 0.842 | 0.263 |
| SCATTER-GATHER | 6 | 1.000 | 0.000 | 27 | 0.926 | 0.000 |
| STACK | 13 | 1.000 | 0.000 | 27 | 0.815 | 0.000 |

Alert clusters (gbdt): 736 clusters, 165 contain laundering (cluster precision 0.224); median size 2 accounts, max 18.
