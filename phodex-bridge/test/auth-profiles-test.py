"""Isolated host-manager integration: fake credentials, never restarts live Codex."""
import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import sys

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

api = load('profiles_api', Path(__file__).parents[1] / 'src/auth-profiles.py')
m = load('manager', Path(__file__).parents[1] / 'src/auth_store.py')
def credential(account, refresh):
    claims = base64.urlsafe_b64encode(json.dumps({'iat': 1700000000, 'exp': 2000000000}).encode()).decode().rstrip('=')
    return {'auth_mode': 'chatgpt', 'last_refresh': '2026-11-01T12:00:00Z', 'tokens': {
        'account_id': account, 'refresh_token': refresh, 'access_token': 'header.' + claims + '.signature'}}

class ProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(self.temp.name)
        self.patches = [patch.object(m, 'BASE', base), patch.object(m, 'PROFILES', base/'auth-profiles'),
                        patch.object(m, 'STATE', base/'auth-profiles/maintenance')]
        for p in self.patches: p.start()
        m.STATE.mkdir(parents=True)
        m.save_json(base/'auth.json', credential('active-account', 'active-new'))
        m.save_json(m.PROFILES/'1/auth.json', credential('other-account', 'inactive-original'))
        old = credential('active-account', 'active-old')
        old['last_refresh'] = '2026-10-31T12:00:00Z'
        m.save_json(m.PROFILES/'2/auth.json', old)
        self.api = api.Profiles(m)
        self.revision = self.api.list()['profiles'][0]['revision']
        self.active = (base/'auth.json').read_bytes()

    def tearDown(self):
        for p in reversed(self.patches): p.stop()
        self.temp.cleanup()

    def test_filters_by_identity_not_token_bytes_and_returns_only_dates(self):
        result = self.api.list()
        self.assertEqual([x['id'] for x in result['profiles']], ['1'])
        self.assertIn('accessIssuedAt', result['profiles'][0])
        for forbidden in ('active-new', 'inactive-original', 'other-account', 'header.'):
            self.assertNotIn(forbidden, json.dumps(result))

    def test_atomic_cleanup_error_does_not_replace_disk_failure(self):
        with patch.object(m.os, 'fsync', side_effect=OSError(28, 'No space left')):
            with patch.object(m.os, 'unlink', side_effect=PermissionError(13, 'Cleanup denied')):
                with self.assertRaises(OSError) as caught:
                    m.atomic(m.BASE/'auth.json', b'new credentials')
        self.assertEqual(caught.exception.errno, 28)
        self.assertEqual(caught.exception.suppressed[0].errno, 13)
        report = self.api.error(caught.exception)
        self.assertEqual(report['errno'], 28)
        self.assertEqual(report['suppressed'][0]['errno'], 13)
        self.assertTrue(report['suppressed'][0]['context']['cycle'])
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)

    def test_restart_timeout_retains_stage_and_original_exception(self):
        m.save_json(m.STATE/'last-switch.json', {'unit': 'test-operation'})
        failure = m.subprocess.TimeoutExpired(['codex', 'app-server', 'daemon', 'restart'], 120)
        with patch.object(m.subprocess, 'run', side_effect=failure):
            with self.assertRaises(m.subprocess.TimeoutExpired) as caught:
                m.restart('2', 'test-operation')
        self.assertIs(caught.exception, failure)
        self.assertEqual(self.api.error(failure)['stage'], 'daemon_restart')

    def test_new_registered_profile_is_listed_and_can_be_selected(self):
        m.save_json(m.PROFILES/'3/auth.json', credential('third-account', 'third-private'))
        rows = self.api.list()['profiles']
        self.assertEqual([row['id'] for row in rows], ['1', '3'])
        self.assertNotIn('third-private', json.dumps(rows))
        with patch.object(m.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stderr='')):
            result = self.api.switch('3', rows[1]['revision'])
        self.assertEqual(result['profile'], '3')
        self.assertNotIn('3', [row['id'] for row in self.api.list()['profiles']])
        m.save_json(m.BASE/'auth.json', credential('active-account', 'newer-selection'))
        self.assertEqual(self.api.list()['restartStatus'], 'superseded_by_newer_switch')

    def test_new_profile_query_uses_registry_without_changing_active_account(self):
        m.save_json(m.PROFILES/'3/auth.json', credential('third-account', 'third-private'))
        row = next(row for row in self.api.list()['profiles'] if row['id'] == '3')
        with patch.object(m, 'RPC', self.rpc(account='third-account')):
            result = self.api.query('3', row['revision'])
        self.assertEqual(result['profile'], '3')
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)

    def test_unregistered_files_and_noncanonical_ids_cannot_be_selected(self):
        m.save_json(m.PROFILES/'4/auth.json', credential('fourth-account', 'fourth-private'))
        self.assertNotIn('4', [row['id'] for row in self.api.list()['profiles']])
        for number in ('4', '03', '3\n', '../3'):
            with self.assertRaises(api.ProfileError) as caught:
                self.api.target(number, '1:2')
            self.assertEqual(caught.exception.code, 'INVALID_PROFILE')

    def test_switch_uses_existing_manager_save_and_delayed_restart(self):
        with patch.object(m.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stderr='')) as run:
            result = self.api.switch('1', self.revision)
        self.assertEqual(result['status'], 'restart_scheduled')
        self.assertIn('--on-active=10s', run.call_args.args[0])
        self.assertIn('--property=ExitType=cgroup', run.call_args.args[0])
        self.assertIn('--property=Type=exec', run.call_args.args[0])
        self.assertEqual(result['operationId'], self.api.list()['operationId'])
        self.assertEqual((m.PROFILES/'2/auth.json').read_bytes(), self.active)
        self.assertEqual(self.api.list()['profiles'][0]['id'], '2')
        self.assertEqual(self.api.list()['restartStatus'], 'restart_scheduled')

    def test_stale_selection_and_active_account_are_rejected_without_writes(self):
        for number, revision, code in [('1', 'old', 'PROFILE_CHANGED'), ('2', self.revision, 'PROFILE_CHANGED'), ('../auth', self.revision, 'INVALID_PROFILE')]:
            with self.assertRaises(api.ProfileError) as caught: self.api.switch(number, revision)
            self.assertEqual(caught.exception.code, code)
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)

    def test_old_timer_cannot_restart_or_overwrite_newer_operation(self):
        m.save_json(m.STATE/'last-switch.json', {'unit': 'new-operation', 'profile': '1'})
        m.save_json(m.STATE/'last-restart.json', {'operationId': 'new-operation', 'status': 'restarted'})
        before = (m.STATE/'last-restart.json').read_bytes()
        with patch.object(m.subprocess, 'run', side_effect=AssertionError('must not restart')):
            self.assertEqual(m.restart('1', 'old-operation'), 0)
        self.assertEqual((m.STATE/'last-restart.json').read_bytes(), before)

    def test_switch_preserves_proxy_environment_for_delayed_daemon(self):
        with patch.dict(m.os.environ, {'HTTPS_PROXY': 'http://127.0.0.1:7897'}):
            with patch.object(m.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stderr='')) as run:
                result = self.api.switch('1', self.revision)
        args = run.call_args.args[0]
        self.assertIn('--setenv=HTTPS_PROXY=http://127.0.0.1:7897', args)
        self.assertEqual(args[-2:], ['--operation-id', result['operationId']])

    def test_later_manual_selection_is_not_confirmed_as_the_pending_switch(self):
        m.save_json(m.STATE/'last-switch.json', {'unit': 'op-1', 'profile': '1'})
        m.save_json(m.STATE/'last-restart.json', {'operationId': 'op-1', 'status': 'restarted'})
        result = self.api.list()
        self.assertEqual(result['restartStatus'], 'superseded_by_newer_switch')
        self.assertIn('selection-verification', result['restartError'])

    def test_failed_restart_scheduling_rolls_back_and_keeps_original_evidence(self):
        with patch.object(m.subprocess, 'run', return_value=SimpleNamespace(returncode=1, stderr='scheduler failure 42')):
            with self.assertRaises(RuntimeError) as caught: self.api.switch('1', self.revision)
        self.assertIn('scheduler failure 42', self.api.error(caught.exception)['message'])
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)

    def rpc(self, fail=False, conflict=False, close_fail=False, compat=False, account='other-account'):
        owner = self
        class RPC:
            def __init__(self, work):
                self.work = work
                self.errors = b'original inactive-original rotated-secret'
                self.truncated = False
                self.calls = []
            def send(self, value): pass
            def call(self, number, method, params):
                self.calls.append((method, params))
                if method == 'account/read': return {'account': {'type': 'chatgpt', 'email': 'fake@example.test', 'planType': 'plus'}}
                if method == 'account/rateLimits/read':
                    m.save_json(self.work/'auth.json', credential(account, 'rotated-secret'))
                    if conflict: m.save_json(m.PROFILES/'1/auth.json', credential('other-account', 'concurrent-secret'))
                    if compat and number == 3: raise m.RpcError({'code': -32602, 'message': 'invalid params excludeResetCreditDetails'})
                    if fail: raise m.RpcError({'code': 401, 'message': 'denied inactive-original', 'data': {'stage': 'quota'}})
                    if not compat: owner.assertEqual(params, {'excludeResetCreditDetails': False})
                    return {'rateLimits': {'primary': {'usedPercent': 25, 'windowDurationMins': 10080}}, 'rateLimitResetCredits': {'availableCount': 2, 'credits': []}}
                return {}
            def close(self):
                if close_fail: raise OSError('close failed')
        return RPC

    def test_query_isolated_and_rotated_credential_persisted_without_changing_active(self):
        with patch.object(m, 'RPC', self.rpc()): result = self.api.query('1', self.revision)
        self.assertEqual(result['quota']['rateLimits']['primary']['usedPercent'], 25)
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)
        self.assertEqual(m.read(m.PROFILES/'1/auth.json')[1]['tokens']['refresh_token'], 'rotated-secret')
        self.assertFalse(list(m.STATE.glob('phone-quota-*')))
        self.assertNotIn('rotated-secret', (m.STATE/'profile-1-phone-quota.stderr.log').read_text())

    def test_failed_query_retains_rpc_stage_suppressed_and_refresh(self):
        with patch.object(m, 'RPC', self.rpc(fail=True, close_fail=True)):
            with self.assertRaises(m.RpcError) as caught: self.api.query('1', self.revision)
        report = self.api.error(caught.exception)
        self.assertEqual(report['rpc']['code'], 401)
        self.assertEqual(report['stage'], 'query_rate_limits')
        self.assertEqual(report['suppressed'][0]['stage'], 'query_cleanup')
        self.assertIn('close failed', report['suppressed'][0]['message'])
        self.assertNotIn('inactive-original', json.dumps(report))
        self.assertEqual(m.read(m.PROFILES/'1/auth.json')[1]['tokens']['refresh_token'], 'rotated-secret')
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)

    def test_conflict_keeps_recovery_credentials_and_does_not_overwrite(self):
        with patch.object(m, 'RPC', self.rpc(conflict=True)):
            with self.assertRaises(api.ProfileError) as caught: self.api.query('1', self.revision)
        self.assertEqual(caught.exception.code, 'PROFILE_CHANGED')
        recovery = list(m.STATE.glob('phone-quota-*/auth.json'))
        self.assertEqual(len(recovery), 1)
        self.assertEqual(m.read(recovery[0])[1]['tokens']['refresh_token'], 'rotated-secret')
        self.assertEqual(m.read(m.PROFILES/'1/auth.json')[1]['tokens']['refresh_token'], 'concurrent-secret')

    def test_compat_fallback_keeps_reason(self):
        with patch.object(m, 'RPC', self.rpc(compat=True)): result = self.api.query('1', self.revision)
        self.assertEqual(result['compatibilityFallback']['rpc']['code'], -32602)

    def test_manual_backup_refresh_preserves_live_account_and_only_returns_metadata(self):
        owner = self
        class Rotate(self.rpc()):
            def call(self, number, method, params):
                if method == 'initialize': return {}
                owner.assertEqual((method, params), ('getAuthStatus', {'refreshToken': True, 'includeToken': False}))
                m.save_json(self.work / 'auth.json', credential('other-account', 'new-secret'))
                return {}
        with patch.object(m, 'RPC', Rotate):
            result = self.api.refreshToken('1', self.revision)
        self.assertEqual(result['status'], 'refreshed')
        self.assertNotEqual(result['profileInfo']['revision'], self.revision)
        self.assertNotIn('new-secret', json.dumps(result))
        self.assertEqual(m.read(m.PROFILES/'1/auth.json')[1]['tokens']['refresh_token'], 'new-secret')
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)
        with self.assertRaisesRegex(api.ProfileError, 'changed'):
            self.api.refreshToken('1', self.revision)

    def test_refresh_ack_without_credential_update_is_not_success(self):
        with patch.object(m, 'RPC', self.rpc()), self.assertRaises(api.ProfileError) as caught:
            self.api.refreshToken('1', self.revision)
        self.assertEqual(caught.exception.code, 'TOKEN_REFRESH_UNCONFIRMED')
        self.assertEqual(caught.exception.profile_stage, 'refresh_verify')

    def test_current_refresh_uses_live_socket_and_syncs_only_matching_slots(self):
        from unittest.mock import MagicMock
        self.api.socket = '/fixture/control.sock'
        current = self.api.list()['currentProfile']
        ws = MagicMock()
        connection = MagicMock()
        connection.__enter__.return_value = ws
        backup = (m.PROFILES/'1/auth.json').read_bytes()
        def send(raw):
            msg = json.loads(raw)
            if 'id' not in msg: return
            result = {}
            if msg['id'] == 2:
                self.assertEqual(msg['params'], {'refreshToken': False, 'includeToken': True})
                result['authToken'] = json.loads(self.active)['tokens']['access_token']
            if msg['id'] == 3:
                self.assertEqual((msg['method'], msg['params']), ('getAuthStatus', {'refreshToken': True, 'includeToken': False}))
                m.save_json(m.BASE/'auth.json', credential('active-account', 'new-live-secret'))
            ws.recv.return_value = json.dumps({'id': msg['id'], 'result': result})
        ws.send.side_effect = send
        with patch('websockets.sync.client.unix_connect', return_value=connection) as connect:
            result = self.api.refreshToken('current', current['revision'])
            connect.assert_called_once_with(self.api.socket, open_timeout=8, close_timeout=2)
        self.assertEqual(result['status'], 'refreshed')
        self.assertNotIn('new-live-secret', json.dumps(result))
        self.assertEqual(backup, (m.PROFILES/'1/auth.json').read_bytes())
        self.assertEqual((m.BASE/'auth.json').read_bytes(), (m.PROFILES/'2/auth.json').read_bytes())
        self.assertEqual(result['snapshots'], [{'profile': '2', 'snapshot': 'synced_newer_active'}])
        self.assertEqual(len(m.state_read()['activeFallback']), 1)
        with patch('websockets.sync.client.unix_connect', side_effect=AssertionError('stale revision must stop before connecting')):
            with self.assertRaises(api.ProfileError) as caught:
                self.api.refreshToken('current', current['revision'])
        self.assertEqual(caught.exception.code, 'PROFILE_CHANGED')

    def expiring_current(self, now):
        value = credential('active-account', 'near-expiry')
        claims = base64.urlsafe_b64encode(json.dumps({'iat': now - 3600, 'exp': now + 300}).encode()).decode().rstrip('=')
        value['tokens']['access_token'] = 'header.' + claims + '.signature'
        m.save_json(m.BASE/'auth.json', value)
        return value

    def test_active_fallback_only_near_expiry_through_codex_then_syncs_both(self):
        from unittest.mock import MagicMock
        now = 1795021200
        self.api.socket = '/fixture/control.sock'
        before = self.expiring_current(now)
        ws = MagicMock()
        context = MagicMock()
        context.__enter__.return_value = ws
        forced = []
        def send(raw):
            message = json.loads(raw)
            if 'id' not in message:
                return
            response = {}
            if message['id'] == 2:
                response['authToken'] = before['tokens']['access_token']
            if message['id'] == 3:
                forced.append(message['params'])
                updated = credential('active-account', 'renewed-current')
                updated['last_refresh'] = m.dt.datetime.fromtimestamp(now, m.dt.timezone.utc).isoformat()
                m.save_json(m.BASE/'auth.json', updated)
            ws.recv.return_value = json.dumps({'id': message['id'], 'result': response})
        ws.send.side_effect = send
        with patch.object(m.time, 'time', return_value=now), patch('websockets.sync.client.unix_connect', return_value=context):
            result = self.api.maintain_current()
            self.assertEqual(result['status'], 'refreshed')
            self.assertEqual(self.api.maintain_current()['status'], 'healthy')
        self.assertEqual(len(forced), 1)
        self.assertEqual((m.BASE/'auth.json').read_bytes(), (m.PROFILES/'2/auth.json').read_bytes())
        self.assertNotIn('renewed-current', json.dumps(result))

    def test_active_failure_persists_attempt_and_cause_across_restart(self):
        now = 1795021200
        self.api.socket = '/fixture/control.sock'
        self.expiring_current(now)
        denied = m.RpcError({'code': 403, 'message': 'policy rejection'})
        with patch.object(m.time, 'time', return_value=now), patch.object(self.api, 'refreshToken', side_effect=denied) as refresh:
            first = self.api.maintain_current()
            second = api.Profiles(m, '/fixture/control.sock').maintain_current()
            self.assertEqual(refresh.call_count, 1)
            self.assertEqual(first['status'], 'failed')
            self.assertEqual(first['diagnostic']['rpc']['code'], 403)
            self.assertEqual(second['status'], 'already_attempted_today')
            self.assertEqual(second['lastResult']['diagnostic']['rpc']['code'], 403)

    def test_active_sync_runs_even_after_daily_job_and_preserves_newer_library(self):
        with patch.object(self.api, 'refreshToken', side_effect=AssertionError('healthy token must not rotate')):
            result = self.api.maintain_current()
            self.assertEqual(result['status'], 'healthy')
            self.assertEqual((m.PROFILES/'2/auth.json').read_bytes(), self.active)
        newer = credential('active-account', 'newer-library')
        newer['last_refresh'] = '2026-11-02T00:00:00Z'
        m.save_json(m.PROFILES/'2/auth.json', newer)
        saved = (m.PROFILES/'2/auth.json').read_bytes()
        with patch.object(self.api, 'refreshToken', side_effect=AssertionError('do not rotate a stale version')):
            self.assertEqual(self.api.maintain_current()['status'], 'version_conflict')
        self.assertEqual((m.PROFILES/'2/auth.json').read_bytes(), saved)
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)

    def test_active_sync_disk_failure_retains_new_current_and_repairs_next_tick(self):
        latest = credential('active-account', 'codex-already-renewed')
        latest['last_refresh'] = '2026-11-18T12:00:00Z'
        m.save_json(m.BASE/'auth.json', latest)
        current = (m.BASE/'auth.json').read_bytes()
        original = m.atomic
        def fail_saved(path, raw):
            if path == m.PROFILES/'2/auth.json':
                raise OSError(28, 'No space for saved snapshot')
            return original(path, raw)
        with patch.object(m, 'atomic', side_effect=fail_saved):
            result = self.api.maintain_current()
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(result['diagnostic']['stage'], 'sync_current_snapshots')
        self.assertEqual(result['diagnostic']['errno'], 28)
        self.assertEqual((m.BASE/'auth.json').read_bytes(), current)
        with patch.object(self.api, 'refreshToken', side_effect=AssertionError('no second rotation')):
            self.assertEqual(self.api.maintain_current()['status'], 'healthy')
        self.assertEqual((m.PROFILES/'2/auth.json').read_bytes(), current)

    def test_sync_keeps_newer_unknown_and_equal_versions(self):
        path = m.PROFILES/'2/auth.json'
        for date, expected in [('2026-11-02T00:00:00Z', 'kept_newer_saved'), ('2026-11-01T12:00:00Z', 'version_order_unknown')]:
            auth = credential('active-account', 'saved-newer')
            auth['last_refresh'] = date
            m.save_json(path, auth)
            before = path.read_bytes()
            result = m.sync_active_snapshot(path, self.active, json.loads(self.active))
            self.assertEqual(result, expected)
            self.assertEqual(path.read_bytes(), before)

    def test_daily_uses_calendar_and_retains_failure_without_repeating(self):
        from datetime import datetime
        now = datetime(2026, 11, 19, 4, 1, tzinfo=m.BEIJING).timestamp()
        class Failure(self.rpc()):
            def call(self, number, method, params):
                if method == 'getAuthStatus':
                    raise m.RpcError({'code': 403, 'message': 'forbidden', 'data': {'reason': 'policy_rejected'}})
                return {}
        with patch.object(m.time, 'time', return_value=now), patch.object(m, 'RPC', Failure):
            result = self.api.maintenance()
            failed = next(row for row in result['results'] if row['profile'] == '1')
            self.assertEqual(failed['diagnostic']['rpc']['code'], 403)
            self.assertEqual(failed['diagnostic']['stage'], 'refresh_account')
            self.assertNotIn('expired', json.dumps(failed))
            with patch.object(m, 'RPC', side_effect=AssertionError('must not repeat')):
                self.assertEqual(self.api.maintenance()['status'], 'waiting')
        with patch.object(m.time, 'time', return_value=now + 86400 - 30), patch.object(m, 'RPC', Failure):
            self.assertEqual(self.api.maintenance()['results'][0]['status'], 'failed')
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)
        self.assertEqual((m.PROFILES/'2/auth.json').read_bytes(), self.active)

    def test_manual_refresh_commit_failure_retains_new_secret_and_cause(self):
        class Rotate(self.rpc()):
            def call(self, number, method, params):
                if method == 'getAuthStatus':
                    m.save_json(self.work/'auth.json', credential('other-account', 'new-secret'))
                return {}
        original_atomic = m.atomic
        target = m.PROFILES/'1/auth.json'
        before = target.read_bytes()
        def fail_commit(path, data):
            if path == target: raise OSError(28, 'disk full')
            return original_atomic(path, data)
        with patch.object(m, 'RPC', Rotate), patch.object(m, 'atomic', side_effect=fail_commit):
            with self.assertRaises(OSError) as caught: self.api.refreshToken('1', self.revision)
        report = self.api.error(caught.exception)
        self.assertEqual(report['errno'], 28)
        self.assertEqual(report['stage'], 'refresh_cleanup')
        self.assertEqual(target.read_bytes(), before)
        saved = list(m.STATE.glob('phone-quota-*/auth.json'))
        self.assertEqual(len(saved), 1)
        self.assertEqual(m.read(saved[0])[1]['tokens']['refresh_token'], 'new-secret')
        self.assertNotIn('new-secret', json.dumps(report))

    def test_new_invalid_expiry_is_preserved_for_recovery(self):
        class Invalid(self.rpc()):
            def call(self, number, method, params):
                if method == 'getAuthStatus':
                    auth = credential('other-account', 'new-secret')
                    claims = base64.urlsafe_b64encode(json.dumps({'exp': 1}).encode()).decode().rstrip('=')
                    auth['tokens']['access_token'] = 'header.' + claims + '.signature'
                    m.save_json(self.work/'auth.json', auth)
                return {}
        with patch.object(m, 'RPC', Invalid), self.assertRaises(api.ProfileError):
            self.api.refreshToken('1', self.revision)
        self.assertEqual(len(list(m.STATE.glob('phone-quota-*/auth.json'))), 1)
        self.assertEqual(m.read(m.PROFILES/'1/auth.json')[1]['tokens']['refresh_token'], 'inactive-original')

    def test_daily_403_and_401_are_preserved_without_declaring_expired(self):
        for code in (401, 403):
            owner = self
            class Rejected(self.rpc()):
                def call(self, number, method, params):
                    if method == 'getAuthStatus':
                        raise m.RpcError({'code': code, 'message': 'denied', 'data': {'reason': 'fixture'}})
                    return {}
            with patch.object(m, 'RPC', Rejected), self.assertRaises(m.RpcError) as caught:
                self.api.refreshToken('1', self.revision)
            detail = self.api.error(caught.exception)
            self.assertEqual(detail['rpc']['code'], code)
            self.assertEqual(detail['stage'], 'refresh_account')
            self.assertNotIn('expired', json.dumps(detail))
        class Timeout(self.rpc()):
            def call(self, number, method, params):
                if method == 'getAuthStatus': raise TimeoutError('request deadline reached')
                return {}
        with patch.object(m, 'RPC', Timeout), self.assertRaises(TimeoutError) as caught:
            self.api.refreshToken('1', self.revision)
        self.assertEqual(self.api.error(caught.exception)['type'], 'TimeoutError')

    def test_server_success_without_rotation_preserves_runtime_http_failure(self):
        class Silent(self.rpc()):
            def __init__(self, work):
                super().__init__(work)
                self.errors = b'Failed to refresh token status=403 denied inactive-original'
        with patch.object(m, 'RPC', Silent), self.assertRaises(api.ProfileError) as caught:
            self.api.refreshToken('1', self.revision)
        detail = self.api.error(caught.exception)
        self.assertEqual(detail['code'], 'TOKEN_REFRESH_UNCONFIRMED')
        self.assertEqual(detail['runtime']['httpStatuses'], ['403'])
        self.assertNotIn('inactive-original', json.dumps(detail))

    def test_daily_waits_before_four_and_recovers_attempt_after_crash(self):
        from datetime import datetime
        before = datetime(2026, 11, 19, 3, 59, tzinfo=m.BEIJING).timestamp()
        with patch.object(m.time, 'time', return_value=before), patch.object(m, 'RPC', side_effect=AssertionError('too early')):
            self.assertEqual(self.api.maintenance()['status'], 'waiting')
        m.save_json(m.STATE/'state.json', {'profiles': {'1': {'last_attempt': before + 61}}})
        with patch.object(m.time, 'time', return_value=before+120), patch.object(m, 'RPC', side_effect=AssertionError('already attempted')):
            report = self.api.maintenance()
        self.assertEqual(report['results'][0]['status'], 'already_refreshed_or_attempted_today')
        self.assertEqual(m.state_read()['remodexSchedule']['completedDay'], '2026-11-19')

    def test_duplicate_identity_is_automatically_attempted_at_most_once(self):
        from datetime import datetime
        m.save_json(m.PROFILES/'3/auth.json', credential('other-account', 'same-account-stale'))
        count = []
        class Fails(self.rpc()):
            def call(self, number, method, params):
                if method == 'getAuthStatus':
                    count.append(method)
                    raise m.RpcError({'code': 401, 'message': 'denied'})
                return {}
        with patch.object(m.time, 'time', return_value=datetime(2026, 11, 19, 4, tzinfo=m.BEIJING).timestamp()), patch.object(m, 'RPC', Fails):
            report = self.api.maintenance()
        self.assertEqual(len(count), 1)
        self.assertEqual(report['results'][-1]['status'], 'already_refreshed_or_attempted_today')

    def test_large_diagnostics_are_bounded_and_marked_without_losing_primary_code(self):
        report = api.bounded_diagnostic({'message': 'large failure', 'type': 'RpcError',
            'stage': 'query_rate_limits', 'rpc': {'code': 401}, 'stack': 'x' * 400000})
        self.assertTrue(report['truncated'])
        self.assertEqual(report['rpcCode'], 401)
        self.assertEqual(report['stage'], 'query_rate_limits')
        self.assertLess(len(json.dumps(report).encode()), 256 * 1024)

if __name__ == '__main__': unittest.main()
