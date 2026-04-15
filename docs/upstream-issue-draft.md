# Upstream Apache Pulsar issue draft

Draft of a docs-only issue to file against `apache/pulsar`. Filed on
2026-04-15 after a duplicate check (closest related: #23944, which covers
a different angle of the same broader theme).

To file from this directory:

```bash
gh issue create --repo apache/pulsar \
  --title "Docs: warn that DeadLetterPolicy.maxRedeliverCount is silently unreliable with negativeAcknowledge under bundle unload / dispatcher close" \
  --label type/docs \
  --body-file docs/upstream-issue-draft.md
```

(`gh` will include this file's heading in the body — strip the lines above
the `---` separator before filing, or use `--body` with a heredoc instead.)

After filing, also leave a short comment on
[#23944](https://github.com/apache/pulsar/issues/23944) noting that this
issue is the docs counterpart to their behavior-change discussion, and
that the bundle-unload trigger is complementary to the consumer-crash
trigger they describe.

---

## Summary

The docs for `negativeAcknowledge` and `DeadLetterPolicy.maxRedeliverCount` don't warn that the redelivery counter is kept exclusively in client and broker RAM, and is lost whenever the subscription's dispatcher is closed. In any cluster with `loadBalancerEnabled=true` (the default), bundles are routinely unloaded and rebalanced, so `maxRedeliverCount` acts as a soft guideline at best and the DLQ is in practice never reached for long-lived poison messages.

This is **not** a behavior bug: `reconsumeLater(...)` with `enableRetry(true)` works correctly and persists its counter as a `RECONSUMETIMES` property on the retry topic, in BookKeeper. The problem is that nothing in the documentation tells a reader to prefer `reconsumeLater` over `negativeAcknowledge` when they care about DLQ guarantees — the two are presented as interchangeable retry mechanisms.

Related discussion: #23944 covers a different angle of the same broader "redelivery count is unreliable" theme (consumer crashes vs. dispatcher close), and proposes a behavior change. This issue is intentionally narrower and **docs-only** — even if #23944 lands a behavior fix, the docs should warn current users today.

## Why this matters (real-world)

Our team ran a consumer with `negativeAcknowledge` + `DeadLetterPolicy(maxRedeliverCount=10)` in production for months before realizing the DLQ was never reached. The subscription accumulated ~400k messages in the backlog because every bundle unload reset the in-memory redelivery counter, and the backoff cycle was longer than the typical time between unloads. We only understood the mechanism after writing an instrumented reproduction against a standalone broker. **A single paragraph in the docs would have saved us that time**, which is why I'm opening this.

## Reproduction

Self-contained, runnable: https://github.com/ng-galien/bug-pulsar

| # | Client | Broker | Event applied | DLQ reached? |
|---|---|---|---|---|
| A | 2.11.0 | 2.11.0 | `docker-compose restart pulsar` | ❌ 0 msgs |
| D | 2.11.0 | 2.11.0 | `pulsar-admin topics unload` (broker stays up) | ❌ 0 msgs |
| V3 | 2.11.0 | 3.3.9 | `pulsar-admin topics unload` | ❌ 0 msgs |
| V3 (`-Pclient-v3`) | 3.3.9 | 3.3.9 | `pulsar-admin topics unload` | ❌ 0 msgs |

After the unload, the same `MessageId`s come back with `redeliveryCount=0` because the `InMemoryRedeliveryTracker` attached to the dispatcher is destroyed and recreated empty. Scenario B in the same repo validates the counterpart: `reconsumeLater(...)` + `enableRetry(true)` is unaffected by either a broker restart or a topic unload, because the counter lives on the retry topic as a persisted message property.

The bug reproduces identically on the latest 3.3.x line, with both the 2.11 and 3.3 clients.

## What I'm asking for

Docs-only. No behavior change, no API change.

1. In the *Negative acknowledgement* page, add a warning callout stating:
   - the redelivery counter is kept only in client (`NegativeAcksTracker`) + broker (`InMemoryRedeliveryTracker`) RAM;
   - any event that closes the subscription dispatcher (broker restart, bundle unload, bundle rebalance, last consumer disconnect) resets the counter;
   - therefore `DeadLetterPolicy.maxRedeliverCount` cannot be relied on as a hard upper bound when `negativeAcknowledge` is the retry mechanism;
   - recommend `reconsumeLater(...)` with `enableRetry(true)` when the DLQ must actually be reached.

2. In the javadoc for `Consumer.negativeAcknowledge(...)` and `DeadLetterPolicy.Builder.maxRedeliverCount(...)`, add a one-liner that links to that callout.

3. Optional: update the *Retry letter topic* page to cross-reference the nack page and explain *why* one would choose one over the other — the current docs describe them as alternatives without explaining the persistence difference, which is arguably the entire point.

Happy to submit a docs PR with proposed wording if a maintainer agrees this is worth documenting.

## Versions covered by the repro

- Broker: `apachepulsar/pulsar:2.11.0` and `apachepulsar/pulsar:3.3.9` (both reproduce)
- Client: `pulsar-client:2.11.0` and `pulsar-client:3.3.9` (both reproduce)
- Java: 21 (Temurin), macOS + Docker
