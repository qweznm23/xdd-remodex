"""Match the local control socket to a dedicated launchd job in this user's domain."""
import hashlib
import os
import re
import socket
import struct
import subprocess
import time


def output(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=5).stdout


def peer_pid(endpoint):
    with socket.socket(socket.AF_UNIX) as peer:
        peer.settimeout(2)
        peer.connect(str(endpoint))
        # Darwin's sys/un.h: SOL_LOCAL / LOCAL_PEERPID returns a native pid_t.
        raw = peer.getsockopt(getattr(socket, 'SOL_LOCAL', 0), getattr(socket, 'LOCAL_PEERPID', 2), 4)
        return struct.unpack('i', raw)[0]


def launchd_owner(endpoint):
    pid = peer_pid(endpoint)
    unknown = {'mode': 'unknown', 'reason': 'external_unmanaged'}
    if pid <= 1:
        return unknown
    uid = os.geteuid()
    info = output('/bin/ps', '-p', str(pid), '-o', 'uid=,lstart=,comm=').strip().split(None, 6)
    if len(info) != 7 or info[0] != str(uid) or os.path.basename(info[-1]) != 'codex':
        return unknown
    labels = []
    for line in output('/bin/launchctl', 'list').splitlines():
        row = line.split()
        if len(row) == 3 and row[0] == str(pid) and re.fullmatch(r'[a-zA-Z0-9_.-]+', row[2]):
            labels.append(row[2])
    if len(labels) != 1:
        return unknown
    # 'list' is scoped to this bootstrap domain; verify the full service target
    # separately, without guessing its label from a process or app name.
    for domain in (f'gui/{uid}', f'user/{uid}'):
        target = domain + '/' + labels[0]
        result = subprocess.run(['/bin/launchctl', 'print', target], capture_output=True, text=True, timeout=5)
        if result.returncode and 'could not find' not in result.stderr.lower():
            result.check_returncode()
        if result.returncode == 0 and re.search(r'^\s*pid = ' + str(pid) + r'\s*$', result.stdout, re.M):
            key = hashlib.sha256(f'{endpoint}:{target}:{pid}:{info}'.encode()).hexdigest()
            return {'mode': 'launchd', 'action': 'restart', 'unit': target, 'key': key}
    return unknown


def wait_for_replacement(endpoint, original):
    deadline = time.monotonic() + 12
    last_error = None
    while True:
        try:
            observed = launchd_owner(endpoint)
        except (FileNotFoundError, ConnectionRefusedError) as error:
            last_error = error
        else:
            if observed.get('mode') == 'launchd' and observed['unit'] != original['unit']:
                raise RuntimeError('A different launchd service acquired the Codex socket')
            if observed.get('unit') == original['unit'] and observed['key'] != original['key']:
                return
        if time.monotonic() >= deadline:
            raise TimeoutError('The launchd job failed to confirm a replacement Codex listener within 12 seconds') from last_error
        time.sleep(.25)
