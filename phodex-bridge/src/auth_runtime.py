"""Read the live local Codex identity without rotating credentials or changing restart history."""
from contextlib import contextmanager
import json
import time


@contextmanager
def runtime_session(api, socket_path):
    if getattr(api, 'owned', None):
        yield api.owned.call
        return
    from websockets.sync.client import unix_connect
    with unix_connect(str(socket_path), open_timeout=5, close_timeout=1) as ws:
        sequence = 0
        def call(method, params, timeout=5):
            nonlocal sequence
            sequence += 1
            ws.send(json.dumps({'id': sequence, 'method': method, 'params': params}))
            deadline = time.monotonic() + timeout
            while True:
                reply = json.loads(ws.recv(timeout=max(0, deadline - time.monotonic())))
                if reply.get('id') == sequence:
                    if 'error' in reply:
                        raise api.m.RpcError(reply['error'])
                    return reply['result']

        call('initialize', {'clientInfo': {'name': 'remodex_auth_verification', 'version': '1'},
                            'capabilities': {'experimentalApi': True}})
        ws.send(json.dumps({'method': 'initialized', 'params': {}}))
        yield call


def live_token(api, call):
    token = call('getAuthStatus', {'refreshToken': False, 'includeToken': True}).get('authToken')
    if isinstance(token, str) and token:
        api.secrets.add(token)
        return token
    return None


def read_runtime_token(api, socket_path):
    with runtime_session(api, socket_path) as call:
        return live_token(api, call)


def runtime_quota(api, expected=None):
    """Attribute quota only when the same account is observed on both sides of the RPC."""
    from datetime import datetime, timezone
    from auth_restarts import RestartError
    api.stage = 'query_runtime_identity'
    with runtime_session(api, api.socket) as call:
        before = live_token(api, call)
        if expected is not None and not token_matches_profile(api, before, expected):
            raise RestartError('RESTART_ACCOUNT_MISMATCH', 'Live Codex account changed before querying quota')
        api.stage = 'query_rate_limits'
        try:
            quota = call('account/rateLimits/read', {'excludeResetCreditDetails': False}, 30)
        except api.m.RpcError as first:
            response = first.response
            if response.get('code') not in (-32600, -32602) or not any(term in str(response.get('message', '')).lower()
                    for term in ('invalid param', 'failed to parse', 'expected', 'missing field')):
                raise
            try:
                quota = call('account/rateLimits/read', {}, 30)
            except Exception as error:
                error.suppressed = [first]
                raise
        api.stage = 'query_runtime_verify'
        after = live_token(api, call)
        if not before or before != after:
            raise RestartError('PROFILE_CHANGED', 'Runtime identity changed while querying quota; retry')
        live_claim = api.m.access_claim({'tokens': {'access_token': after}}, 'https://api.openai.com/auth')
        if isinstance(live_claim, dict) and live_claim.get('chatgpt_account_id') and quota.get('accountId') and (
                live_claim['chatgpt_account_id'] != quota['accountId']):
            raise RestartError('RESTART_ACCOUNT_MISMATCH', 'Quota response belongs to a different account')
        ids = []
        for number in api.registered():
            path = api.m.PROFILES / number / 'auth.json'
            if path.exists() and token_matches_profile(api, after, api.read(path)[1]):
                ids.append(number)
        return {'profile': ids[0] if len(ids) == 1 else None,
                'queriedAt': datetime.now(timezone.utc).isoformat(),
                'quota': {k: quota[k] for k in ('rateLimits', 'rateLimitsByLimitId', 'rateLimitResetCredits') if k in quota}}


def token_matches_profile(api, token, expected):
    if token is None:
        return False
    if token == expected['tokens']['access_token']:
        return True
    live = {'tokens': {'access_token': token}}
    claims = api.m.access_claim(live, 'https://api.openai.com/auth')
    subject = api.m.access_claim(live, 'sub')
    return (isinstance(claims, dict) and bool(subject) and subject == api.m.access_claim(expected, 'sub')
            and bool(claims.get('chatgpt_account_id')) and claims['chatgpt_account_id'] == expected['tokens']['account_id'])


def reconcile_failed_switch(api, result, expected, registered, stopped_key=None):
    record_status = result.get('restartStatus')
    profile = result.get('selectedProfile')
    if (record_status not in ('failed', 'verification_failed') or not (api.socket or api.owned)
            or not result.get('operationId') or profile not in registered):
        return result
    selected_path = api.m.PROFILES / profile / 'auth.json'
    current_path = api.m.BASE / 'auth.json'
    api.stage = 'runtime_account_reconciliation'
    try:
        selected = api.read(selected_path)[1]
        selected_on_disk = api.m.identity(selected) == api.m.identity(expected)
        selected_subject = api.m.access_claim(selected, 'sub')
        current_subject = api.m.access_claim(expected, 'sub')
        if selected_subject and current_subject and selected_subject != current_subject:
            selected_on_disk = False
        token = read_runtime_token(api, api.socket)
        matches = selected_on_disk and token_matches_profile(api, token, expected) and token_matches_profile(api, token, selected)
        runtime_ids = []
        if not matches and token:
            for number in registered:
                path = api.m.PROFILES / number / 'auth.json'
                candidate = selected if number == profile else api.read(path)[1]
                if token_matches_profile(api, token, candidate):
                    runtime_ids.append(number)
        replacement_verified = False
        control = result.get('runtimeControl', {})
        if (matches and stopped_key and control.get('mode') in ('ssh', 'direct')
                and control.get('action') == 'restart' and control.get('key') and control['key'] != stopped_key):
            from auth_restarts import RestartOperations
            # Bracket the account RPC with two observations of the same new listener.
            replacement_verified = RestartOperations(api).direct().get('key') == control['key']
        if api.read(current_path)[1] != expected or api.read(selected_path)[1] != selected:
            return dict(result, runtimeVerification={'status': 'file_changed'})
    except Exception as error:
        # A failed observation leaves the original switch outcome and its diagnosis intact.
        return dict(result, runtimeVerification={'status': 'unavailable', 'diagnostic': api.error(error)})
    if record_status == 'failed' and not replacement_verified and any(r.get('operationId') == result['operationId'] and
            r.get('kind') in ('manual', 'adopt') for r in result.get('restartHistory', [])):
        # Account identity alone cannot prove that a failed management handover/restart succeeded.
        return dict(result, runtimeVerification={'status': 'unconfirmed', 'operationId': result['operationId'],
                    'profile': profile, 'runtimeProfile': profile if matches else runtime_ids[0] if len(runtime_ids) == 1 else None})
    if not matches:
        observation = {'status': 'unconfirmed', 'operationId': result['operationId'], 'profile': profile}
        if len(runtime_ids) == 1:
            observation['runtimeProfile'] = runtime_ids[0]
        return dict(result, runtimeVerification=observation)
    observation = {'status': 'confirmed', 'operationId': result['operationId'], 'profile': profile,
                   'previousStatus': record_status, 'previousError': result.get('restartError')}
    updated = dict(result, restartStatus='ready', restartError=None, runtimeVerification=observation)
    if 'restartHistory' in result:
        updated['restartHistory'] = [
            dict(record, originalStatus=record['status'], status='recovered', recovery={'status': 'runtime_confirmed'})
            if record['operationId'] == result['operationId'] else record for record in result['restartHistory']]
    return updated
