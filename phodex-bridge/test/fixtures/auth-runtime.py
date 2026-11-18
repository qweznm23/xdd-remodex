#!/usr/bin/env python3
"""Synthetic local Codex control socket. Never reads a real Codex home."""
import base64
import datetime
import json
import os
from pathlib import Path
import sys
import time
from websockets.sync.server import unix_serve

os.umask(0o077)
home = Path(sys.argv[1]).resolve()
assert home.name.startswith('remodex-auth-runtime-') and (home / '.fixture').is_file()
def serve(ws):
    for message in ws:
        request = json.loads(message)
        if 'id' not in request:
            continue
        result = {}
        if request['method'] == 'getAuthStatus':
            path = home / 'auth.json'
            auth = json.loads(path.read_text())
            if request['params'].get('refreshToken'):
                now = time.time()
                claims = base64.urlsafe_b64encode(json.dumps({'iat': now, 'exp': now + 3600}).encode()).decode().rstrip('=')
                auth['tokens'].update(access_token='header.' + claims + '.signature', refresh_token='synthetic-renewed-refresh')
                auth['last_refresh'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
                temporary = home / 'auth.new'
                temporary.write_text(json.dumps(auth))
                temporary.replace(path)
                with (home / 'refresh-calls').open('a') as log:
                    log.write('refresh\n')
            if request['params'].get('includeToken'):
                result['authToken'] = auth['tokens']['access_token']
        ws.send(json.dumps({'id': request['id'], 'result': result}))

with unix_serve(serve, str(home / 'control.sock')) as server:
    print('ready', flush=True)
    server.serve_forever()
