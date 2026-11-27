"""Quota checks and short-lived, single-use confirmations. Caller owns manager.lock."""
import json
import math
import time
import uuid


class ResetCardError(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def weekly_remaining(quota):
    buckets = quota.get('rateLimitsByLimitId') or {}
    bucket = buckets.get('codex') if buckets else quota.get('rateLimits')
    if not isinstance(bucket, dict) or bucket.get('limitId') not in (None, 'codex'):
        raise ResetCardError('RESET_QUOTA_UNKNOWN', 'Ordinary Codex weekly quota unavailable')
    weekly = [w for w in (bucket.get('primary'), bucket.get('secondary'))
              if isinstance(w, dict) and w.get('windowDurationMins') == 10080]
    if len(weekly) != 1:
        raise ResetCardError('RESET_QUOTA_UNKNOWN', 'Missing or ambiguous Codex weekly window')
    usage = weekly[0].get('usedPercent')
    reset = weekly[0].get('resetsAt')
    if type(usage) not in (float, int) or not math.isfinite(usage) or not 0 <= usage <= 100 or (
            reset is not None and (type(reset) not in (int, float) or not math.isfinite(reset) or reset <= time.time())):
        raise ResetCardError('RESET_QUOTA_UNKNOWN', 'Invalid or elapsed weekly quota window')
    return 100 - usage


def reset_card(api, number, auth, card_id, ticket, call):
    from auth_runtime import live_token, token_matches_profile
    api.stage = 'reset_identity'
    def verify_identity():
        if not token_matches_profile(api, live_token(api, call), auth):
            raise ResetCardError('RESET_ACCOUNT_CHANGED', 'Codex account differs from the selected authorization')
    subject = api.m.access_claim(auth, 'sub')
    account = auth['tokens']['account_id']
    if not subject or not account:
        raise ResetCardError('RESET_ACCOUNT_UNKNOWN', 'Cannot verify account identity')
    identity = [number, account, subject, card_id]
    path = api.m.STATE / 'reset-card-confirmations.json'
    entries = json.loads(path.read_text()) if path.exists() else {}
    entries = {k: v for k, v in entries.items() if v['expires'] > time.time()}
    entry = None
    if ticket is not None:
        api.stage = 'reset_confirmation'
        entry = entries.get(ticket)
        if not entry or entry['identity'] != identity:
            raise ResetCardError('RESET_RECHECK_REQUIRED', 'Confirmation expired or account/card changed; check again')
        del entries[ticket]
        api.m.save_json(path, entries)
    api.stage = 'reset_identity'
    verify_identity()
    api.stage = 'reset_read_quota'
    quota = call('account/rateLimits/read', {'excludeResetCreditDetails': False}, 30)
    if quota.get('accountId') and quota['accountId'] != account:
        raise ResetCardError('RESET_ACCOUNT_CHANGED', 'Quota belongs to another account')
    remaining = weekly_remaining(quota)
    summary = quota.get('rateLimitResetCredits') or {}
    count = summary.get('availableCount')
    cards = [c for c in summary.get('credits') or [] if c.get('id') == card_id]
    if type(count) is not int or count <= 0 or len(cards) != 1:
        raise ResetCardError('RESET_CARD_UNAVAILABLE', 'The selected quota reset card is unavailable')
    card = cards[0]
    if card.get('status') != 'available' or card.get('resetType') != 'codexRateLimits':
        raise ResetCardError('RESET_CARD_UNAVAILABLE', 'The selected card cannot reset Codex limits')
    expiry = card.get('expiresAt')
    if 'expiresAt' not in card or (expiry is not None and (type(expiry) is not int or expiry <= time.time())):
        raise ResetCardError('RESET_CARD_UNAVAILABLE', 'The selected card has expired or expiry could not be verified')
    title = card.get('title') or ''
    email = api.metadata(number, auth).get('email')
    view = dict(remaining=remaining, title=title, account=email or account, accountId=account)
    if remaining > 5:
        return dict(view, status='blocked')

    api.stage = 'reset_confirmation'
    now = time.time()
    if entry and (entry['remaining'] != remaining or entry['expires'] <= now):
        raise ResetCardError('RESET_RECHECK_REQUIRED', 'Confirmation expired or quota changed; check again')
    if entry is None or (remaining > 0 and entry['step'] == 'confirm'):
        step = 'confirm' if ticket is None else 'confirmAgain'
        key = str(uuid.uuid4())
        if len(entries) >= 32:
            del entries[min(entries, key=lambda k: entries[k]['expires'])]
        entries[key] = dict(identity=identity, remaining=remaining, expires=now + 120, step=step)
        api.m.save_json(path, entries)
        return dict(view, status=step, ticket=key)
    api.stage = 'reset_verify_account'
    source = (api.m.BASE if number == 'current' else api.m.PROFILES / number) / 'auth.json'
    current = api.read(source)[1]
    if api.m.identity(current) != api.m.identity(auth) or api.m.access_claim(current, 'sub') != subject:
        raise ResetCardError('RESET_ACCOUNT_CHANGED', 'Selected authorization changed before use')
    verify_identity()
    # The same named credit and identity use the same key even after a lost response or restart.
    idem = str(uuid.uuid5(uuid.NAMESPACE_URL, json.dumps(['remodex-reset', account, subject, card_id])))
    api.stage = 'reset_consume'
    response = call('account/rateLimitResetCredit/consume', {'creditId': card_id, 'idempotencyKey': idem}, 45)
    outcome = response.get('outcome')
    if outcome not in ('reset', 'nothingToReset', 'noCredit', 'alreadyRedeemed'):
        raise ResetCardError('RESET_OUTCOME_UNKNOWN', 'Unexpected reset outcome; check quota before trying again')
    return dict(view, status=outcome)
