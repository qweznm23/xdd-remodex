#!/usr/bin/env python3
"""Remodex authorization service. Metadata RPCs never return credentials."""
import argparse
import base64
import datetime as dt
import fcntl
import hashlib
import uuid
import json
import os
import re
from pathlib import Path
import shutil
import signal
import tempfile
import time
import traceback
import unicodedata

from auth_restarts import DISPLAY_LIMIT, HISTORY_LIMIT, PENDING, RestartOperations


class ProfileError(RuntimeError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def iso(seconds):
    return dt.datetime.fromtimestamp(seconds, dt.timezone.utc).isoformat()


def bounded_diagnostic(report, max_bytes=256 * 1024):
    encoded = json.dumps(report, ensure_ascii=False).encode()
    if len(encoded) <= max_bytes:
        return report
    return {**{key: report.get(key) for key in ('operation', 'profile', 'at', 'stage', 'type', 'code')},
            **{key: report[key] for key in ('errno', 'exitCode', 'timeoutSeconds') if key in report},
            'rpcCode': (report.get('rpc') or {}).get('code'), 'message': report.get('message', '')[: min(8192, max_bytes // 8)],
            'truncated': True, 'diagnosticPrefix': encoded[: min(96 * 1024, max_bytes * 3 // 8)].decode(errors='replace')}


class Profiles:
    def __init__(self, manager, socket=None):
        self.socket = socket
        self.owned = None
        self.m = manager
        self.secrets = set()
        self.stage = 'validate'

    @staticmethod
    def bound_restart_diagnostic(report):
        return bounded_diagnostic(report, max_bytes=24 * 1024)

    def registered(self):
        return self.m.registered_profiles()

    def next_number(self):
        reserved = {p.name for p in self.m.PROFILES.iterdir()}
        removed = self.m.STATE / 'removed'
        if removed.is_dir():
            reserved.update(p.name for p in removed.iterdir())
        number = 1
        while str(number) in reserved:
            number += 1
        return str(number)

    def label(self, number):
        if number == 'current':
            return None
        path = self.m.PROFILES / number / 'label.json'
        try:
            with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), 'r') as stream:
                return json.load(stream)['name']
        except FileNotFoundError:
            return None

    def clean_label(self, name):
        if not isinstance(name, str) or len(name.strip()) > 80 or any(unicodedata.category(c) in ('Cc', 'Cs') for c in name):
            raise ProfileError('INVALID_NAME', 'Choose a single-line name of up to 80 characters')
        return name.strip() or None

    def account_display(self, auth, access):
        # Local display claims only. Account matching and switching still use identity().
        sources = []
        token = auth['tokens'].get('id_token')
        if isinstance(token, str):
            try:
                part = token.split('.')[1]
                sources.append(json.loads(base64.urlsafe_b64decode(part + '=' * (-len(part) % 4))))
            except (ValueError, IndexError):
                pass  # Optional display claims; older files may use opaque tokens.
        sources.extend([access.get('https://api.openai.com/profile'), access])
        def field(key):
            for source in sources:
                value = source.get(key) if isinstance(source, dict) else None
                if (isinstance(value, str) and 0 < len(value.strip()) <= 320 and
                        value not in auth['tokens'].values() and value == self.redact(value)
                        and all(unicodedata.category(c) not in ('Cc', 'Cs') for c in value)):
                    return value.strip()
            return None
        return {'accountName': field('name'), 'email': field('email')}

    def read(self, path):
        if path.stat().st_size > 65536:
            raise ProfileError('AUTH_TOO_LARGE', 'Authorization file exceeds 64 KiB')
        raw, auth = self.m.read(path)
        self.secrets.update(v for v in auth['tokens'].values() if isinstance(v, str) and v)
        return raw, auth

    def redact(self, text):
        for secret in sorted(self.secrets, key=len, reverse=True):
            text = text.replace(secret, '[REDACTED]')
        text = re.sub(r'(?i)(Bearer\s+)[^\s"\\]+', r'\1[REDACTED]', text)
        return re.sub(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', '[REDACTED_JWT]', text)

    def error(self, error, _ancestors=frozenset()):
        # Cleanup exceptions can refer back to the primary exception via Python's
        # implicit context while also being attached as suppressed diagnostics.
        if id(error) in _ancestors:
            return {'type': type(error).__name__, 'reference': 'ancestor_exception', 'cycle': True}
        if len(_ancestors) >= 16:
            return {'type': type(error).__name__, 'truncated': True, 'reason': 'cause_depth_limit'}
        ancestors = _ancestors | {id(error)}
        result = {'type': type(error).__name__, 'message': str(error),
                  'code': getattr(error, 'code', None), 'stage': getattr(error, 'profile_stage', self.stage),
                  'stack': ''.join(traceback.format_exception(type(error), error, error.__traceback__))}
        if isinstance(error, self.m.RpcError):
            result['rpc'] = error.response
        if isinstance(error, OSError):
            result['errno'] = error.errno
        if error.__cause__:
            result['cause'] = self.error(error.__cause__, ancestors)
        elif error.__context__ and not error.__suppress_context__:
            result['context'] = self.error(error.__context__, ancestors)
        if hasattr(error, 'runtime_diagnostic'):
            result['runtime'] = error.runtime_diagnostic
        if hasattr(error, 'suppressed'):
            result['suppressed'] = [self.error(e, ancestors) for e in error.suppressed]
        # JSONDecodeError may embed a document in repr/context in future runtimes.
        # Python's formatted exception contains only the parse position, not .doc.
        return json.loads(self.redact(json.dumps(result)))

    def token_claims(self, auth):
        claims = {}
        token = auth['tokens']['access_token']
        try:
            part = token.split('.')[1]
            claims = json.loads(base64.urlsafe_b64decode(part + '=' * (-len(part) % 4)))
            if not isinstance(claims, dict):
                raise ValueError('JWT payload is not an object')
        except (ValueError, IndexError) as error:
            raise ProfileError('INVALID_TOKEN_METADATA', 'Cannot decode access token timestamps') from error
        for field in ('iat', 'exp'):
            value = claims.get(field)
            if value is not None and (type(value) not in (int, float) or not 0 < value < 253402214400):
                raise ProfileError('INVALID_TOKEN_TIME', 'Invalid access token ' + field)
        return claims

    def metadata(self, number, auth):
        path = (self.m.BASE if number == 'current' else self.m.PROFILES / number) / 'auth.json'
        claims = self.token_claims(auth)
        def date(key):
            return iso(claims[key]) if claims.get(key) is not None else None
        stat = path.stat()
        return {'id': number, 'path': str(path), 'revision': f'{stat.st_mtime_ns}:{stat.st_size}',
                'displayName': self.label(number), **self.account_display(auth, claims),
                'modifiedAt': iso(stat.st_mtime), 'lastRefresh': auth.get('last_refresh'),
                'accessIssuedAt': date('iat'), 'accessExpiresAt': date('exp'),
                'accessTokenState': 'unknown' if claims.get('exp') is None else (
                    'expired' if claims['exp'] <= time.time() else 'valid_by_timestamp'),
                'credentialValidity': 'not_verified'}

    def list(self):
        self.stage = 'list_profiles'
        active_auth = self.read(self.m.BASE / 'auth.json')[1]
        active = self.m.identity(active_auth)
        rows = []
        numbers = self.registered()
        for number in numbers:
            path = self.m.PROFILES / number / 'auth.json'
            if not path.exists():
                continue
            auth = self.read(path)[1]
            if self.m.identity(auth) != active:
                rows.append(self.metadata(number, auth))
        restarts = RestartOperations(self)
        records = restarts.records()
        history = restarts.history(records=records)
        latest = history[0] if history else None
        restart = self.legacy_restart(active, numbers)
        if restart:
            history = [*history[:DISPLAY_LIMIT - 1], restart]
        if latest:
            restart = latest
        restart_status = restart['status'] if restart else None
        restart_error = restart.get('failure') if restart else None
        result = {'status': 'available', 'profiles': rows, 'supportsTokenRefresh': True, 'managedBy': 'remodex', 'supportsImportExport': True,
                'supportsManualRestart': restarts.supported(),
                'currentProfile': self.metadata('current', active_auth) if self.socket or self.owned else None,
                'restartStatus': 'restart_scheduled' if restart_status in PENDING else 'ready' if restart_status == 'recovered' else restart_status,
                'operationId': restart.get('operationId') if restart else None,
                'selectedProfile': (restart.get('profile') or restart.get('expectedProfile')) if restart else None,
                'restartError': json.dumps(restart_error, ensure_ascii=False)[:16000] if restart_error else None,
                'restartHistory': history}
        self.stage = 'restart_capability'
        try:
            capability = restarts.capability()
        except Exception as error:
            capability = {'mode': 'unknown', 'diagnostic': restarts.diagnostic(error)}
        result.update(runtimeControl=capability, supportsManualRestart=capability.get('action') in ('start', 'restart'),
                      supportsRestartProgress=True)
        from auth_runtime import reconcile_failed_switch
        stopped_key = None
        if latest and latest.get('kind') == 'manual' and latest.get('status') == 'failed' and latest.get('stage') == 'direct_stop':
            stopped_key = records[0].get('_directKey')
        return reconcile_failed_switch(self, result, active_auth, numbers, stopped_key)

    def legacy_restart(self, active, numbers):
        switch = self.m.STATE / 'last-switch.json'
        restart = self.m.STATE / 'last-restart.json'
        if not switch.exists():
            return None
        selection = json.loads(switch.read_text())
        if not selection.get('unit'):
            return None
        report = json.loads(restart.read_text()) if restart.exists() and restart.stat().st_mtime_ns >= switch.stat().st_mtime_ns else {}
        if report.get('operationId') and report['operationId'] != selection.get('unit'):
            report = {}
        restart_status = report.get('status', 'restart_scheduled')
        failure = self.bound_restart_diagnostic(json.loads(self.redact(json.dumps(report)))) if restart_status in ('failed', 'verification_failed') else None
        if not report and time.time() - switch.stat().st_mtime > 180:
            restart_status = 'failed'
            failure = {'stage': 'restart_schedule', 'code': 'RESTART_QUEUE_EXPIRED', 'message': 'Legacy restart has no completion record'}
        selected = selection.get('profile')
        matches_file = active is not None and selected in numbers and self.m.identity(self.read(self.m.PROFILES / selected / 'auth.json')[1]) == active
        if active is not None and selected in numbers and not matches_file:
            restart_status = 'superseded_by_newer_switch'
            failure = {'stage': 'selection-verification', 'message': 'Active authorization no longer matches this switch operation',
                       'previousFailure': failure}
        return dict(operationId=selection.get('unit'), kind='switch', profile=selected, status=restart_status,
                    stage=report.get('stage') or (failure or {}).get('stage'), requestedAt=selection.get('time_beijing'),
                    finishedAt=report.get('time_beijing'), failure=failure, legacy=True, selectedOnDisk=matches_file)

    def status(self):
        active = self.read(self.m.BASE / 'auth.json')[1]
        state = self.m.state_read()
        rows = []
        for number in self.registered():
            auth = self.read(self.m.PROFILES / number / 'auth.json')[1]
            row = self.metadata(number, auth)
            row['active'] = self.m.identity(auth) == self.m.identity(active)
            row['lastMaintenance'] = state.get('profiles', {}).get(number, {}).get('last_result')
            rows.append(row)
        return {'managedBy': 'remodex', 'profiles': rows, 'nextProfileId': self.next_number(),
                'schedule': state.get('remodexSchedule'),
                'activeMaintenance': json.loads((self.m.STATE / 'last-active-maintenance.json').read_text()) if (self.m.STATE / 'last-active-maintenance.json').exists() else None,
                'scheduler': json.loads((self.m.STATE / 'remodex-scheduler.json').read_text()) if (self.m.STATE / 'remodex-scheduler.json').exists() else None,
                'currentProfile': self.metadata('current', active)}

    def check(self, number, revision):
        path = (self.m.BASE if number == 'current' else self.m.PROFILES / number) / 'auth.json'
        if number != 'current' and number not in self.registered():
            raise ProfileError('INVALID_PROFILE', 'Unknown authorization')
        auth = self.read(path)[1]
        metadata = self.metadata(number, auth)
        if metadata['revision'] != revision:
            raise ProfileError('PROFILE_CHANGED', 'Authorization changed; reload before continuing')
        return metadata

    def rename(self, number, revision, name, expected_name):
        self.stage = 'rename_profile'
        self.check(number, revision)
        if number == 'current' or self.label(number) != (expected_name or None):
            raise ProfileError('PROFILE_CHANGED', 'Profile name changed; reload before editing')
        self.m.save_json(self.m.PROFILES / number / 'label.json', {'name': self.clean_label(name)})
        return {'status': 'renamed', 'profile': number}

    def remove(self, number, revision):
        self.stage = 'remove_profile'
        self.target(number, revision)  # Includes active-account protection.
        archive = self.m.STATE / 'removed'
        archive.mkdir(mode=0o700, exist_ok=True)
        destination = archive / number
        if destination.exists():
            destination = archive / (number + '-' + str(uuid.uuid4()))
        os.rename(self.m.PROFILES / number, destination)
        self.m.sync_directory(archive)
        self.m.sync_directory(self.m.STATE)
        self.m.sync_directory(self.m.PROFILES)
        return {'status': 'removed', 'profile': number}

    def import_profile(self, number, revision, source, name=None):
        self.stage = 'import_validate'
        if not self.m.valid_number(number):
            raise ProfileError('INVALID_PROFILE', 'Invalid authorization ID')
        directory = self.m.PROFILES / number
        if directory.is_symlink() or (directory / 'auth.json').is_symlink():
            raise ProfileError('INVALID_PROFILE', 'Authorization links are not supported')
        if name is not None and (revision != 'missing' or directory.exists()):
            raise ProfileError('PROFILE_CHANGED', 'Authorization already exists; reload before adding')
        if revision == 'missing' and (self.m.STATE / 'removed' / number).exists():
            raise ProfileError('PROFILE_CHANGED', 'Authorization was removed; reload before adding')
        upload = Path(source)
        root = (self.m.BASE / 'remodex_uploads').resolve()
        if not upload.is_absolute() or not upload.resolve().is_relative_to(root) or upload.name == '.upload-part':
            raise ProfileError('IMPORT_SOURCE_NOT_ALLOWED', 'Select a completed protected workspace upload')
        # symlinks and nonregular files are rejected by the storage reader.
        raw, auth = self.read(upload)
        self.token_claims(auth)
        self.secrets.update(v for v in auth['tokens'].values() if isinstance(v, str))
        destination = self.m.PROFILES / number / 'auth.json'
        before = destination.read_bytes() if destination.exists() else None
        if before is None:
            if revision != 'missing':
                raise ProfileError('PROFILE_CHANGED', 'Authorization no longer exists')
        else:
            self.target(number, revision)
        # Preserve the former file for recovery without including it in metadata or logs.
        self.stage = 'import_commit'
        if before is not None:
            backup = self.m.STATE / 'imports' / str(uuid.uuid4()) / 'auth.json'
            self.m.atomic(backup, before)
        if (destination.read_bytes() if destination.exists() else None) != before:
            raise ProfileError('PROFILE_CHANGED', 'Authorization changed during import')
        if name is not None:
            label = self.clean_label(name)
            with tempfile.TemporaryDirectory(dir=self.m.PROFILES) as tmp:
                staged = Path(tmp) / 'profile'
                self.m.atomic(staged / 'auth.json', raw)
                self.m.save_json(staged / 'label.json', {'name': label})
                staged.rename(directory)
                self.m.sync_directory(self.m.PROFILES)
        else:
            self.m.atomic(destination, raw)
        return {'status': 'imported', 'profile': number, 'profileInfo': self.metadata(number, auth)}

    def export(self, number, revision):
        self.stage = 'export_validate'
        self.check(number, revision)
        path = (self.m.BASE if number == 'current' else self.m.PROFILES / number) / 'auth.json'
        active_data, active = self.read(self.m.BASE / 'auth.json')
        if number != 'current' and self.m.identity(self.read(path)[1]) == self.m.identity(active):
            self.m.sync_active_snapshot(path, active_data, active)
        raw, _ = self.read(path)
        # Immutable, private snapshots use the existing paired workspace file transfer.
        work = Path(tempfile.mkdtemp(prefix='export-', dir=self.m.STATE))
        destination = work / 'auth.json'
        self.m.atomic(destination, raw)
        return {'profile': number, 'path': str(destination), 'cwd': str(work),
                'fileName': 'auth.json', 'byteLength': len(raw)}

    def sync_current_snapshots(self, dry=False):
        self.stage = 'sync_current_snapshots'
        raw, active = self.read(self.m.BASE / 'auth.json')
        results = []
        for number in self.registered():
            path = self.m.PROFILES / number / 'auth.json'
            if self.m.identity(self.read(path)[1]) == self.m.identity(active):
                results.append({'profile': number, 'snapshot': self.m.sync_active_snapshot(path, raw, active, dry=dry)})
        return results

    def maintain_current(self, dry=False):
        """Minute check: synchronize Codex's new files; renew only near expiry.

        Never inject a library token into the daemon or combine access/refresh
        tokens from different versions. A newer/ambiguous saved credential needs
        reconciliation, not an attempted rotation of a potentially stale token.
        """
        now = time.time()
        report = {'checkedAt': iso(now), 'refreshBeforeExpirySeconds': 900}
        try:
            report['snapshots'] = self.sync_current_snapshots(dry)
            _, active = self.read(self.m.BASE / 'auth.json')
            exp = self.token_claims(active).get('exp')
            if any(row['snapshot'] in ('kept_newer_saved', 'version_order_unknown') for row in report['snapshots']):
                report['status'] = 'version_conflict'
            elif exp is None:
                report['status'] = 'expiry_unknown'
            elif exp > now + 900:
                report['status'] = 'healthy'
            elif not self.socket:
                raise ProfileError('RUNTIME_UNAVAILABLE', 'Active refresh requires the connected Codex control socket')
            else:
                self.stage = 'active_fallback_select'
                state = self.m.state_read()
                key = hashlib.sha256(json.dumps(self.m.identity(active)).encode()).hexdigest()
                entry = state.setdefault('activeFallback', {}).setdefault(key, {})
                day = self.m.dt.datetime.fromtimestamp(now, self.m.BEIJING).date().isoformat()
                if entry.get('attemptDay') == day:
                    report['status'] = 'already_attempted_today'
                    report['lastAttempt'] = entry.get('lastAttempt')
                    report['lastResult'] = entry.get('lastResult')
                elif dry:
                    report['status'] = 'would_refresh_current'
                else:
                    # Survives crashes/restarts; never retry a failed rotation on
                    # every minute tick. Manual refresh remains explicit.
                    entry.update(attemptDay=day, lastAttempt=iso(now))
                    self.m.save_json(self.m.STATE / 'state.json', state)
                    try:
                        result = self.refreshToken('current', self.metadata('current', active)['revision'])
                        report.update(status='refreshed', snapshots=result['snapshots'])
                        entry['lastResult'] = {'status': 'refreshed', 'at': iso(time.time())}
                    except Exception as error:
                        report.update(status='failed', diagnostic=bounded_diagnostic(self.error(error)))
                        entry['lastResult'] = {'status': 'failed', 'diagnostic': report['diagnostic']}
                    try:
                        self.m.save_json(self.m.STATE / 'state.json', state)
                    except Exception as storage_error:
                        if report.get('status') != 'failed':
                            raise
                        report['diagnostic'].setdefault('suppressed', []).append(self.error(storage_error))
        except Exception as error:
            report.update(status='failed', diagnostic=bounded_diagnostic(self.error(error)))
        if not dry:
            try:
                self.m.save_json(self.m.STATE / 'last-active-maintenance.json', report)
            except Exception as storage_error:
                if report.get('status') != 'failed':
                    raise
                report['diagnostic'].setdefault('suppressed', []).append(self.error(storage_error))
        return report

    def maintenance(self, dry=False, force=False):
        self.stage = 'maintenance'
        active_result = self.maintain_current(dry)
        now = time.time()
        today = self.m.dt.datetime.fromtimestamp(now, self.m.BEIJING)
        state = self.m.state_read()
        rows = []
        next_check = today.replace(hour=4, minute=0, second=0, microsecond=0)
        if today.hour >= 4:
            next_check += self.m.dt.timedelta(days=1)
        prior = state.get('remodexSchedule', {})
        if not force and (today.hour < 4 or prior.get('completedDay') == today.date().isoformat()):
            return {'status': 'waiting', 'managedBy': 'remodex', 'nextCheck': self.m.timestamp(next_check.timestamp()), 'activeMaintenance': active_result}
        for number in self.registered():
            try:
                self.stage = 'maintenance_select'
                active_data, active = self.read(self.m.BASE / 'auth.json')
                path = self.m.PROFILES / number / 'auth.json'
                auth = self.read(path)[1]
                entry = state['profiles'].setdefault(number, {})
                account_key = hashlib.sha256(json.dumps(self.m.identity(auth)).encode()).hexdigest()
                account_entry = state.setdefault('accounts', {}).setdefault(account_key, {})
                # Honor the old per-slot records as well as the account-wide records.
                merged = {k: max(entry.get(k) or 0, account_entry.get(k) or 0) for k in ('last_attempt', 'last_success')}
                due, _ = self.m.daily_due(merged, auth, now)
                if self.m.identity(auth) == self.m.identity(active):
                    result = {'profile': number, 'status': 'skipped_active',
                              'snapshot': self.m.sync_active_snapshot(path, active_data, active, dry=dry)}
                elif not due and not force:
                    result = {'profile': number, 'status': 'already_refreshed_or_attempted_today'}
                elif dry:
                    result = {'profile': number, 'status': 'would_refresh'}
                else:
                    entry['last_attempt'] = account_entry['last_attempt'] = now
                    self.m.save_json(self.m.STATE / 'state.json', state)
                    self.refreshToken(number, self.metadata(number, auth)['revision'])
                    result = {'profile': number, 'status': 'refreshed'}
                    entry['last_success'] = account_entry['last_success'] = time.time()
                entry['last_result'] = result
            except Exception as error:
                result = {'profile': number, 'status': 'failed', 'diagnostic': bounded_diagnostic(self.error(error))}
                state['profiles'].setdefault(number, {})['last_result'] = result
            rows.append(result)
            if not dry:
                self.m.save_json(self.m.STATE / 'state.json', state)
        report = {'status': 'completed', 'managedBy': 'remodex', 'results': rows, 'checkedAt': iso(now),
                  'activeMaintenance': active_result,
                  'nextCheck': self.m.timestamp(next_check.timestamp()), 'completedDay': today.date().isoformat()}
        if not dry:
            state['remodexSchedule'] = {k: v for k, v in report.items() if k not in ('results', 'activeMaintenance')}
            self.m.save_json(self.m.STATE / 'state.json', state)
            self.m.save_json(self.m.STATE / 'last-remodex-run.json', report)
        return report

    def target(self, number, revision):
        if number not in self.registered():
            raise ProfileError('INVALID_PROFILE', 'Expected a profile registered with the host manager')
        path = self.m.PROFILES / number / 'auth.json'
        raw, auth = self.read(path)
        if self.m.identity(auth) == self.m.identity(self.read(self.m.BASE / 'auth.json')[1]):
            raise ProfileError('PROFILE_ACTIVE', 'This account is already selected; refresh the list')
        if self.metadata(number, auth)['revision'] != revision:
            raise ProfileError('PROFILE_CHANGED', 'Authorization file changed; refresh the list')
        return path, raw, auth

    def switch(self, number, revision):
        self.stage = 'switch_selection'
        self.check(number, revision)
        return RestartOperations(self).schedule(number, revision)

    def verify_refresh(self, before, after):
        if self.m.identity(before) != self.m.identity(after):
            raise ProfileError('IDENTITY_CHANGED', 'The authorization account changed during refresh')
        if before['tokens']['access_token'] == after['tokens']['access_token'] and before['tokens']['refresh_token'] == after['tokens']['refresh_token']:
            raise ProfileError('TOKEN_REFRESH_UNCONFIRMED', 'Codex did not persist a renewed token')
        if (self.m.expiry(after) or 0) <= time.time():
            raise ProfileError('TOKEN_REFRESH_EXPIRED', 'Refreshed access token has no valid future expiry')

    def refreshToken(self, number, revision):
        if number != 'current':
            _, _, auth = self.target(number, revision)
            state = self.m.state_read()
            key = hashlib.sha256(json.dumps(self.m.identity(auth)).encode()).hexdigest()
            now = time.time()
            state['profiles'].setdefault(number, {})['last_attempt'] = now
            state.setdefault('accounts', {}).setdefault(key, {})['last_attempt'] = now
            self.m.save_json(self.m.STATE / 'state.json', state)
            return self.run_isolated(number, revision, refresh_token=True)
        if not self.socket:
            raise ProfileError('RUNTIME_UNAVAILABLE', 'The connected daemon has no local control socket')
        from websockets.sync.client import unix_connect
        self.stage = 'refresh_current_validate'
        path = self.m.BASE / 'auth.json'
        before, auth = self.read(path)
        if self.metadata('current', auth)['revision'] != revision:
            raise ProfileError('PROFILE_CHANGED', 'Current authorization changed; refresh the list')
        self.stage = 'refresh_current_connect'
        with unix_connect(self.socket, open_timeout=8, close_timeout=2) as ws:
            def call(i, method, params):
                ws.send(json.dumps({'id': i, 'method': method, 'params': params}))
                until = time.monotonic() + 60
                while True:
                    message = json.loads(ws.recv(timeout=max(0, until - time.monotonic())))
                    if message.get('id') == i:
                        if 'error' in message:
                            raise self.m.RpcError(message['error'])
                        return message['result']
            call(1, 'initialize', {'clientInfo': {'name': 'remodex_manual_refresh', 'version': '1.0'}})
            ws.send(json.dumps({'method': 'initialized', 'params': {}}))
            self.stage = 'refresh_current_identity'
            token = call(2, 'getAuthStatus', {'refreshToken': False, 'includeToken': True}).get('authToken')
            if token != auth['tokens']['access_token'] or path.read_bytes() != before:
                raise ProfileError('PROFILE_CHANGED', 'The live daemon and selected authorization do not match; reload account status')
            # An explicit manual attempt also prevents an immediate automatic
            # retry of the same account later in this Beijing calendar day.
            state = self.m.state_read()
            now = time.time()
            key = hashlib.sha256(json.dumps(self.m.identity(auth)).encode()).hexdigest()
            state.setdefault('activeFallback', {}).setdefault(key, {}).update(
                attemptDay=self.m.dt.datetime.fromtimestamp(now, self.m.BEIJING).date().isoformat(), lastAttempt=iso(now))
            self.m.save_json(self.m.STATE / 'state.json', state)
            self.stage = 'refresh_current_request'
            call(3, 'getAuthStatus', {'refreshToken': True, 'includeToken': False})
        self.stage = 'refresh_current_verify'
        updated = self.read(path)[1]
        self.verify_refresh(auth, updated)
        snapshots = self.sync_current_snapshots()
        if any(row['snapshot'] in ('kept_newer_saved', 'version_order_unknown') for row in snapshots):
            raise ProfileError('CURRENT_SYNC_CONFLICT', 'Codex persisted refreshed credentials, but a saved credential version could not safely be replaced; both files are retained')
        return {'profile': number, 'status': 'refreshed', 'profileInfo': self.metadata(number, updated), 'snapshots': snapshots}

    def query(self, number, revision):
        if self.socket:
            ops = RestartOperations(self)
            records = ops.records()
            latest = records[0] if records else self.legacy_restart(None, ())
        else:
            latest = None
        if latest and latest['status'] in ('failed', 'verification_failed'):
            from auth_runtime import read_runtime_token, token_matches_profile, runtime_quota
            _, _, auth = self.target(number, revision)
            self.stage = 'query_runtime_identity'
            if token_matches_profile(self, read_runtime_token(self, self.socket), auth):
                result = runtime_quota(self, auth)
                if result['profile'] != number:
                    raise ProfileError('PROFILE_CHANGED', 'Runtime quota cannot be attributed to a unique profile')
                return dict(result, profileInfo=self.metadata(number, auth))
        return self.run_isolated(number, revision, refresh_token=False)

    def run_isolated(self, number, revision, *, refresh_token):
        action = 'refresh' if refresh_token else 'query'
        self.stage = action + '_validate'
        path, before, auth = self.target(number, revision)
        if len(list(self.m.STATE.glob('phone-quota-*/auth.json'))) >= 5:
            raise ProfileError('RECOVERY_REQUIRED', 'Resolve retained query credentials before querying again')
        work = Path(tempfile.mkdtemp(prefix='phone-quota-', dir=self.m.STATE))
        rpc = None
        failure = None
        result = None
        def secondary(error):
            nonlocal failure
            error.profile_stage = self.stage
            if failure is None:
                failure = error
            else:
                failure.suppressed = getattr(failure, 'suppressed', []) + [error]
        try:
            self.m.atomic(work / 'auth.json', before)
            (work / 'config.toml').write_text('cli_auth_credentials_store = "file"\n')
            self.stage = action + '_initialize'
            rpc = self.m.RPC(work)
            rpc.call(1, 'initialize', {'clientInfo': {'name': 'remodex_auth_profiles', 'version': '1.0'},
                                     'capabilities': {'experimentalApi': True}})
            rpc.send({'method': 'initialized', 'params': {}})
            self.stage = action + '_account'
            if refresh_token:
                # Desktop maintenance uses the same lock; also guard external file edits.
                self.target(number, revision)
                if path.read_bytes() != before:
                    raise ProfileError('PROFILE_CHANGED', 'Authorization changed before refresh')
                rpc.call(2, 'getAuthStatus', {'refreshToken': True, 'includeToken': False})
                self.stage = 'refresh_verify'
                self.verify_refresh(auth, self.read(work / 'auth.json')[1])
                result = {'profile': number, 'status': 'refreshed'}
            else:
                account = rpc.call(2, 'account/read', {'refreshToken': False}).get('account') or {}
                self.stage = 'query_rate_limits'
                fallback = None
                try:
                    quota = rpc.call(3, 'account/rateLimits/read', {'excludeResetCreditDetails': False})
                except self.m.RpcError as first:
                    response = first.response
                    message = str(response.get('message', '')).lower()
                    if response.get('code') not in (-32602, -32600) or not any(s in message for s in ('invalid param', 'failed to parse', 'expected', 'missing field')):
                        raise
                    fallback = self.error(first)
                    try:
                        quota = rpc.call(4, 'account/rateLimits/read', {})
                    except Exception as error:
                        error.suppressed = [first]
                        raise
                result = {'profile': number, 'queriedAt': iso(time.time()),
                          'quota': {key: quota[key] for key in ('rateLimits', 'rateLimitsByLimitId', 'rateLimitResetCredits') if key in quota},
                          'email': account.get('email'), 'plan': account.get('planType')}
                if fallback:
                    result['compatibilityFallback'] = fallback
        except Exception as error:
            error.profile_stage = self.stage
            failure = error
        finally:
            failed_stage = self.stage
            self.stage = action + '_cleanup'
            if rpc:
                try:
                    rpc.close()
                except Exception as error:
                    secondary(error)
            # Preserve automatically rotated credentials even when the quota call failed.
            # Never modify active auth; retain the private recovery directory on conflicts.
            try:
                if (work / 'auth.json').exists():
                    after, updated = self.read(work / 'auth.json')
                    if after != before:
                        if self.m.identity(updated) != self.m.identity(auth):
                            raise ProfileError('IDENTITY_CHANGED', 'Query account changed; credentials retained at ' + str(work))
                        if path.read_bytes() != before:
                            raise ProfileError('PROFILE_CHANGED', 'Profile changed concurrently; credentials retained at ' + str(work))
                        if self.m.identity(self.read(self.m.BASE / 'auth.json')[1]) == self.m.identity(updated):
                            raise ProfileError('PROFILE_ACTIVE', 'Profile became active; credentials retained at ' + str(work))
                        if refresh_token:
                            self.verify_refresh(auth, updated)
                        self.m.atomic(path, after)
                shutil.rmtree(work)
            except Exception as error:
                secondary(error)
            if rpc:
                try:
                    diagnostic = self.redact(rpc.errors.decode(errors='replace'))
                    if rpc.truncated:
                        diagnostic += '\n[TRUNCATED]\n'
                    if failure:
                        failure.runtime_diagnostic = {
                            'stderr': diagnostic, 'truncated': rpc.truncated,
                            'httpStatuses': re.findall(r'Failed to refresh token status=(\d{3})', diagnostic),
                        }
                    self.m.atomic(self.m.STATE / f"profile-{number}-phone-{'refresh' if refresh_token else 'quota'}.stderr.log", diagnostic.encode())
                except Exception as error:
                    secondary(error)
            self.stage = failed_stage if failure else action + '_complete'
        if failure:
            raise failure
        result['profileInfo'] = self.metadata(number, self.read(path)[1])
        if refresh_token:
            return result
        self.stage = 'query_record'
        if len(json.dumps(result).encode()) > 512 * 1024:
            raise ProfileError('QUOTA_PAYLOAD_TOO_LARGE', 'Quota response exceeds the 512 KiB result limit')
        self.m.save_json(self.m.STATE / f'profile-{number}-phone-quota.json', result)
        return result


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('--manager', help=argparse.SUPPRESS)  # Rolling deployment: old bridges passed this; storage is always bundled.
    parser.add_argument('--source')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--force', action='store_true')
    parser.add_argument('--home', required=True)
    parser.add_argument('action', choices=('list', 'status', 'check', 'import', 'rename', 'remove', 'export', 'maintenance', 'query', 'refreshToken',
                                           'runtime-quota', 'switch', 'restart', 'restart-progress', 'adopt', 'restart-history', 'run-restart'))
    parser.add_argument('--name')
    parser.add_argument('--expected-name')
    parser.add_argument('--socket')
    parser.add_argument('--bridge-child', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--wait-for-restart', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--profile')
    parser.add_argument('--revision')
    parser.add_argument('--operation-id')
    parser.add_argument('--confirmation')
    parser.add_argument('--start-if-stopped', action='store_true')
    args = parser.parse_args()
    if args.action == 'run-restart' and not args.operation_id:
        parser.error('run-restart requires --operation-id')
    if args.start_if_stopped and args.action != 'restart':
        parser.error('--start-if-stopped requires restart')
    import auth_store as manager
    manager.configure(args.home)
    api = Profiles(manager, args.socket)
    try:
        if args.bridge_child:
            from auth_owned_child import OwnedChild
            api.owned = OwnedChild(manager)
        if args.wait_for_restart:
            if args.action != 'run-restart':
                parser.error('--wait-for-restart requires run-restart')
            from auth_restarts import DELAY
            time.sleep(DELAY)
        if manager.BASE.resolve() != Path(args.home).resolve():
            raise ProfileError('RUNTIME_MISMATCH', 'Auth manager belongs to a different Codex home')
        manager.STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
        if args.action == 'runtime-quota':
            if not api.socket and not api.owned:
                raise ProfileError('RUNTIME_UNAVAILABLE', 'Live quota requires the local Codex control socket')
            from auth_runtime import runtime_quota
            print(json.dumps({'ok': True, 'result': runtime_quota(api)}))
            return
        if args.action == 'restart-progress':
            ops = RestartOperations(api)
            with (manager.STATE / 'manager.lock').open('a') as lock:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    result = [ops.public(r) for r in ops.records()[:DISPLAY_LIMIT]]
                else:
                    result = ops.history()
            print(json.dumps({'ok': True, 'result': {'restartHistory': result}}))
            return
        with (manager.STATE / 'manager.lock').open('a') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX if args.action == 'run-restart' else fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise ProfileError('MANAGER_BUSY', 'Account maintenance is running; try again later') from error
            if args.action in ('list', 'status'):
                result = getattr(api, args.action)()
            elif args.action == 'adopt':
                if not args.confirmation or not re.fullmatch('[a-f0-9]{64}', args.confirmation):
                    raise ProfileError('CONFIRMATION_REQUIRED', 'Review and confirm the running listener first')
                result = RestartOperations(api).schedule(adoption=args.confirmation)
            elif args.action == 'restart':
                result = RestartOperations(api).schedule(allow_stopped=args.start_if_stopped, expected_runtime=args.confirmation)
            elif args.action == 'restart-history':
                history = RestartOperations(api).history(HISTORY_LIMIT)
                legacy = api.legacy_restart(None, ())
                result = {'restartHistory': [*history[:HISTORY_LIMIT - 1], legacy] if legacy else history}
            elif args.action == 'run-restart':
                result = RestartOperations(api).run(args.operation_id)
            elif args.action == 'maintenance':
                result = api.maintenance(args.dry_run, args.force)
            elif args.action == 'import':
                result = api.import_profile(args.profile, args.revision, args.source, args.name)
            elif args.action == 'rename':
                result = api.rename(args.profile, args.revision, args.name, args.expected_name)
            else:
                result = getattr(api, args.action)(args.profile, args.revision)
            if api.owned and args.action in ('switch', 'restart') and result['status'] == 'restart_scheduled':
                from auth_restarts import DELAY
                print(json.dumps({'ok': True, 'result': result}), flush=True)
                time.sleep(DELAY)
                # Same lock, same bridge connection. The original acknowledgement
                # is already on the phone; progress and failures are durable.
                RestartOperations(api).run(result['operationId'])
                return
        print(json.dumps({'ok': True, 'result': result}))
        if args.action == 'run-restart' and result and result['status'] == 'failed':
            raise SystemExit(1)
    except Exception as error:
        report = api.error(error)
        report.update(operation=args.action, profile=args.profile, at=iso(time.time()))
        report = bounded_diagnostic(report)
        if report.get('code') != 'MANAGER_BUSY':
            try:
                manager.save_json(manager.STATE / 'last-phone-error.json', report)
            except Exception as secondary:
                report.setdefault('suppressed', []).append(api.error(secondary))
        print(json.dumps({'ok': False, 'error': bounded_diagnostic(report)}))


if __name__ == '__main__':
    def timeout(signum, frame):
        raise TimeoutError('Authorization manager exceeded its 900 second deadline')
    signal.signal(signal.SIGALRM, timeout)
    signal.alarm(900)
    main()
