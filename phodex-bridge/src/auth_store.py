#!/usr/bin/env python3
"""Remodex-owned atomic credential storage, version comparison, RPC and switch restart."""
import argparse
import base64
import datetime as dt
import fcntl
import json
import os
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
import time
import traceback

BASE = Path.home() / '.codex'
PROFILES = BASE / 'auth-profiles'
PROFILE_NUMBERS = ('1', '2', '3')
STATE = PROFILES / 'maintenance'
INTERVAL = 86400
BEIJING = dt.timezone(dt.timedelta(hours=8))
SCRIPT = Path(__file__).resolve()


def read(path):
    import stat
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > 65536:
            raise ValueError('Authorization must be a regular file no larger than 64 KiB')
        data = stream.read(65537)
    if len(data) > 65536:
        raise ValueError('Authorization exceeds 64 KiB')
    value = json.loads(data)
    if not isinstance(value, dict) or not isinstance(value.get('tokens'), dict):
        raise ValueError('Invalid authorization structure')
    t = value.get('tokens', {})
    if value.get('auth_mode') != 'chatgpt' or not all(isinstance(t.get(k), str) and t[k] for k in ('access_token', 'refresh_token', 'account_id')):
        raise ValueError(f'Invalid managed ChatGPT credential: {path}')
    refreshed = value.get('last_refresh')
    if refreshed is not None:
        if not isinstance(refreshed, str):
            raise ValueError('Invalid last_refresh timestamp type')
        try:
            parsed = dt.datetime.fromisoformat(refreshed)
            if parsed.tzinfo is None:
                raise ValueError('Missing timezone')
        except ValueError as cause:
            raise ValueError('Invalid last_refresh timestamp') from cause
    return data, value


def identity(auth):
    # Conservatively skip every registration for the active account/workspace.
    return auth['auth_mode'], auth['tokens']['account_id']


def atomic(path, data):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.auth-manager-', dir=path.parent)
    failure = None
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    except BaseException as error:
        failure = error
        raise
    finally:
        try:
            if os.path.exists(name):
                os.unlink(name)
        except Exception as cleanup:
            if failure is None:
                raise
            failure.suppressed = getattr(failure, 'suppressed', []) + [cleanup]


