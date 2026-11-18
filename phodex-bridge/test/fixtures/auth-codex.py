#!/usr/bin/python3
"""Synthetic Codex endpoint for the normal Remodex child-process entry. No network."""
import base64
import datetime
import json
import os
from pathlib import Path
import sys
import time
root = Path(os.environ['REMODEX_AUTH_FIXTURE_ROOT']).resolve()
assert (root / '.fixture').is_file()
if 'daemon' in sys.argv:
    (root / 'daemon-called').write_text(json.dumps(sys.argv[1:]))
    print(json.dumps({'status': 'running'}))
    raise SystemExit(0)
home = Path(os.environ['CODEX_HOME']).resolve()
assert home.is_relative_to(root)
for line in sys.stdin:
    request = json.loads(line)
    if 'id' not in request:
        continue
    method = request['method']
    result = {}
    if method == 'getAuthStatus' and request['params'].get('refreshToken'):
        auth = json.loads((home/'auth.json').read_text())
        account = auth['tokens']['account_id']
        with (root/'refresh-calls').open('a') as f:
            f.write(account + '\n')
        if account.startswith('denied'):
            code = int(account[6:])
            print(json.dumps({'id': request['id'], 'error': {'code': code, 'message': 'fixture rejected', 'data': {'phase': 'oauth'}}}), flush=True)
            continue
        if account == 'silent403':
            print('Failed to refresh token status=403 forbidden', file=sys.stderr, flush=True)
        else:
            now = time.time()
            claims = base64.urlsafe_b64encode(json.dumps({'iat': now, 'exp': now+864000}).encode()).decode().rstrip('=')
            auth['tokens']['access_token'] = 'header.'+claims+'.signature'
            auth['tokens']['refresh_token'] = 'synthetic-refreshed-'+str(time.time_ns())
            auth['last_refresh'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
            (home/'auth.json').write_text(json.dumps(auth))
    if method == 'account/read':
        result = {'account': {'email': 'fixture@example.test', 'planType': 'plus'}}
    if method == 'account/rateLimits/read':
        result = {'rateLimits': {'primary': {'usedPercent': 20, 'windowDurationMins': 10080}}}
    print(json.dumps({'id': request['id'], 'result': result}), flush=True)
