"""Durable, serialized Codex restarts for the local authorization manager."""

import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time


PENDING = ('restart_scheduled', 'running')
DELAY = 10
QUEUE_TIMEOUT = 75
HISTORY_LIMIT = 30
DISPLAY_LIMIT = 10
OPERATION_ID = re.compile(r'codex-auth-restart-[0-9]+')
PROXY_ENV = ('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy')


class RestartError(RuntimeError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


class RestartOperations:
    # Calls run under the same manager lock as authorization writes.
    def __init__(self, api):
        self.api = api
        self.m = api.m
        self.directory = self.m.STATE / 'restarts'
        self.canonical_socket = self.m.BASE / 'app-server-control/app-server-control.sock'
        self.auth_path = self.m.BASE / 'auth.json'

    def save(self, record):
        self.m.save_json(self.directory / (record['operationId'] + '.json'), record)

    def records(self):
        return [json.loads(path.read_text()) for path in sorted(self.directory.glob('codex-auth-restart-*.json'), reverse=True)
                if OPERATION_ID.fullmatch(path.stem)]

    def finish(self, record, status):
        record.update(status=status, finishedAt=self.m.timestamp(time.time()))
        self.save(record)

    def interrupted(self, record):
        if record['status'] == 'running' or (record['status'] == 'restart_scheduled' and time.time() >= record['_expires']):
            self.api.stage = record['stage']
            code = 'RESTART_QUEUE_EXPIRED'
            if record['status'] == 'running':
                code = 'RESTART_WORKER_INTERRUPTED' if record.get('startedAt') else 'RESTART_PREFLIGHT_INTERRUPTED'
            if record.get('_hasRollbackSnapshot'):
                record['recovery'] = {'status': 'verification_required', 'previousAuthorizationRetained': True}
            self.fail(record, RestartError(code, 'Restart worker did not finish; verify the runtime before retrying'))

    def diagnostic(self, error):
        self.api.secrets.update(os.environ[name] for name in PROXY_ENV if os.environ.get(name))
        report = self.api.error(error)
        if isinstance(error, (subprocess.CalledProcessError, subprocess.TimeoutExpired)):
            for field in ('stdout', 'stderr'):
                value = getattr(error, field, None)
                if isinstance(value, bytes):
                    value = value.decode(errors='replace')
                if value:
                    sanitized = self.api.redact(value)[:8193]
                    report[field] = sanitized[:8192]
                    if len(sanitized) > 8192:
                        report['truncated'] = True
            if isinstance(error, subprocess.CalledProcessError):
                report['exitCode'] = error.returncode
            else:
                report['timeoutSeconds'] = error.timeout
        return self.api.bound_restart_diagnostic(report)

    def fail(self, record, error):
        record['failure'] = self.diagnostic(error)
        record['stage'] = record['failure']['stage']
        try:
            self.finish(record, 'failed')
        except Exception as secondary:
            error.suppressed = getattr(error, 'suppressed', []) + [secondary]
            raise error

    @staticmethod
    def public(record):
        return {key: value for key, value in record.items() if not key.startswith('_')}

    def history(self, limit=DISPLAY_LIMIT):
        records = self.records()
        if records:
            self.interrupted(records[0])
        return [self.public(record) for record in records[:limit]]

    def supported(self):
        return sys.platform.startswith('linux') and bool(self.api.socket) and Path(self.api.socket) == self.canonical_socket

    def control_socket(self):
        if not self.supported():
            raise RestartError('RESTART_RUNTIME_UNSUPPORTED', 'Restart requires a local Linux managed Codex control socket')
        return self.canonical_socket

    def command(self, action, timeout):
        return subprocess.run([self.m.cli(), 'app-server', 'daemon', action], check=True,
                              capture_output=True, text=True, timeout=timeout,
                              env={**os.environ, 'CODEX_HOME': str(self.m.BASE)})

    def owner(self, allow_stopped=False):
        endpoint = self.control_socket()
        try:
            result = self.command('version', 8)
        except subprocess.CalledProcessError as error:
            if allow_stopped:
                # A failed version request alone cannot establish that the socket is offline.
                with socket.socket(socket.AF_UNIX) as check:
                    check.settimeout(2)
                    try:
                        check.connect(str(endpoint))
                    except (FileNotFoundError, ConnectionRefusedError):
                        return 'start'
                    except OSError as secondary:
                        error.suppressed = [secondary]
            raise error
        version = json.loads(result.stdout)
        if version.get('status') != 'running' or version.get('socketPath') != str(endpoint):
            raise RestartError('RESTART_RUNTIME_MISMATCH', 'The daemon CLI did not confirm the connected Codex instance')
        if not version.get('backend'):
            raise RestartError('RESTART_RUNTIME_UNMANAGED', 'The connected Codex is running without daemon management')
        return 'restart'

    def runtime_matches(self, auth):
        from auth_runtime import read_runtime_token
        token = read_runtime_token(self.api, self.control_socket())
        if not token:
            return False
        if token == auth['tokens']['access_token']:
            return True
        claims = self.m.access_claim({'tokens': {'access_token': token}}, 'https://api.openai.com/auth')
        return isinstance(claims, dict) and claims.get('chatgpt_account_id') == auth['tokens']['account_id']

    def schedule(self, profile=None, revision=None, allow_stopped=False):
        records = self.records()
        previous = records[0] if records else None
        if previous:
            self.interrupted(previous)
            if previous['status'] in PENDING:
                raise RestartError('RESTART_ALREADY_PENDING', 'A Codex restart is already scheduled')
        elif (self.m.STATE / 'last-switch.json').exists():
            _, active = self.api.read(self.auth_path)
            previous = self.api.legacy_restart(self.m.identity(active), self.m.registered_profiles())
            if previous and previous['status'] in PENDING:
                raise RestartError('RESTART_ALREADY_PENDING', 'A previous authorization restart is still scheduled')
        last_sequence = int(records[0]['operationId'].rsplit('-', 1)[-1]) if records else 0
        operation_id = f'codex-auth-restart-{max(time.time_ns(), last_sequence + 1)}'
        record = dict(operationId=operation_id, kind='switch' if profile else 'manual', profile=profile,
                      status='running', stage='restart_preflight', requestedAt=self.m.timestamp(time.time()), attempt=0)
        if not profile and previous and previous['status'] == 'failed':
            record['previousOperationId'] = previous['operationId']
        self.save(record)
        try:
            self.api.stage = 'restart_preflight'
            current, _ = self.api.read(self.auth_path)
            self.owner(allow_stopped)
            record['_currentDigest'] = hashlib.sha256(current).hexdigest()
            if profile:
                self.api.stage = 'switch_selection'
                _, target, _ = self.api.target(profile, revision)
                record['_targetDigest'] = hashlib.sha256(target).hexdigest()
            self.api.stage = 'restart_schedule'
            self.control_socket()
            record.update(status='restart_scheduled', stage=self.api.stage, _expires=time.time() + QUEUE_TIMEOUT,
                          _allowStopped=allow_stopped, restartAfterSeconds=DELAY)
            self.save(record)
            names = (*PROXY_ENV, 'REMODEX_AUTH_CODEX_BIN')
            environment = ['--setenv=' + name + '=' + os.environ[name] for name in names if name in os.environ]
            args = [*environment, '/usr/bin/python3', str(self.m.SCRIPT.with_name('auth-profiles.py')),
                    '--home', str(self.m.BASE), 'run-restart', '--socket', str(self.canonical_socket),
                    '--operation-id', operation_id]
            subprocess.run(['systemd-run', '--unit=' + operation_id, f'--on-active={DELAY}s',
                            '--timer-property=AccuracySec=1s', '--property=UMask=0077', '--property=Type=exec',
                            '--property=ExitType=cgroup', *args], check=True, capture_output=True, text=True, timeout=8)
        except Exception as error:
            self.fail(record, error)
        try:
            for old in records[HISTORY_LIMIT - 1:]:
                (self.directory / (old['operationId'] + '.json')).unlink(missing_ok=True)
        except OSError as error:
            self.api.stage = 'restart_history_cleanup'
            record['cleanupFailure'] = self.diagnostic(error)
            self.save(record)
        return self.public(record)

    def run(self, operation_id):
        records = self.records()
        if not records or records[0]['operationId'] != operation_id:
            return None
        record = records[0]
        if record['status'] != 'restart_scheduled':
            return self.public(record)
        self.interrupted(record)
        if record['status'] != 'restart_scheduled':
            return self.public(record)
        record.update(status='running', startedAt=self.m.timestamp(time.time()))
        before = target = None
        snapshot = self.m.STATE / 'restart-recovery' / operation_id / 'auth.json'
        profile = record['profile']
        try:
            self.phase(record, 'restart_preflight')
            verb = self.owner(record['_allowStopped'])
            self.phase(record, 'restart_selection')
            before, prior = self.api.read(self.auth_path)
            if hashlib.sha256(before).hexdigest() != record['_currentDigest']:
                raise RestartError('RESTART_SELECTION_CHANGED', 'Current authorization changed after the restart was scheduled')
            expected = prior
            if profile:
                if profile not in self.m.registered_profiles():
                    raise RestartError('RESTART_SELECTION_CHANGED', 'The target authorization is no longer registered')
                selected, expected = self.api.read(self.m.PROFILES / profile / 'auth.json')
                if hashlib.sha256(selected).hexdigest() != record['_targetDigest']:
                    raise RestartError('RESTART_SELECTION_CHANGED', 'Target authorization changed after the restart was scheduled')
                if not self.runtime_matches(prior):
                    raise RestartError('RESTART_ACCOUNT_MISMATCH', 'Current authorization differs from the live Codex account')
                self.phase(record, 'switch_write')
                for number in self.m.registered_profiles():
                    path = self.m.PROFILES / number / 'auth.json'
                    if self.m.identity(self.api.read(path)[1]) == self.m.identity(prior):
                        self.m.sync_active_snapshot(path, before, prior)
                self.m.atomic(snapshot, before)
                record['_hasRollbackSnapshot'] = True
                self.save(record)
                if self.auth_path.read_bytes() != before or (self.m.PROFILES / profile / 'auth.json').read_bytes() != selected:
                    raise RestartError('RESTART_SELECTION_CHANGED', 'Authorization changed immediately before the switch')
                target = selected
                self.m.atomic(self.auth_path, target)
            record.update(attempt=1, command=verb)
            self.phase(record, 'daemon_restart' if verb == 'restart' else 'daemon_start')
            self.command(verb, 120)
            self.phase(record, 'runtime_account_verification')
            self.owner()
            if not self.runtime_matches(expected) or self.m.identity(self.api.read(self.auth_path)[1]) != self.m.identity(expected):
                raise RestartError('RESTART_ACCOUNT_MISMATCH', 'Connected Codex did not confirm the expected authorization')
            self.finish(record, 'ready')
        except Exception as error:
            failure = self.diagnostic(error)
            outcome = 'failed'
            if target is not None:
                # A timed out or unverified restart may still complete. Only restore
                # exact bytes we installed, and only when the old account is proven live.
                record['recovery'] = {'status': 'verification_required', 'previousAuthorizationRetained': True}
                try:
                    if self.auth_path.read_bytes() == before:
                        record['recovery'] = {'status': 'original_retained'}
                    elif (record['attempt'] == 0 or isinstance(error, subprocess.CalledProcessError) and self.api.stage == 'daemon_restart'):
                        if self.runtime_matches(expected) and self.m.identity(self.api.read(self.auth_path)[1]) == self.m.identity(expected):
                            outcome = 'recovered'
                            record['recovery'] = {'status': 'runtime_confirmed'}
                        elif self.runtime_matches(prior) and self.auth_path.read_bytes() == target:
                            self.m.atomic(self.auth_path, before)
                            record['recovery'] = {'status': 'rolled_back'}
                except Exception as secondary:
                    self.api.stage = 'restart_recovery'
                    record['recovery']['failure'] = self.diagnostic(secondary)
            record['failure'] = failure
            record['stage'] = failure['stage']
            try:
                self.finish(record, outcome)
            except Exception as secondary:
                error.suppressed = getattr(error, 'suppressed', []) + [secondary]
                raise error
        if record.get('recovery', {}).get('status') != 'verification_required' and snapshot.exists():
            try:
                snapshot.unlink()
                snapshot.parent.rmdir()
            except OSError as error:
                self.api.stage = 'restart_cleanup'
                record['cleanupFailure'] = self.diagnostic(error)
                self.save(record)
        return self.public(record)

    def phase(self, record, stage):
        self.api.stage = record['stage'] = stage
        self.save(record)
