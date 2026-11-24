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

from auth_listener import inspect_direct, restart_direct, retire_direct


PENDING = ('restart_scheduled', 'running')
DELAY = 3
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
        steps = record.setdefault('steps', [])
        state = {'stage': record['stage'], 'status': record['status']}
        if not steps or any(steps[-1][k] != v for k, v in state.items()):
            steps.append(dict(state, at=self.m.timestamp(time.time())))
        self.m.save_json(self.directory / (record['operationId'] + '.json'), record)

    def records(self):
        records = []
        for path in sorted(self.directory.glob('codex-auth-restart-*.json'), reverse=True):
            if OPERATION_ID.fullmatch(path.stem):
                try:
                    records.append(json.loads(path.read_text()))
                except FileNotFoundError:
                    continue  # Completed records may be pruned during a lock-free read.
        return records

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

    def history(self, limit=DISPLAY_LIMIT, records=None):
        if records is None:
            records = self.records()
        if records:
            self.interrupted(records[0])
        return [self.public(record) for record in records[:limit]]

    def supported(self):
        return bool(self.api.owned) or ((sys.platform.startswith('linux') or sys.platform == 'darwin')
                and bool(self.api.socket) and Path(self.api.socket) == self.canonical_socket)

    def control_socket(self):
        if not self.supported():
            raise RestartError('RESTART_RUNTIME_UNSUPPORTED', 'Cannot verify ownership of this Codex connection for restart')
        return self.canonical_socket

    def command(self, action, timeout):
        return subprocess.run([self.m.cli(), 'app-server', 'daemon', action], check=True,
                              capture_output=True, text=True, timeout=timeout,
                              env={**os.environ, 'CODEX_HOME': str(self.m.BASE)})

    def capability(self):
        if not self.supported():
            return {'mode': 'unsupported'}
        if self.api.owned:
            key = self.api.owned.call('child/identity', {})
            raw, _ = self.api.read(self.auth_path)
            return {'mode': 'direct', 'owner': 'bridge', 'action': 'restart',
                    'listenerConfirmation': hashlib.sha256(key.encode() + raw).hexdigest()}
        try:
            verb = self.owner(True)
            if verb == 'start' and not (self.m.BASE / 'packages/app-server-daemon/current/bin/codex').is_file():
                return {'mode': 'offline'}
            if verb == 'launchd':
                observed = self.direct()
                if observed.get('mode') == 'launchd':
                    # Existing clients use "direct" for host-launched Codex.
                    return dict(observed, mode='direct', owner='launchd')
                return observed
            if verb in ('systemd', 'direct'):
                return self.direct()
            return {'mode': 'daemon' if verb == 'restart' else 'stopped', 'action': verb}
        except RestartError as error:
            if error.code == 'RESTART_RUNTIME_UNMANAGED':
                return self.direct()
            raise

    def direct(self):
        if sys.platform == 'darwin':
            from auth_macos import launchd_owner
            return launchd_owner(self.control_socket())
        info = inspect_direct(self.control_socket(), self.m.BASE)
        if info.get('mode') in ('ssh', 'direct') and info.get('action') == 'restart':
            # Consent applies to this listener and exactly the selected on-disk authorization.
            raw, _ = self.api.read(self.auth_path)
            info['listenerConfirmation'] = hashlib.sha256(info['key'].encode() + raw).hexdigest()
        return {k: v for k, v in info.items() if not k.startswith('_')}

    def owner(self, allow_stopped=False):
        if self.api.owned:
            return 'bridge_child'
        endpoint = self.control_socket()
        try:
            result = self.command('version', 8)
        except FileNotFoundError as error:
            if sys.platform == 'darwin':
                try:
                    if self.direct().get('mode') == 'launchd':
                        return 'launchd'
                except (OSError, subprocess.SubprocessError) as secondary:
                    error.suppressed = [secondary]
            raise
        except subprocess.CalledProcessError as error:
            if sys.platform == 'darwin':
                try:
                    if self.direct().get('mode') == 'launchd':
                        return 'launchd'
                except (OSError, subprocess.SubprocessError) as secondary:
                    error.suppressed = [secondary]
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
            info = self.direct()
            if info.get('action') == 'restart' and info.get('mode') in ('ssh', 'direct'):
                return 'direct'
            if info.get('mode') in ('systemd', 'launchd'):
                return info['mode']
            if sys.platform == 'darwin':
                raise RestartError('RESTART_RUNTIME_UNMANAGED', 'The external Mac Codex has no verified restart owner; use its original launcher to switch accounts')
            raise RestartError('RESTART_RUNTIME_UNMANAGED', 'The connected Codex is running without daemon management')
        return 'restart'

    def verify_owner(self):
        deadline = time.monotonic() + 12
        while True:
            try:
                return self.owner()
            except subprocess.CalledProcessError as error:
                # A simple systemd service can report started before it binds the Unix socket.
                with socket.socket(socket.AF_UNIX) as probe:
                    probe.settimeout(1)
                    try:
                        probe.connect(str(self.canonical_socket))
                    except (FileNotFoundError, ConnectionRefusedError) as cause:
                        if time.monotonic() >= deadline:
                            raise TimeoutError('Codex control socket did not return within 12 seconds') from error
                    else:
                        raise error
                time.sleep(0.25)

    def runtime_matches(self, auth):
        from auth_runtime import read_runtime_token, token_matches_profile
        return token_matches_profile(self.api, read_runtime_token(self.api, self.control_socket()), auth)

    def schedule(self, profile=None, revision=None, allow_stopped=False, adoption=None, expected_runtime=None):
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
        record = dict(operationId=operation_id, kind='adopt' if adoption else 'switch' if profile else 'manual', profile=profile,
                      status='running', stage='restart_preflight', requestedAt=self.m.timestamp(time.time()), attempt=0)
        if not profile and previous and previous['status'] == 'failed':
            record['previousOperationId'] = previous['operationId']
        self.save(record)
        try:
            self.api.stage = 'restart_preflight'
            current, current_auth = self.api.read(self.auth_path)
            if not profile:
                matches = [n for n in self.m.registered_profiles() if self.m.identity(
                    self.api.read(self.m.PROFILES / n / 'auth.json')[1]) == self.m.identity(current_auth)]
                if len(matches) == 1:
                    record['expectedProfile'] = matches[0]
            if expected_runtime and self.capability().get('listenerConfirmation') != expected_runtime:
                raise RestartError('RESTART_SELECTION_CHANGED', 'The direct listener or on-disk account changed; confirm again')
            if adoption:
                info = self.capability()
                if not info.get('canAdopt') or info['listenerConfirmation'] != adoption:
                    raise RestartError('RESTART_SELECTION_CHANGED', 'The listener or selected authorization changed; review again')
                record['_adoption'] = info['key']
            else:
                verb = self.owner(allow_stopped)
                if verb == 'direct':
                    record['_directKey'] = self.direct()['key']
                if verb == 'systemd':
                    info = self.direct()
                    record['_systemd'] = {key: info[key] for key in ('unit', 'user', 'key')}
                if verb == 'launchd':
                    info = self.direct()
                    record['_launchd'] = {key: info[key] for key in ('unit', 'key')}

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
            args = ['/usr/bin/python3', str(self.m.SCRIPT.with_name('auth-profiles.py')),
                    '--home', str(self.m.BASE), 'run-restart', '--socket', str(self.canonical_socket),
                    '--operation-id', operation_id]
            if self.api.owned:
                record['_bridgeChild'] = self.api.owned.call('child/identity', {})
                self.save(record)
            elif sys.platform == 'darwin':
                record['_macOwner'] = verb
                self.save(record)
                subprocess.Popen([*args, '--wait-for-restart'], start_new_session=True,
                                 stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                names = (*PROXY_ENV, 'REMODEX_AUTH_CODEX_BIN')
                environment = ['--setenv=' + name + '=' + os.environ[name] for name in names if name in os.environ]
                subprocess.run(['systemd-run', *(['--user'] if os.geteuid() else []), '--unit=' + operation_id, f'--on-active={DELAY}s',
                                '--timer-property=AccuracySec=100ms', '--property=UMask=0077', '--property=Type=exec',
                                '--property=ExitType=cgroup', *environment, *args], check=True, capture_output=True, text=True, timeout=8)
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
            adoption = record.get('_adoption')
            verb = 'adopt' if adoption else self.owner(record['_allowStopped'])
            if self.api.owned:
                if self.api.owned.call('child/identity', {}) != record.get('_bridgeChild'):
                    raise RestartError('RESTART_RUNTIME_MISMATCH', 'The bridge-owned Codex changed after scheduling')
            elif record.get('_bridgeChild') or (record.get('_macOwner') and record['_macOwner'] != verb):
                raise RestartError('RESTART_RUNTIME_MISMATCH', 'Codex ownership changed after scheduling')
            direct_key = record.get('_directKey')
            if direct_key or verb == 'direct':
                if verb != 'direct' or not direct_key or self.direct().get('key') != direct_key:
                    raise RestartError('RESTART_RUNTIME_MISMATCH', 'The direct restart listener changed after scheduling')
            unit = record.get('_systemd')
            if unit or verb == 'systemd':
                info = self.direct()
                if not unit or any(info.get(k) != v for k, v in unit.items()):
                    raise RestartError('RESTART_RUNTIME_MISMATCH', 'The systemd service owner changed')
            mac_unit = record.get('_launchd')
            if mac_unit or verb == 'launchd':
                info = self.direct()
                if verb != 'launchd' or not mac_unit or any(info.get(k) != v for k, v in mac_unit.items()):
                    raise RestartError('RESTART_RUNTIME_MISMATCH', 'The launchd service owner changed')
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
            if adoption:
                self.phase(record, 'handover_stop')
                retire_direct(self.control_socket(), self.m.BASE, adoption)
                if self.auth_path.read_bytes() != before:
                    raise RestartError('RESTART_SELECTION_CHANGED', 'Authorization changed during handover; start after checking')
                self.phase(record, 'daemon_start')
                self.command('start', 120)
            elif self.api.owned:
                self.phase(record, 'direct_start')
                replacement = self.api.owned.call('child/restart', {'expected': record['_bridgeChild']})
                if replacement == record['_bridgeChild'] or replacement != self.api.owned.call('child/identity', {}):
                    raise RestartError('RESTART_RUNTIME_MISMATCH', 'The bridge did not confirm its replacement child')
            elif direct_key:
                restart_direct(self, record, direct_key)
            elif mac_unit:
                self.phase(record, 'service_restart')
                if self.direct().get('key') != mac_unit['key']:
                    raise RestartError('RESTART_RUNTIME_MISMATCH', 'The launchd listener changed before restart')
                subprocess.run(['/bin/launchctl', 'kickstart', '-k', mac_unit['unit']],
                               check=True, capture_output=True, text=True, timeout=120)
            elif unit:
                self.phase(record, 'service_restart')
                subprocess.run(['systemctl', *(['--user'] if unit['user'] else []), 'restart', '--', unit['unit']],
                               check=True, capture_output=True, text=True, timeout=120)
            else:
                self.phase(record, 'daemon_restart' if verb == 'restart' else 'daemon_start')
                self.command(verb, 120)
            self.phase(record, 'runtime_account_verification')
            if mac_unit:
                from auth_macos import wait_for_replacement
                wait_for_replacement(self.control_socket(), mac_unit)
                owner = 'launchd'
            else:
                owner = self.verify_owner()
            if record.get('_macOwner') in ('restart', 'start') and owner != 'restart':
                raise RestartError('RESTART_RUNTIME_MISMATCH', 'Codex did not confirm daemon management after restart')
            if (direct_key and owner != 'direct') or (unit and (owner != 'systemd' or self.direct().get('unit') != unit['unit'])) or (adoption and owner != 'restart'):
                raise RestartError('RESTART_RUNTIME_MISMATCH', 'The expected manager did not take ownership')
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