def save_json(path, value):
    atomic(path, (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode())


def timestamp(value):
    if value is None:
        return None
    return dt.datetime.fromtimestamp(value, BEIJING).isoformat()


def access_claim(auth, name):
    try:
        s = auth['tokens']['access_token'].split('.')[1]
        return json.loads(base64.urlsafe_b64decode(s + '=' * (-len(s) % 4))).get(name)
    except (ValueError, IndexError, TypeError, AttributeError):
        return None


def expiry(auth):
    return access_claim(auth, 'exp')


def credential_time(auth):
    last_refresh = auth.get('last_refresh')
    if isinstance(last_refresh, str):
        try:
            parsed = dt.datetime.fromisoformat(last_refresh)
            if parsed.tzinfo is not None:
                return parsed.timestamp()
        except (ValueError, OverflowError):
            pass
    issued = access_claim(auth, 'iat')
    return issued if type(issued) in (int, float) and 0 < issued < 253402214400 else None


def daily_due(entry, auth, now):
    latest = max(entry.get('last_attempt') or 0,
                 entry.get('last_success') or 0, credential_time(auth) or 0)
    today = dt.datetime.fromtimestamp(now, BEIJING)
    # Compare calendar days, not elapsed seconds: systemd adds a small delay
    # which can make two consecutive daily activations less than 24h apart.
    due = latest == 0 or dt.datetime.fromtimestamp(latest, BEIJING).date() < today.date()
    scheduled = today.replace(hour=4, minute=0, second=0, microsecond=0)
    if scheduled <= today or not due:
        scheduled += dt.timedelta(seconds=INTERVAL)
    return due, scheduled.timestamp()


def sync_active_snapshot(profile, active_data, active, *, dry=False):
    saved_data, saved = read(profile)
    if identity(saved) != identity(active):
        raise ValueError('Cannot synchronize credentials between different accounts')
    if active_data == saved_data or (active['tokens'] == saved['tokens'] and credential_time(active) == credential_time(saved)):
        return 'already_synced'
    live_time, saved_time = credential_time(active), credential_time(saved)
    if live_time is None or saved_time is None or live_time == saved_time:
        return 'version_order_unknown'
    if live_time < saved_time:
        return 'kept_newer_saved'
    if dry:
        return 'would_sync_newer_active'
    if (BASE / 'auth.json').read_bytes() != active_data or profile.read_bytes() != saved_data:
        raise RuntimeError('Credential changed during synchronization; rerun to use a fresh snapshot')
    atomic(profile, active_data)
    return 'synced_newer_active'


def cli():
    # Use the daemon's own release so switching doesn't downgrade the service.
    managed = BASE / 'packages/app-server-daemon/current/bin/codex'
    return os.environ.get('REMODEX_AUTH_CODEX_BIN') or str(managed if managed.is_file() else Path.home() / '.local/bin/codex')


def state_read():
    p = STATE / 'state.json'
    return json.loads(p.read_text()) if p.exists() else {'profiles': {}}


def registered_profiles():
    # Slots 1 and 2 are established. Slot 3 is optional until imported.
    return tuple(n for n in PROFILE_NUMBERS if (PROFILES / n / 'auth.json').is_file())


class RpcError(RuntimeError):
    def __init__(self, response):
        self.response = response
        super().__init__(json.dumps(response, ensure_ascii=False))


class RPC:
    def __init__(self, work):
        env = os.environ.copy()
        env['CODEX_HOME'] = str(work)  # Standard Codex configuration, isolated from active auth.
        self.messages = queue.Queue()
        self.errors = bytearray()
        self.truncated = False
        self.proc = subprocess.Popen([cli(), 'app-server', '--stdio'], env=env,
                                     cwd=work, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        def stdout():
            try:
                for line in self.proc.stdout:
                    try:
                        self.messages.put(json.loads(line), timeout=1)
                    except json.JSONDecodeError as error:
                        self.messages.put(error)
                        break
            finally:
                self.messages.put(None)
        def stderr():
            while chunk := self.proc.stderr.read(4096):
                remaining = 512 * 1024 - len(self.errors)
                self.errors.extend(chunk[:max(0, remaining)])
                if len(chunk) > remaining:
                    self.truncated = True
        self.reader = threading.Thread(target=stdout, daemon=True)
        self.err_reader = threading.Thread(target=stderr, daemon=True)
        self.reader.start()
        self.err_reader.start()

    def send(self, obj):
        self.proc.stdin.write(json.dumps(obj).encode() + b'\n')
        self.proc.stdin.flush()

    def call(self, request_id, method, params):
        self.send({'id': request_id, 'method': method, 'params': params})
        deadline = time.monotonic() + 60
        while True:
            try:
                message = self.messages.get(timeout=max(0.01, deadline - time.monotonic()))
            except queue.Empty as error:
                raise TimeoutError(f'RPC {method} timed out after 60 seconds') from error
            if message is None:
                raise EOFError(f'RPC {method} transport closed; process exit={self.proc.poll()}')
            if isinstance(message, Exception):
                raise RuntimeError(f'RPC {method} response parsing failed') from message
            if message.get('id') == request_id:
                if 'error' in message:
                    raise RpcError(message['error'])
                return message.get('result')

    def close(self):
        self.proc.stdin.close()
        try:
            self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proc.terminate()  # Only this isolated process; never the active daemon.
            self.proc.wait(timeout=10)
        self.err_reader.join(timeout=2)


def switch(number, dry=False):
    path = BASE / 'auth.json'
    before, current = read(path)
    target_path = PROFILES / number / 'auth.json'
    data, target = read(target_path)
    if dry:
        print(f'Would select profile {number}; restart required={identity(current) != identity(target)}')
        return
    snapshots = {}
    for n in registered_profiles():
        saved_path = PROFILES / n / 'auth.json'
        if identity(read(saved_path)[1]) == identity(current):
            snapshots[n] = sync_active_snapshot(saved_path, before, current)
    if identity(current) == identity(target):
        print(f'Already using profile {number}; saved credential: {snapshots[number]}.')
        return
    if path.read_bytes() != before or target_path.read_bytes() != data:
        raise RuntimeError('Credentials changed concurrently; switch cancelled')
    atomic(path, data)
    unit = f'codex-auth-restart-{time.time_ns()}'
    previous_switch = (STATE / 'last-switch.json').read_bytes() if (STATE / 'last-switch.json').exists() else None
    try:
        save_json(STATE / 'last-switch.json', {'profile': number, 'time_beijing': timestamp(time.time()),
                                             'status': 'restart_scheduled', 'unit': unit})
        # Transient system services do not inherit the caller's proxy environment.
        environment = ['--setenv=' + name + '=' + os.environ[name] for name in
                       ('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY',
                        'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy') if name in os.environ]
        run = subprocess.run(['systemd-run', '--unit=' + unit, '--on-active=10s',
            '--timer-property=AccuracySec=1s', '--property=UMask=0077',
            '--property=Type=exec', '--property=ExitType=cgroup',
            *environment, '/usr/bin/python3', str(SCRIPT), '--home', str(BASE), 'restart', number, '--operation-id', unit], capture_output=True, text=True)
        if run.returncode:
            raise RuntimeError(f'Restart scheduling failed ({run.returncode}): {run.stderr}')
    except Exception as primary:
        try:
            if path.read_bytes() == data:
                atomic(path, before)
            if previous_switch is None:
                (STATE / 'last-switch.json').unlink(missing_ok=True)
            else:
                atomic(STATE / 'last-switch.json', previous_switch)
        except Exception as cleanup:
            primary.suppressed = getattr(primary, 'suppressed', []) + [cleanup]
        raise
    print(f'Selected profile {number}. Codex service restarts in 10 seconds; SSH desktop clients may reconnect.')


def restart(number, operation_id=None, redact=lambda value: value):
    report = {'operation': 'restart', 'profile': number, 'time_beijing': timestamp(time.time())}
    selection = json.loads((STATE / 'last-switch.json').read_text()) if (STATE / 'last-switch.json').exists() else {}
    report['operationId'] = operation_id or selection.get('unit')
    report['stage'] = 'daemon_restart'
    if (operation_id and operation_id != selection.get('unit')) or identity(read(BASE / 'auth.json')[1]) != identity(read(PROFILES / number / 'auth.json')[1]):
        report['status'] = 'superseded_by_newer_switch'
    else:
        try:
            run = subprocess.run([cli(), 'app-server', 'daemon', 'restart'], capture_output=True, text=True, timeout=120)
        except Exception as error:
            error.profile_stage = 'daemon_restart'
            raise
        report.update(exit_code=run.returncode, stdout=run.stdout, stderr=run.stderr)
        if run.returncode:
            report['status'] = 'failed'
        else:
            report['stage'] = 'daemon_verification'
            try:
                check = subprocess.run([cli(), 'app-server', 'daemon', 'version'], capture_output=True, text=True, timeout=20)
            except Exception as error:
                error.profile_stage = 'daemon_verification'
                raise
            report['verification_exit_code'] = check.returncode
            report['verification_output'] = check.stdout
            report['verification_stderr'] = check.stderr
            version = json.loads(check.stdout) if check.returncode == 0 else {}
            report['status'] = 'restarted' if version.get('status') == 'running' else 'verification_failed'
    if not operation_id or operation_id == selection.get('unit'):
        report = json.loads(redact(json.dumps(report)))
        save_json(STATE / 'last-restart.json', report)
    print(json.dumps(report, ensure_ascii=False))
    return int(report['status'] not in ('restarted', 'superseded_by_newer_switch'))



def configure(home):
    global BASE, PROFILES, STATE
    BASE = Path(home).resolve()
    PROFILES = BASE / 'auth-profiles'
    STATE = PROFILES / 'maintenance'


if __name__ == '__main__':
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('--home', required=True)
    parser.add_argument('action', choices=['restart'])
    parser.add_argument('profile', choices=PROFILE_NUMBERS)
    parser.add_argument('--operation-id', required=True)
    args = parser.parse_args()
    configure(args.home)
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (STATE / 'manager.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        # Same structured diagnostics/redaction as the bridge's normal entry point.
        import importlib.util
        spec = importlib.util.spec_from_file_location('auth_profiles', SCRIPT.with_name('auth-profiles.py'))
        adapter = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(adapter)
        import sys
        api = adapter.Profiles(sys.modules[__name__])
        try:
            for number in registered_profiles():
                api.read(PROFILES / number / 'auth.json')
            api.read(BASE / 'auth.json')
            raise SystemExit(restart(args.profile, args.operation_id, api.redact))
        except Exception as error:
            report = api.error(error)
            report.update(operation='restart', profile=args.profile, operationId=args.operation_id, status='failed')
            try:
                selection = json.loads((STATE / 'last-switch.json').read_text())
                if selection.get('unit') == args.operation_id:
                    save_json(STATE / 'last-restart.json', adapter.bounded_diagnostic(report))
            except Exception as secondary:
                report.setdefault('suppressed', []).append(api.error(secondary))
            print(json.dumps(adapter.bounded_diagnostic(report)))
            raise SystemExit(1)
