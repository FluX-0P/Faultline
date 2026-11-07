# The planted bugs

The demo app's ten bugs, each switched on by a setting with a neutral name. Each page says:
- what the bug is, and where it is in the code;
- the fault that proves it;
- the evidence from the latest benchmark;
- the fix, which is the bug-free setting.

All the numbers come from the benchmark of 2026-11-07, seed 42, hand-written plans:
- **With the bug:** every bug was confirmed. Its check failed in both runs with the fault, and passed in the baseline.
- **On the bug-free build:** every bug-free plan came out suspected. The fault fired in both runs and every check passed.
- **The scan:** it flagged each of B1 to B8 at its location. B9 and B10 have no code pattern on purpose.

| Bug | What | Pattern | Check that fails |
|---|---|---|---|
| [B1](B1.md) | Saves, then acknowledges the message | P1 | `no-duplicates` |
| [B2](B2.md) | Auto-commit with a thread pool | P2 | `no-lost` |
| [B3](B3.md) | Dedupes by message ID only, so a double submit slips through | P3 | `one-charge-per-payment` |
| [B4](B4.md) | Read-modify-write on the balance | P4 | `shop-balance-adds-up` |
| [B5](B5.md) | Retries a timed-out charge without an idempotency key | P5 | `one-charge-per-payment-at-the-provider` |
| [B6](B6.md) | Immediate, unlimited retries | P6 | `recovery-within` |
| [B7](B7.md) | No dead-letter handling for bad messages | P7 | `no-lost` |
| [B8](B8.md) | Saves, then publishes "payment completed" | P8 | `no-lost-downstream` |
| [B9](B9.md) | A retry topic reorders events for one account | - | `per-key-order` |
| [B10](B10.md) | A frozen worker wakes up and writes stale data | - | `no-duplicates` |

Rerun one: `./faultline benchmark --bug B4`. [benchmark/bugs.yaml](../../benchmark/bugs.yaml) lists every switch.
