# Lifecycle and Expiry Scheduling

## No full-pool timers

Every account has an independently persisted `next_action_at`. Known expirations schedule renewal before expiry with a provider safety window and deterministic jitter. Unknown expirations schedule periodic probes from the last successful action. Restarting a service does not re-randomize the population.

## Recovery funnel

Expired accounts progress from cheap to expensive recovery:

```text
probe -> refresh token -> stored-session/credential login
      -> mailbox OTP -> browser reauth/captcha -> waiting_input or terminal state
```

Each stage has independent global, provider, action, egress, and mailbox-domain budgets. Browser recovery cannot consume inference or realtime risk capacity.

## Storm prevention

- Deterministic jitter spreads both normal schedules and catch-up work.
- Exponential backoff includes decorrelated jitter.
- Provider circuit breakers stop mass retries during shared upstream failures.
- Half-open recovery admits a small number of probes and ramps gradually.
- Manual bulk actions create a campaign spread over a window; they do not bypass budgets.
- Queue depth and oldest-due age control scheduler prefetch.
- Stale Redis messages are rejected using `generation` and `expires_at`.
- Duplicate action families are coalesced by idempotency key.

## Lease protocol

Inference and lifecycle share account capacity. Redis acquisition is an atomic script that verifies eligibility, increments inflight, creates a TTL lease, and returns a unique owner token plus fencing token. Renewal and release compare the owner token. Losing renewal aborts upstream work and fails closed.

Python browser jobs additionally hold a durable Java job lease and local browser/proxy leases. A job heartbeat renews them together; terminal cleanup is idempotent.

## Observability

Required metrics include due accounts, oldest due age, action backlog, retry error classes, circuit state, account recovery stage, browser queue wait, captcha success by solver, stale-message count, and lease renewal failures.

