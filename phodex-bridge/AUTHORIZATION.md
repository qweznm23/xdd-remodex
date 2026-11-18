# Local authorization management

The bridge owns authorization maintenance through `src/auth-profiles.js` and
`src/auth-profiles.py`. Storage, locking, credential version comparison and delayed
switch restarts live in `src/auth_store.py`. No separately installed auth manager
is required. The connected local runtime determines the Codex home; phone RPCs
cannot select another home.

## Storage and interfaces

Existing files are retained: `auth.json`, `auth-profiles/{1,2,3}/auth.json`, and
`auth-profiles/maintenance/`. All mutations take the shared `manager.lock`;
credentials use private temporary files, atomic rename and file/directory fsync.
A conflicting operation returns `MANAGER_BUSY`. Revision checks reject stale
phone selections with `PROFILE_CHANGED`.

Existing `account/profiles/list`, `query`, `refreshToken`, and `switch` calls retain
their response contracts. Additional calls use the same paired bridge routing:

| Method suffix | Parameters | Result |
| --- | --- | --- |
| `status` | none | Registered profiles, active identity match, maintenance metadata |
| `check` | `profile`, `revision` | File structure and token timestamp metadata |
| `import` | `profile`, `revision`, `sourcePath` | Import/replace a numbered slot from a completed protected workspace upload |
| `export` | `profile`, `revision` | Private snapshot path for the existing protected workspace file transfer |

Import uses revision `missing` only for an absent slot; replacement saves the
prior file privately. `check`, `export`, and `refreshToken` also accept `current`.
Metadata never returns token values. `valid_by_timestamp` only describes token
timestamps, **not** verified server acceptance. `query` obtains account/usage
information without forcing token rotation. Current-account manual refresh goes
through the connected Codex runtime. Inactive refresh uses an isolated Codex home
and commits new credentials only after validation and a revision check. Failed
commits keep the newly issued file in the private recovery directory.

Switching synchronizes matching current-account library entries only when the
current credential version is strictly newer. Equal or unknown order preserves
the library file. The comparison uses `last_refresh`, falling back to access-token
`iat`. The same rule applies during daily maintenance. Switch acknowledgements and
restart results have an operation ID so an older callback cannot replace the
newer operation's state. Failure to schedule restart rolls back the selection.
The existing runtime-account verification still determines whether the phone can
report the switch ready.

## Daily maintenance

The Android **Settings → Account and authorization** page displays scheduler
checks, daily maintenance, current-account synchronization/fallback status and
sanitized failure details. Import/replace selects a numbered slot and a system
document; active slots cannot be selected there. File menus provide timestamp
checks and export through the existing protected file transport to a user-chosen
document destination. Imported files are limited to 64 KiB before upload.
Reading this page and refreshing the list never force token rotation. Existing
quota queries, explicit token refresh and verified account switching remain.

`src/auth-maintenance.js` starts with the bridge: first check after 15 seconds,
then every minute. The due time is 04:00 Asia/Shanghai. A late bridge startup
recovers that day's due work. Set `REMODEX_AUTH_SCHEDULE_ENABLED=0` to disable this
owner when deliberately running another scheduler.

The active account is checked every minute, including before 04:00 and after the
daily job. Newer current credentials are synchronized to matching library slots.
Within 15 minutes of access-token expiry, Remodex may ask the connected Codex to
refresh and persist the active credentials, then immediately synchronize matching
slots. This fallback is attempted at most once per Beijing calendar day per
account, with the attempt persisted before rotation. Healthy credentials do not
rotate. Newer or ambiguous library versions stop fallback and report a conflict;
the bridge never injects a possibly stale library token into the live daemon.
Manual current-account refresh also synchronizes matching slots immediately.
`last-active-maintenance.json` records this check separately from the daily job.
Inactive
accounts have at most one automatic attempt per Beijing calendar day, including
failed attempts. The attempt is persisted **before** requesting rotation; both
existing per-slot records and account-identity records are honored. Normal manual
refresh also records an attempt, so the daily job does not immediately repeat it.
Successful completion and the next due time survive bridge restart. A crash after
an attempt does not authorize another automatic attempt that day.

`maintenance/remodex-scheduler.json` records the successful scheduler heartbeat,
process and instance IDs. `state.json` holds attempts and schedule state;
`last-remodex-run.json` holds per-profile results. A heartbeat is evidence of a
check at its timestamp, not proof that its process is still alive. Validate its
PID against the running bridge when diagnosing takeover.

Do not run a legacy refresh timer alongside this scheduler. When migrating, first
back up the old code and service configuration, dry-run the due work, verify the
new owner's heartbeat and restart recovery, and only then disable the old timer.
Configuration rollback must preserve all credentials and recovery files produced
since deployment; do not restore stale tokens from a deployment backup.

## Diagnostics and local operations

Errors retain operation, stage, type, cause/context, stack, suppressed cleanup
errors, and available RPC/HTTP/OS codes. Known credentials and bearer/JWT strings
are redacted. Detailed refresh diagnostics are private and bounded, with explicit
truncation markers. HTTP 401, HTTP 403, timeout, malformed responses and failed
disk commits remain distinct; none alone is treated as proof of token expiry.

Run these from `phodex-bridge` (the default home is `~/.codex`):

```sh
/usr/bin/python3 src/auth-cli.py status
/usr/bin/python3 src/auth-cli.py refresh --dry-run
/usr/bin/python3 src/auth-cli.py switch 2 --dry-run
```

`refresh` obeys daily scheduling. Local `--force` deliberately overrides it and
may rotate inactive credentials; it is not exposed as a phone maintenance RPC.
The direct Python entry accepts explicit `--home` for isolated test fixtures.

Tests use synthetic tokens and an isolated fake Codex executable, never a real
account rotation. They cover normal bridge routing, protected upload/export,
manual refresh, switch/restart, concurrency, daily recovery, error evidence and
failed-commit credential recovery:

```sh
/usr/bin/python3 test/auth-profiles-test.py
node --test test/auth-profiles.test.js test/auth-maintenance-integration.test.js test/bridge-desktop-ipc-integration.test.js test/auth-file-info.test.js
```
