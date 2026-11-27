# Local authorization management

The bridge owns authorization maintenance through `src/auth-profiles.js` and
`src/auth-profiles.py`. `src/auth_store.py` handles credential storage and version
comparison. `src/auth_restarts.py` schedules, verifies and records local restarts.
The connected local runtime determines the Codex home used by phone RPCs.

## Storage and interfaces

Existing files are retained: `auth.json`, `auth-profiles/<positive numeric ID>/auth.json`, and
`auth-profiles/maintenance/`. All mutations take the shared `manager.lock`;
credentials use private temporary files, atomic rename and file/directory fsync.
A conflicting operation returns `MANAGER_BUSY`. Revision checks reject stale
phone selections with `PROFILE_CHANGED`.

`account/profiles/list` also returns `restartHistory` and `supportsManualRestart`.
`switch` and `restart` return a queued or failed operation record with an operation
ID. Account RPCs use the same paired bridge routing:

| Method suffix | Parameters | Result |
| --- | --- | --- |
| `status` | none | Registered profiles, active identity match, maintenance metadata |
| `check` | `profile`, `revision` | File structure and token timestamp metadata |
| `import` | `profile`, `revision`, `sourcePath` | Import/replace a numbered slot from a completed protected workspace upload |
| `rename` | `profile`, `revision`, `displayName`, `expectedName` | Set or clear the library label with a stale-edit check |
| `remove` | `profile`, `revision` | Remove an inactive entry from the list and scheduler; retain a private recovery copy |
| `export` | `profile`, `revision` | Private snapshot path for the existing protected workspace file transfer |
| `restart` | none | A queued or rejected manual restart with its own operation ID |

The library can start empty. Status returns the available `nextProfileId`; import
with that ID, revision `missing`, and an optional label (`displayName`, possibly
empty) creates the file and label together. A concurrent creation requires a
fresh status read. The host also discovers existing numeric directories, including
the original 1, 2 and 3. Removed IDs are reserved under `maintenance/removed/`.
Labels live in `label.json` alongside the credential. File metadata includes
the account name and email when available in local token claims. An absent claim
is returned as null; displaying these fields does not perform a token refresh.

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
`iat`. The same rule applies during daily maintenance.

## Codex restart and recovery

For a Linux systemd installation, the Android authorization page offers
**Restart Codex** and **Restart history**.
Manual restart uses the current authorization. Account switching and manual restart
both verify the configured local Unix socket and the reported daemon management
backend before scheduling a worker with a three-second countdown. The worker checks the same socket and owner
again under `manager.lock`, then checks that the selected credentials still match
the queued revisions. Switching writes the target authorization only at this
point. Restart success requires a passive account check against the connected
runtime. A running process or a changed file alone is insufficient evidence.

Each operation updates its own private record in `auth-profiles/maintenance/restarts/`.
It includes the operation ID, action, target profile, timestamps, attempt,
result, stage, sanitized original diagnostics and recovery outcome. The host
retains 30 new records and the phone displays up to 10 recent records, including
the last legacy result when available. A confirmed runtime can show the latest
legacy operation as recovered while keeping its original failure. A new manual
restart carries a link to the immediately preceding failed operation when one exists.

The worker can restore the original authorization when the old runtime account
is confirmed and the exact bytes installed by this operation are still present.
When the outcome is uncertain, the worker retains the previous authorization in
the private `auth-profiles/maintenance/restart-recovery/<operationId>/` directory
for operator recovery. History contains diagnostic evidence and recovery status.
After a timeout or an interrupted worker, check history before retrying. A manual
restart uses the authorization then on disk and creates a separate record. The phone shows the observed owner. For a dedicated systemd service, the Unix
listener must be the service main PID in the exact control group. For a verified SSH or direct listener, **Restart Codex** relaunches the original
installed executable, arguments and working directory with the same environment.
The worker checks the same-user listener again before stopping it and confirms
the new listener and on-disk account before reporting completion. For a direct
listener that is still draining after three seconds, the worker sends a second
shutdown signal through the same pidfd to request immediate Codex shutdown.
If stopping timed out, a later list refresh can confirm recovery by verifying
a replacement direct listener and the selected account. History retains the
original failure. No daemon
installation or boot registration is needed. A new direct process has a detached
session and writes to the local Codex server log. For these instances, **Set up daemon management** requires separate confirmation
for that listener and the selected authorization. The worker rechecks both before
signalling that process and starting the official daemon. Unknown launchers require
host-side verification.

The phone polls persisted stages through `account/profiles/restart-progress`.
After reconnecting it reloads the latest operation. When the live account differs
from the selected file, the authorization page reads its weekly quota on entry,
with a five-minute refresh interval while visible. The existing refresh action
forces a read. Quota is attributed to the verified runtime identity.

When the phone cannot reach the bridge, run these commands on the Codex host
from the server checkout or release directory. Supply `--home` if the runtime uses
a different Codex home. `--start-if-stopped` allows the daemon manager to start
Codex when the local control socket is confirmed offline.

```sh
/usr/bin/python3 phodex-bridge/src/auth-cli.py restart-history
/usr/bin/python3 phodex-bridge/src/auth-cli.py restart
/usr/bin/python3 phodex-bridge/src/auth-cli.py restart --start-if-stopped
```

## macOS restart ownership

On macOS a bridge-owned stdio child can be restarted through the existing
account operations. The worker uses inherited local pipes to check the live
account and request replacement of the exact child generation. It retains the
account lock across the countdown, file write, replacement and verification.
No network listener or new persistent service is created. A shared canonical
Unix endpoint is eligible when the Codex daemon manages it or when its peer is
the main Codex executable of a verified user launchd job. A detached worker
rechecks ownership before switching. An unmanaged external listener has no
automatic restart action.

## Daily maintenance

The Android **Settings → Account and authorization** page displays scheduler
checks, daily maintenance, current-account synchronization/fallback status and
sanitized failure details. Add authorization opens a display-name field and the
system document picker. Existing entries can be renamed; inactive ones can also
be replaced or removed through their file menus. File menus provide timestamp
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

An explicit refresh-token revocation from Codex is reported as
`REFRESH_TOKEN_REVOKED`. Both automatic refresh paths skip that credential on
future checks. The private record preserves the original diagnostic and follows
the refresh credential across file rewrites and alias changes. Importing a
different refresh token makes the profile eligible again under the normal
schedule. A bare HTTP 401 or other unconfirmed failure keeps the ordinary retry
schedule.

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

### Quota reset cards

The paired client calls `account/profiles/reset-card` with a specific credit ID. The host checks the running identity for the current account, or opens an isolated Codex with the selected saved profile and revision. Each step runs under the authorization manager lock and reads the ordinary Codex weekly quota and current card details. Missing or ambiguous quota, unavailable cards and unverifiable identities block redemption.

An initial request only returns a result or a short-lived confirmation. At zero remaining, one confirmation is required. Above zero through 5% inclusive the host issues a second confirmation. Above 5% no confirmation is issued. Single-use confirmations bind the account, subject, profile, card and remaining percentage, expire after two minutes and are saved atomically on the host. The native consume call always identifies the card and uses an account/card-specific idempotency key. The paired router rejects direct native consume requests. After an uncertain result, read quota and card status before starting another attempt.
