#!/usr/bin/env python3
"""Local command-line client for the Remodex authorization service."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--home', default=str(Path.home() / '.codex'))
parser.add_argument('action', choices=['status', 'refresh', 'switch'])
parser.add_argument('profile', nargs='?', choices=['1', '2', '3'])
parser.add_argument('--force', action='store_true')
parser.add_argument('--dry-run', action='store_true')
args = parser.parse_args()
entry = Path(__file__).with_name('auth-profiles.py')

def request(action, extra=()):
    response = subprocess.run([sys.executable, str(entry), '--home', args.home, action, *extra],
                              capture_output=True, text=True, timeout=930, check=True)
    reply = json.loads(response.stdout)
    if not reply.get('ok'):
        print(json.dumps(reply, ensure_ascii=False))
        raise SystemExit(1)
    return reply['result']

if args.action == 'status':
    result = request('status')
elif args.action == 'refresh':
    extra = (['--dry-run'] if args.dry_run else []) + (['--force'] if args.force else [])
    result = request('maintenance', extra)
else:
    if not args.profile:
        parser.error('switch requires a numbered authorization')
    if args.dry_run:
        status = request('status')
        row = next((p for p in status['profiles'] if p['id'] == args.profile), None)
        if row is None:
            parser.error('authorization not found')
        result = {'profile': args.profile, 'status': 'would_switch', 'restartRequired': not row['active']}
    else:
        status = request('status')
        row = next((p for p in status['profiles'] if p['id'] == args.profile), None)
        if row is None:
            parser.error('authorization not found')
        result = request('switch', ['--profile', row['id'], '--revision', row['revision']])
print(json.dumps(result, ensure_ascii=False, indent=2))
if result.get('results') and any(row.get('status') == 'failed' for row in result['results']):
    raise SystemExit(1)
