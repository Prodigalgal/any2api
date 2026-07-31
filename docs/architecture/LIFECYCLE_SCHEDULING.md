# Lifecycle and Expiry Scheduling

## No full-pool timers

Every account has an independently persisted row in `scheduled_actions`. Unknown expirations schedule periodic probes from the last successful action. Restarting a service does not re-randomize the population.

## Recovery funnel

Expired accounts progress from cheap to expensive recovery:

```text
probe -> refresh token -> stored session OAuth -> credential login -> new session OAuth
      -> mailbox OTP -> browser reauth/captcha -> waiting_input or terminal state
```

Each stage has independent global, provider, action, egress, and mailbox-domain budgets. Browser recovery cannot consume inference or realtime risk capacity.

## Storm prevention

- Deterministic jitter spreads both normal schedules and catch-up work.
- Exponential backoff includes decorrelated jitter.
- PostgreSQL claims a bounded batch with `FOR UPDATE SKIP LOCKED`.
- Expired durable leases consume an attempt and return with a delay; they cannot reset forever.
- Manual reauthentication uses a PostgreSQL advisory lock and coalesces an existing pending/leased action.
- Keepalive authentication failure transforms the same action into delayed reauthentication.
- Reauthentication and recovery of a pending or expired account must pass a real inference probe;
  a successful profile/config request alone cannot promote the account.
- An inference `credential_rejected` result keeps the action in delayed reauthentication even when
  the provider's lightweight keepalive endpoint succeeded. Account probe metadata is forwarded to
  the isolated provider worker so it can skip credential fast paths already proven unusable.
- Successful reauthentication plus inference readiness transforms the action back into keepalive
  and supersedes duplicate action families.
- Credential expiry returned by a provider worker is persisted with the rotated credential; the next healthy action is spread into the pre-expiry refresh window instead of being scheduled after token expiry.
- Terminal authentication failures become `EXHAUSTED` and are not retried indefinitely.
- Repeated non-terminal lifecycle failures become `EXHAUSTED` after 12 attempts.
- Rows past their explicit `expires_at` become `EXPIRED` before claims are issued.
- Registration lease loss and batch-level failures increment both attempt and failure counters; the configured attempt budget ends the job as `FAILED`.

## Lease protocol

Inference and lifecycle share account capacity. Redis acquisition is an atomic script that verifies eligibility, increments inflight, creates a TTL lease, and returns a unique owner token plus fencing token. Renewal and release compare the owner token. Losing renewal aborts upstream work and fails closed.

Python browser jobs additionally hold a durable Java job lease and a Redis-fenced proxy-node lease. The proxy lease TTL is longer than the bounded browser attempt and release compares its random owner token.

## Observability

Required metrics include due accounts, oldest due age, action backlog, retry error classes, circuit state, account recovery stage, browser queue wait, captcha success by solver, stale-message count, and lease renewal failures.
