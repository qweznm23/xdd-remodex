"""Private inherited pipes to the bridge that owns this stdio Codex child."""
import json
import os


class OwnedChild:
    def __init__(self, manager):
        self.manager = manager
        self.requests = os.fdopen(4, 'w')
        self.responses = os.fdopen(3, 'r')

    def call(self, method, params, timeout=20):
        self.requests.write(json.dumps({'method': method, 'params': params, 'timeout': timeout}) + '\n')
        self.requests.flush()
        line = self.responses.readline(256 * 1024)
        if not line:
            raise ConnectionError('Owning bridge disconnected during ' + method)
        if not line.endswith('\n'):
            raise ValueError('Oversized bridge control response during ' + method)
        reply = json.loads(line)
        if 'error' in reply:
            raise self.manager.RpcError(reply['error'])
        return reply['result']
