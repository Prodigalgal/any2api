# Legacy account migration

`migrate_legacy_accounts.py` reads read-only SQLite snapshots from Grok, LongCat, MiMo, and Qwen.
It maps each row to a provider-scoped Any2API identity and imports credentials through the admin API,
where they are encrypted with the target credential master key. MinMax and GLM have no legacy account
store in the current source inventory.

The default mode is a secret-silent dry run. `--apply` requires
`ANY2API_MIGRATION_ADMIN_PASSWORD` and imports every source row as `PENDING`, disabled, with lifecycle
scheduling suppressed. `--schedule-activations` is a separate phase and spreads activation actions
over the configured window. Providers with reauthentication support refresh credentials first; other
providers execute a real inference probe. A healthy provider probe promotes an account to `ACTIVE`;
a stored row alone never does.

The report contains counts, inventory hashes, and hashed failure references. It never contains
credentials, email addresses, or raw external account IDs. SQLite snapshots and reports containing
operational inventory must stay outside Git, under the private infrastructure workspace.
