"""Identify only the same-user process accepting the configured Linux control socket."""
import hashlib
import json
import os
from pathlib import Path
import re
import select
import signal
import socket
import struct
import time
import subprocess


def listener(endpoint):
    with socket.socket(socket.AF_UNIX) as probe:
        probe.settimeout(2)
        probe.connect(str(endpoint))
        pid, uid, _ = struct.unpack('3i', probe.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
    if uid != os.geteuid() or pid <= 1:
        return None
    return pid


def inspect_direct(endpoint, home):
    pid = listener(endpoint)
    if not pid:
        return {'mode': 'unknown'}
    proc = Path('/proc') / str(pid)
    exe = (proc / 'exe').resolve(strict=True)
    args = (proc / 'cmdline').read_bytes().rstrip(b'\0').split(b'\0')[1:]
    # Do not adopt a custom command or lose its flags when moving to the daemon.
    simple = [b'app-server', b'--listen', b'unix://']
    feature = [b'-c', b'features.code_mode_host=true'] + simple
    if exe.name != 'codex' or args[-3:] != simple:
        return {'mode': 'unknown'}
    groups = (proc / 'cgroup').read_text().splitlines()
    group = next((line[3:] for line in groups if line.startswith('0::')), '')
    unit = group.rsplit('/', 1)[-1]
    start = (proc / 'stat').read_text().rsplit(')', 1)[1].split()[19]
    key = hashlib.sha256(f'{endpoint}:{pid}:{start}:{group}:{exe}'.encode()).hexdigest()
    own_worker = re.fullmatch(r'codex-auth-restart-[0-9]+\.service', unit) is not None
    if own_worker:
        marker = home / 'auth-profiles/maintenance/direct-listener.json'
        if not marker.is_file() or json.loads(marker.read_text()) != process_mark(pid):
            return {'mode': 'unknown'}
    if not own_worker and unit.endswith('.service') and re.fullmatch(r'[A-Za-z0-9@_.:-]+\.service', unit):
        user = '/user@' in group
        controller = ['systemctl', *(['--user'] if user else [])]
        info = subprocess.run([*controller, 'show', '--property=MainPID,ControlGroup,FragmentPath', '--', unit],
                              check=True, text=True, capture_output=True, timeout=5)
        fields = dict(row.split('=', 1) for row in info.stdout.splitlines() if '=' in row)
        if fields.get('MainPID') == str(pid) and fields.get('ControlGroup') == group and fields.get('FragmentPath'):
            return {'mode': 'systemd', 'action': 'restart', 'key': key, 'unit': unit, 'user': user}
        return {'mode': 'unknown'}
    mode = 'direct'
    match = re.fullmatch(r'session-([a-zA-Z0-9]+)\.scope', unit)
    if match:
        service = subprocess.run(['loginctl', 'show-session', match[1], '-p', 'Service', '--value'],
                                 text=True, capture_output=True, timeout=3)
        if service.stdout.strip() == 'sshd':
            mode = 'ssh'
    elif group != '/' and not own_worker:
        return {'mode': mode}
    packages = home / 'packages'
    installed = any(exe.is_relative_to(packages / name / 'releases') for name in ('standalone', 'app-server-daemon'))
    managed = packages / 'app-server-daemon/current/bin/codex'
    # Never signal a same-user instance configured for a different Codex home.
    env = dict(item.split(b'=', 1) for item in (proc / 'environ').read_bytes().split(b'\0') if b'=' in item)
    owner_home = env.get(b'CODEX_HOME') or os.path.join(env.get(b'HOME', b''), b'.codex')
    if not os.path.isabs(owner_home) or Path(os.fsdecode(owner_home)).resolve() != home.resolve():
        return {'mode': mode}
    if not installed or args not in (simple, feature) or not hasattr(os, 'pidfd_open') or not hasattr(signal, 'pidfd_send_signal'):
        return {'mode': mode}
    info = {'mode': mode, 'action': 'restart', 'key': key, '_pid': pid}
    if managed.is_file():
        settings = home / 'app-server-daemon/settings.json'
        if args == simple or (settings.exists() and json.loads(settings.read_text()).get('featureOverrides', {}).get('code_mode_host') is True):
            info['canAdopt'] = True
    return info


def process_mark(pid):
    # PID, kernel start time and boot ID identify our own child across worker exits.
    return {'pid': pid, 'start': (Path('/proc') / str(pid) / 'stat').read_text().rsplit(')', 1)[1].split()[19],
            'boot': Path('/proc/sys/kernel/random/boot_id').read_text().strip()}


def retire_direct(endpoint, home, key):
    snapshot = inspect_direct(endpoint, home)
    if snapshot.get('mode') not in ('ssh', 'direct') or snapshot.get('key') != key:
        raise RuntimeError('The direct Codex listener changed before restart')
    fd = os.pidfd_open(snapshot['_pid'])
    try:
        if inspect_direct(endpoint, home).get('key') != key:
            raise RuntimeError('The direct Codex listener changed before termination')
        signal.pidfd_send_signal(fd, signal.SIGTERM)
        ready = select.poll()
        ready.register(fd, select.POLLIN)
        if not ready.poll(3000):
            # Codex drains active turns on its first shutdown signal. A second
            # requests its immediate shutdown path. The pidfd pins the original
            # process even if a desktop client reconnects and starts another.
            try:
                signal.pidfd_send_signal(fd, signal.SIGTERM)
            except ProcessLookupError:
                pass  # The process exited between poll and signal.
            if not ready.poll(12000):
                raise TimeoutError('The original Codex process did not exit after two shutdown signals (3s + 12s)')
        try:
            listener(endpoint)
        except (FileNotFoundError, ConnectionRefusedError):
            return
        raise RuntimeError('Another process acquired the Codex socket during handover')
    finally:
        os.close(fd)


def restart_direct(ops, record, key):
    """Relaunch this verified local listener in direct mode. Launch parameters stay in memory."""
    endpoint, home = ops.control_socket(), ops.m.BASE
    info = inspect_direct(endpoint, home)
    if info.get('key') != key or info.get('mode') not in ('ssh', 'direct'):
        raise RuntimeError('Direct restart target changed')
    proc = Path('/proc') / str(info['_pid'])
    exe = (proc / 'exe').resolve(strict=True)
    cwd = (proc / 'cwd').resolve(strict=True)
    argv = (proc / 'cmdline').read_bytes().rstrip(b'\0').split(b'\0')
    env = dict(item.split(b'=', 1) for item in (proc / 'environ').read_bytes().split(b'\0') if b'=' in item)
    status = dict(line.split(':', 1) for line in (proc / 'status').read_text().splitlines() if ':' in line)
    worker = dict(line.split(':', 1) for line in Path('/proc/self/status').read_text().splitlines() if ':' in line)
    def groups(row):
        return set(row['Groups'].split()) | set(row['Gid'].split())
    if groups(status) != groups(worker) or any((proc / 'ns' / ns).readlink() != (Path('/proc/self/ns') / ns).readlink()
                                               for ns in ('mnt', 'net', 'user', 'pid')):
        raise RuntimeError('Direct restart cannot preserve the original groups or namespaces')
    if any(status[k].strip() != worker[k].strip() for k in ('Uid', 'Gid', 'NoNewPrivs', 'Seccomp', 'CapEff', 'CapBnd')):
        raise RuntimeError('Direct restart cannot preserve the original process security context')
    umask = int(status['Umask'].strip(), 8)
    # Choose a private local log before committing to stop. Never copy a terminal or SSH pipe.
    log_path = home / 'app-server-control/app-server.log'
    log_fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
    with os.fdopen(log_fd, 'ab') as log:
        import stat
        if not stat.S_ISREG(os.fstat(log_fd).st_mode):
            raise RuntimeError('Direct Codex log must be a regular file')
        ops.phase(record, 'direct_stop')
        retire_direct(endpoint, home, key)
        ops.phase(record, 'direct_start')
        child = subprocess.Popen(argv, executable=exe, cwd=cwd, env=env, umask=umask, start_new_session=True,
                                 stdin=subprocess.DEVNULL, stdout=log, stderr=log)
    deadline = time.monotonic() + 15
    while child.poll() is None:
        try:
            pid = listener(endpoint)
        except (ConnectionRefusedError, FileNotFoundError):
            pid = None
        if pid == child.pid:
            ops.m.save_json(ops.m.STATE / 'direct-listener.json', process_mark(pid))
            return
        if pid or time.monotonic() >= deadline:
            failure = (RuntimeError('Another listener owns the socket') if pid
                       else TimeoutError('Direct Codex did not bind its socket within 15 seconds'))
            try:
                child.terminate()
                child.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired) as cleanup:
                failure.suppressed = [cleanup]
            raise failure
        time.sleep(.15)
    raise subprocess.CalledProcessError(child.returncode, 'codex app-server (direct start)')
