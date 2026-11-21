"""Read the live local Codex identity without rotating credentials or changing restart history."""
import json
import time


def read_runtime_token(api, socket_path):
    from websockets.sync.client import unix_connect

    def call(ws, request_id, method, params):
        ws.send(json.dumps({'id': request_id, 'method': method, 'params': params}))
        deadline = time.monotonic() + 5
        while True:
            reply = json.loads(ws.recv(timeout=max(0, deadline - time.monotonic())))
            if reply.get('id') == request_id:
                if 'error' in reply:
                    raise api.m.RpcError(reply['error'])
                return reply['result']

    with unix_connect(str(socket_path), open_timeout=5, close_timeout=1) as ws:
        call(ws, 1, 'initialize', {'clientInfo': {'name': 'remodex_auth_verification', 'version': '1'}})
        ws.send(json.dumps({'method': 'initialized', 'params': {}}))
        token = call(ws, 2, 'getAuthStatus', {'refreshToken': False, 'includeToken': True}).get('authToken')
    if isinstance(token, str) and token:
        api.secrets.add(token)
        return token
    return None


def matches_runtime(api, socket_path, expected):
    token = read_runtime_token(api, socket_path)
    if token is None:
        return False
    if token == expected['tokens']['access_token']:
        return True
    live = {'tokens': {'access_token': token}}
    claims = api.m.access_claim(live, 'https://api.openai.com/auth')
    subject = api.m.access_claim(live, 'sub')
    return (isinstance(claims, dict) and bool(subject) and subject == api.m.access_claim(expected, 'sub')
            and bool(claims.get('chatgpt_account_id')) and claims['chatgpt_account_id'] == expected['tokens']['account_id'])


def reconcile_failed_switch(api, result, expected, registered):
    record_status = result.get('restartStatus')
    profile = result.get('selectedProfile')
    if (record_status not in ('failed', 'verification_failed') or not api.socket
            or not result.get('operationId') or profile not in registered):
        return result
    selected_path = api.m.PROFILES / profile / 'auth.json'
    current_path = api.m.BASE / 'auth.json'
    api.stage = 'runtime_account_reconciliation'
    try:
        selected = api.read(selected_path)[1]
        if api.m.identity(selected) != api.m.identity(expected):
            return result
        selected_subject = api.m.access_claim(selected, 'sub')
        current_subject = api.m.access_claim(expected, 'sub')
        if selected_subject and current_subject and selected_subject != current_subject:
            return result
        matches = matches_runtime(api, api.socket, expected)
        if api.read(current_path)[1] != expected or api.read(selected_path)[1] != selected:
            return dict(result, runtimeVerification={'status': 'file_changed'})
    except Exception as error:
        # A failed observation leaves the original switch outcome and its diagnosis intact.
        return dict(result, runtimeVerification={'status': 'unavailable', 'diagnostic': api.error(error)})
    if not matches:
        return dict(result, runtimeVerification={'status': 'unconfirmed'})
    observation = {'status': 'confirmed', 'operationId': result['operationId'], 'profile': profile,
                   'previousStatus': record_status, 'previousError': result.get('restartError')}
    updated = dict(result, restartStatus='ready', restartError=None, runtimeVerification=observation)
    if 'restartHistory' in result:
        updated['restartHistory'] = [
            dict(record, originalStatus=record['status'], status='recovered', recovery={'status': 'runtime_confirmed'})
            if record['operationId'] == result['operationId'] else record for record in result['restartHistory']]
    return updated
