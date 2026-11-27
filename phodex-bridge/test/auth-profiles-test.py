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

sys.path.insert(0, str(Path(__file__).parents[1] / 'src'))
api = load('profiles_api', Path(__file__).parents[1] / 'src/auth-profiles.py')
m = load('manager', Path(__file__).parents[1] / 'src/auth_store.py')
from auth_restarts import RestartError
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

    def test_manually_restarted_runtime_recovers_only_the_matching_selection(self):
        self.api.socket = str(m.BASE / 'test.sock')
        selection = {'profile': '2', 'unit': 'test-switch'}
        report = {'status': 'failed', 'operationId': 'test-switch', 'stage': 'daemon_restart', 'exit_code': 1}
        m.save_json(m.STATE / 'last-switch.json', selection)
        m.save_json(m.STATE / 'last-restart.json', report)
        history = (m.STATE / 'last-restart.json').read_bytes()
        with patch('auth_runtime.read_runtime_token', return_value=None):
            self.assertEqual(self.api.list()['restartStatus'], 'failed')
        with patch('auth_runtime.read_runtime_token', side_effect=TimeoutError('fixture runtime read timed out')):
            snapshot = self.api.list()
            self.assertEqual(snapshot['restartStatus'], 'failed')
            self.assertEqual(snapshot['runtimeVerification']['diagnostic']['stage'], 'runtime_account_reconciliation')
        with patch('auth_runtime.read_runtime_token', return_value=m.read(m.BASE/'auth.json')[1]['tokens']['access_token']):
            recovered = self.api.list()
            self.assertEqual(recovered['restartStatus'], 'ready')
            self.assertEqual(recovered['runtimeVerification']['previousStatus'], 'failed')
            self.assertEqual(recovered['restartHistory'][0]['failure']['exit_code'], 1)
            self.assertEqual(recovered['restartHistory'][0]['status'], 'recovered')
        self.assertEqual((m.STATE / 'last-restart.json').read_bytes(), history)
        m.save_json(m.STATE / 'last-switch.json', dict(selection, profile='1'))
        with patch('auth_runtime.read_runtime_token') as probe:
            self.assertNotEqual(self.api.list()['restartStatus'], 'ready')
            probe.assert_not_called()

    def test_failed_restart_reports_only_unique_live_library_profile(self):
        self.api.socket = str(m.BASE / 'test.sock')
        m.save_json(m.STATE / 'last-switch.json', {'profile': '2', 'unit': 'switch-id'})
        m.save_json(m.STATE / 'last-restart.json', {'status': 'failed', 'operationId': 'switch-id',
                    'stage': 'daemon_restart', 'exit_code': 1})
        _, other = m.read(m.PROFILES/'1/auth.json')
        other['tokens']['access_token'] += '-fixture-other-access'
        m.save_json(m.PROFILES/'1/auth.json', other)
        with patch('auth_runtime.read_runtime_token', return_value=other['tokens']['access_token']) as request:
            result = self.api.list()
            request.assert_called_once()
            self.assertEqual(result['restartStatus'], 'failed')
            self.assertEqual(result['runtimeVerification'], {'status': 'unconfirmed', 'operationId': 'switch-id',
                             'profile': '2', 'runtimeProfile': '1'})
            self.assertNotIn('fixture-other-access', json.dumps(result))
            m.save_json(m.PROFILES/'3/auth.json', other)
            self.assertNotIn('runtimeProfile', self.api.list()['runtimeVerification'])


    def reset_fixture(self, number='current'):
        path = (m.BASE if number == 'current' else m.PROFILES/number)/'auth.json'
        auth = m.read(path)[1]
        payload = base64.urlsafe_b64encode(json.dumps({'sub':'sample-user', 'iat':1700000000, 'exp':2000000000}).encode()).decode().rstrip('=')
        token = auth['tokens']['access_token'] = 'header.' + payload + '.signature'
        m.save_json(path, auth)
        quota = {'accountId': auth['tokens']['account_id'], 'rateLimitsByLimitId': {'codex': {
            'secondary': {'usedPercent': 100, 'windowDurationMins': 10080}}},
            'rateLimitResetCredits': {'availableCount': 1, 'credits': [
                {'id':'test-card', 'status':'available', 'resetType':'codexRateLimits', 'expiresAt':None}]}}
        writes = []
        def call(method, params, timeout=5):
            if method == 'getAuthStatus': return {'authToken': token}
            if method == 'account/rateLimits/read': return quota
            self.assertEqual(method, 'account/rateLimitResetCredit/consume')
            writes.append(params)
            return {'outcome':'reset'}
        self.api.owned = SimpleNamespace(call=call)
        return quota, writes, call

    def test_reset_card_zero_and_low_quota_confirmations(self):
        from auth_reset_cards import ResetCardError
        quota, writes, _ = self.reset_fixture()
        def step(ticket=None, card='test-card'):
            return self.api.reset_card('current',None,card,ticket)
        window=quota['rateLimitsByLimitId']['codex']['secondary']
        for remaining in (0, 0.5, 5, 5.1, 100):
            writes.clear()
            window['usedPercent'] = 100 - remaining
            first = step()
            self.assertFalse(writes)
            if remaining > 5:
                self.assertEqual(first['status'],'blocked')
                self.assertNotIn('ticket',first)
                continue
            self.assertEqual(first['status'],'confirm')
            ticket=first['ticket']
            if remaining:
                second=step(ticket)
                self.assertEqual(second['status'],'confirmAgain')
                self.assertFalse(writes)
                with self.assertRaises(ResetCardError): step(ticket)
                ticket=second['ticket']
            self.assertEqual(step(ticket)['status'],'reset')
            self.assertEqual(writes[0]['creditId'],'test-card')
            self.assertTrue(writes[0]['idempotencyKey'])
            with self.assertRaises(ResetCardError): step(ticket)
            self.assertEqual(len(writes),1)

    def test_reset_card_rechecks_current_quota_identity_and_card(self):
        from auth_reset_cards import ResetCardError
        quota,writes,_ = self.reset_fixture()
        def step(ticket=None):
            return self.api.reset_card('current',None,'test-card',ticket)
        window=quota['rateLimitsByLimitId']['codex']['secondary']
        ticket=step()['ticket']
        window['usedPercent']=98
        with self.assertRaises(ResetCardError) as changed: step(ticket)
        self.assertEqual(changed.exception.code,'RESET_RECHECK_REQUIRED')
        ticket=step()['ticket']
        window['usedPercent']=94
        self.assertEqual(step(ticket)['status'],'blocked')
        window.pop('usedPercent')
        with self.assertRaises(ResetCardError) as unknown: step()
        self.assertEqual(unknown.exception.code,'RESET_QUOTA_UNKNOWN')
        window['usedPercent']=100
        quota['accountId']='different'
        with self.assertRaises(ResetCardError) as mismatch: step()
        self.assertEqual(mismatch.exception.code,'RESET_ACCOUNT_CHANGED')
        quota['accountId']='active-account'
        quota['rateLimitResetCredits']['credits'][0]['expiresAt']=1
        with self.assertRaises(ResetCardError) as expired: step()
        self.assertEqual(expired.exception.code,'RESET_CARD_UNAVAILABLE')
        self.assertFalse(writes)

    def test_reset_card_expired_confirmation_and_missing_weekly_block_use(self):
        from auth_reset_cards import ResetCardError
        quota, writes, _ = self.reset_fixture()
        def step(ticket=None):
            return self.api.reset_card('current', None, 'test-card', ticket)
        ticket = step()['ticket']
        path = m.STATE/'reset-card-confirmations.json'
        entries = json.loads(path.read_text())
        entries[ticket]['expires'] = 0
        m.save_json(path, entries)
        with self.assertRaises(ResetCardError) as expired: step(ticket)
        self.assertEqual(expired.exception.code, 'RESET_RECHECK_REQUIRED')
        quota['rateLimitsByLimitId']['codex']['secondary']['windowDurationMins'] = 300
        with self.assertRaises(ResetCardError) as missing: step()
        self.assertEqual(missing.exception.code, 'RESET_QUOTA_UNKNOWN')
        self.assertFalse(writes)

    def test_reset_card_saved_profile_preserves_upstream_error_and_single_attempt(self):
        quota,writes,call = self.reset_fixture('1')
        revision=self.api.metadata('1',m.read(m.PROFILES/'1/auth.json')[1])['revision']
        class RPC:
            errors=b''
            truncated=False
            def __init__(self, work): pass
            def send(self, message): pass
            def close(self): pass
            def call(self,n,method,params):
                if method=='initialize': return {}
                result=call(method, params)
                if method=='account/rateLimitResetCredit/consume':
                    raise m.RpcError({'code':-32001,'message':'fake upstream rejection'})
                return result
        with patch.object(m,'RPC',RPC):
            first=self.api.reset_card('1',revision,'test-card',None)
            with self.assertRaises(m.RpcError) as failed:
                self.api.reset_card('1',revision,'test-card',first['ticket'])
            self.assertEqual(self.api.stage,'reset_consume')
            self.assertEqual(failed.exception.response['code'],-32001)
            self.assertEqual(len(writes),1)

    def test_draining_listener_receives_second_shutdown_on_the_original_pidfd(self):
        import auth_listener as direct
        import signal
        owner = {'mode': 'ssh', 'key': 'old', '_pid': 123}
        with patch.object(direct, 'inspect_direct', return_value=owner), \
             patch.object(direct.os, 'pidfd_open', return_value=77), patch.object(direct.os, 'close') as close, \
             patch.object(direct.signal, 'pidfd_send_signal') as send, patch.object(direct.select, 'poll') as poll, \
             patch.object(direct, 'listener', side_effect=ConnectionRefusedError):
            poll.return_value.poll.side_effect = [[], [(77, direct.select.POLLIN)]]
            direct.retire_direct('socket', m.BASE, 'old')
            self.assertEqual([c.args for c in send.call_args_list], [(77, signal.SIGTERM)] * 2)
            self.assertEqual([c.args for c in poll.return_value.poll.call_args_list], [(3000,), (12000,)])
            close.assert_called_once_with(77)

    def test_late_direct_restart_requires_both_stable_replacement_and_target_account(self):
        self.api.socket = str(api.RestartOperations(self.api).canonical_socket)
        ops = api.RestartOperations(self.api)
        record = dict(operationId='codex-auth-restart-1', kind='manual', expectedProfile='2',
                      status='failed', stage='direct_stop', _directKey='original',
                      failure={'type': 'TimeoutError', 'stage': 'direct_stop', 'message': 'old listener took 15s'})
        ops.save(record)
        raw = ops.records()[0]
        target = m.read(m.BASE/'auth.json')[1]['tokens']['access_token']
        for before, after, token, expected in [
                ('original', 'original', target, 'failed'), ('new', 'changed', target, 'failed'),
                ('new', 'new', None, 'failed'), ('new', 'new', target, 'ready')]:
            with self.subTest(before=before, after=after, token=bool(token)):
                cap = dict(mode='ssh', action='restart', key=before)
                with patch.object(api.RestartOperations, 'capability', return_value=cap), \
                     patch.object(api.RestartOperations, 'direct', return_value=dict(cap, key=after)), \
                     patch('auth_runtime.read_runtime_token', return_value=token):
                    result = self.api.list()
                self.assertEqual(result['restartStatus'], expected)
                self.assertEqual(result['restartHistory'][0]['failure'], record['failure'])
                self.assertEqual(ops.records()[0], raw)

    def test_filters_by_identity_not_token_bytes_and_returns_only_dates(self):
        result = self.api.list()
        self.assertEqual([x['id'] for x in result['profiles']], ['1'])
        self.assertIn('accessIssuedAt', result['profiles'][0])
        for forbidden in ('active-new', 'inactive-original', 'other-account', 'header.'):
            self.assertNotIn(forbidden, json.dumps(result))

    def test_codex_refresh_timestamps_list_and_query_without_rewriting_current(self):
        stamp = '2026-11-20T04:29:54.446202Z'
        active_path = m.BASE / 'auth.json'
        active = credential('active-account', 'active-new')
        active['last_refresh'] = stamp
        m.save_json(active_path, active)
        original = active_path.read_bytes()
        self.api.socket = str(m.BASE / 'fixture.sock')
        listing = self.api.list()
        self.assertEqual(listing['currentProfile']['lastRefresh'], stamp)
        row, = listing['profiles']
        with patch.object(m, 'RPC', self.rpc()):
            result = self.api.query(row['id'], row['revision'])
        self.assertEqual(result['quota']['rateLimits']['primary']['usedPercent'], 25)
        self.assertEqual(active_path.read_bytes(), original)

    def test_refresh_version_uses_timezone_and_fraction_instead_of_jwt_issue_time(self):
        utc = m.dt.timezone.utc
        for stamp, microseconds in [
            ('2026-11-20T04:29:54Z', 0),
            ('2026-11-20T04:29:54.4Z', 400000),
            ('2026-11-20T04:29:54.446202Z', 446202),
            ('2026-11-20T04:29:54.446202987Z', 446202),
            ('2026-11-20T12:29:54.446202987+08:00', 446202),
        ]:
            with self.subTest(stamp=stamp):
                auth = credential('one-account', 'fixture')
                auth['last_refresh'] = stamp
                expected = m.dt.datetime(2026, 11, 20, 4, 29, 54, microseconds, utc)
                self.assertEqual(m.credential_time(auth), expected.timestamp())

    def test_invalid_refresh_timestamp_keeps_list_stage_and_parse_cause(self):
        for stamp in ('not-a-date', '2026-11-20T04:29:54.446202', '2026-02-30T04:29:54Z'):
            with self.subTest(stamp=stamp):
                auth = credential('active-account', 'active-new')
                auth['last_refresh'] = stamp
                m.save_json(m.BASE / 'auth.json', auth)
                with self.assertRaisesRegex(ValueError, 'Invalid last_refresh timestamp') as caught:
                    self.api.list()
                report = self.api.error(caught.exception)
                self.assertEqual(report['stage'], 'list_profiles')
                self.assertEqual(report['cause']['type'], 'ValueError')
                self.assertTrue(report['cause']['message'])

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

    def test_empty_library_first_add_and_account_display_fallback(self):
        for number in self.api.registered():
            (m.PROFILES / number / 'auth.json').unlink()
            (m.PROFILES / number).rmdir()
        status = self.api.status()
        self.assertEqual((status['profiles'], status['nextProfileId']), ([], '1'))
        record = credential('test-account', 'not-a-display-value')
        payload = {'https://api.openai.com/profile': {'email': 'first@example.test'}}
        part = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip('=')
        record['tokens'].update(access_token='h.' + part + '.s', id_token='opaque')
        upload = m.BASE / 'remodex_uploads' / 'file.json'
        m.save_json(upload, record)
        result = self.api.import_profile('1', 'missing', upload, '')['profileInfo']
        self.assertEqual(result['email'], 'first@example.test')
        self.assertIsNone(result['accountName'])
        self.assertIsNone(result['displayName'])
        exported = self.api.export('1', result['revision'])
        self.assertEqual(Path(exported['path']).read_bytes(), upload.read_bytes())
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
        self.api.socket = str(api.RestartOperations(self.api).canonical_socket)
        with patch.object(api.RestartOperations, 'owner', return_value='restart'), patch.object(m.subprocess, 'run'):
            result = self.api.switch('3', rows[1]['revision'])
        self.assertEqual(result['status'], 'restart_scheduled')
        self.assertEqual(result['profile'], '3')
        self.assertIn('3', [row['id'] for row in self.api.list()['profiles']])
        m.save_json(m.BASE/'auth.json', credential('active-account', 'newer-credential-version'))
        with patch.object(api.RestartOperations, 'owner', return_value='restart'), patch.object(api.RestartOperations, 'command') as command:
            final = api.RestartOperations(self.api).run(result['operationId'])
        command.assert_not_called()
        self.assertEqual(final['failure']['code'], 'RESTART_SELECTION_CHANGED')
        self.assertEqual(m.read(m.BASE/'auth.json')[1]['tokens']['refresh_token'], 'newer-credential-version')

    def test_new_profile_query_uses_registry_without_changing_active_account(self):
        m.save_json(m.PROFILES/'3/auth.json', credential('third-account', 'third-private'))
        row = next(row for row in self.api.list()['profiles'] if row['id'] == '3')
        with patch.object(m, 'RPC', self.rpc(account='third-account')):
            result = self.api.query('3', row['revision'])
        self.assertEqual(result['profile'], '3')
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)

    def test_additional_files_are_discovered_but_links_and_noncanonical_ids_cannot_be_selected(self):
        m.save_json(m.PROFILES/'4/auth.json', credential('fourth-account', 'fourth-private'))
        (m.PROFILES / '5').symlink_to(m.PROFILES / '4', target_is_directory=True)
        self.assertIn('4', [row['id'] for row in self.api.list()['profiles']])
        for number in ('5', '6', '03', '3\n', '../3'):
            with self.assertRaises(api.ProfileError) as caught:
                self.api.target(number, '1:2')
            self.assertEqual(caught.exception.code, 'INVALID_PROFILE')

    def test_switch_defers_writing_authorization_until_the_worker_runs(self):
        self.api.socket = str(api.RestartOperations(self.api).canonical_socket)
        with patch.object(api.RestartOperations, 'owner', return_value='restart'), patch.object(m.subprocess, 'run') as run:
            result = self.api.switch('1', self.revision)
        self.assertEqual(result['status'], 'restart_scheduled')
        self.assertEqual(result['restartAfterSeconds'], 3)
        self.assertIn('--on-active=3s', run.call_args.args[0])
        self.assertIn('--property=ExitType=cgroup', run.call_args.args[0])
        self.assertIn('--property=Type=exec', run.call_args.args[0])
        self.assertEqual(result['operationId'], self.api.list()['operationId'])
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)
        self.assertEqual(self.api.list()['profiles'][0]['id'], '1')
        self.assertEqual(self.api.list()['restartStatus'], 'restart_scheduled')
        ops = api.RestartOperations(self.api)
        with patch.object(ops, 'owner', return_value='restart'), patch.object(ops, 'runtime_matches', return_value=True), patch.object(ops, 'command'):
            self.assertEqual(ops.run(result['operationId'])['status'], 'ready')
        self.assertEqual((m.PROFILES/'2/auth.json').read_bytes(), self.active)
        self.assertEqual(self.api.list()['profiles'][0]['id'], '2')

    def test_direct_restart_rechecks_listener_and_never_falls_through_to_daemon(self):
        ops = api.RestartOperations(self.api)
        self.api.socket = str(ops.canonical_socket)
        original = (m.BASE/'auth.json').read_bytes()
        with patch.object(ops, 'owner', return_value='direct'), patch.object(m.subprocess, 'run'), \
                patch.object(ops, 'direct', side_effect=[{'key': 'one'}, {'key': 'two'}]), patch.object(ops, 'command') as daemon:
            op = ops.schedule()
            rejected = ops.run(op['operationId'])
        self.assertEqual((rejected['status'], rejected['failure']['code']), ('failed', 'RESTART_RUNTIME_MISMATCH'))
        self.assertEqual((m.BASE/'auth.json').read_bytes(), original)
        daemon.assert_not_called()

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
        self.api.socket = str(api.RestartOperations(self.api).canonical_socket)
        with patch.dict(m.os.environ, {'HTTPS_PROXY': 'http://127.0.0.1:7897'}):
            with patch.object(api.RestartOperations, 'owner', return_value='restart'), patch.object(m.subprocess, 'run') as run:
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
        self.api.socket = str(api.RestartOperations(self.api).canonical_socket)
        error = m.subprocess.CalledProcessError(42, 'systemd-run', stderr='scheduler failure 42')
        with patch.object(api.RestartOperations, 'owner', return_value='restart'), patch.object(m.subprocess, 'run', side_effect=error):
            result = self.api.switch('1', self.revision)
        self.assertEqual(result['failure']['exitCode'], 42)
        self.assertEqual(result['failure']['stage'], 'restart_schedule')
        self.assertEqual(result['failure']['stderr'], 'scheduler failure 42')
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)

    def test_mac_switch_rechecks_launchd_owner_before_writing_or_kickstarting(self):
        ops = api.RestartOperations(self.api)
        self.api.socket = str(ops.canonical_socket)
        unit = {'mode': 'launchd', 'unit': 'gui/502/test.codex', 'key': 'old', 'action': 'restart'}
        with patch.object(sys, 'platform', 'darwin'), patch.object(ops, 'owner', return_value='launchd'), \
                patch.object(ops, 'direct', return_value=unit), patch.object(m.subprocess, 'Popen') as worker:
            pending = ops.schedule('1', self.revision)
            self.assertEqual(pending['restartAfterSeconds'], 3)
            self.assertIn('--wait-for-restart', worker.call_args.args[0])
            self.assertTrue(worker.call_args.kwargs['start_new_session'])
            with patch.object(ops, 'direct', return_value=dict(unit, key='another')), patch.object(m.subprocess, 'run') as kick:
                failed = ops.run(pending['operationId'])
                self.assertEqual(failed['failure']['code'], 'RESTART_RUNTIME_MISMATCH')
                self.assertEqual(failed['failure']['stage'], 'restart_preflight')
                kick.assert_not_called()
            self.assertEqual(ops.auth_path.read_bytes(), self.active)
            second = ops.schedule('1', self.revision)
            with patch.object(ops, 'runtime_matches', return_value=True), patch('auth_macos.wait_for_replacement') as verify, \
                    patch.object(m.subprocess, 'run') as kick:
                self.assertEqual(ops.run(second['operationId'])['status'], 'ready')
                self.assertEqual(kick.call_args.args[0], ['/bin/launchctl', 'kickstart', '-k', unit['unit']])
                verify.assert_called_once_with(ops.canonical_socket, {'unit': unit['unit'], 'key': 'old'})

    def test_mac_launchd_identification_requires_peer_uid_binary_and_matching_job_pid(self):
        import auth_macos as mac
        from subprocess import CompletedProcess
        for uid, name, listed_pid, printed_pid, expected in [
                ('502', 'codex', 42, 42, 'launchd'), ('501', 'codex', 42, 42, 'unknown'),
                ('502', 'Terminal', 42, 42, 'unknown'), ('502', 'codex', 77, 42, 'unknown'),
                ('502', 'codex', 42, 77, 'unknown')]:
            ps = f'{uid} Tue Nov 24 12:30:01 2026 /Applications/Test App/bin/{name}'
            with self.subTest(uid=uid, name=name, listed=listed_pid, printed=printed_pid), \
                    patch.object(mac, 'peer_pid', return_value=42), patch.object(mac.os, 'geteuid', return_value=502), \
                    patch.object(mac, 'output', side_effect=[ps, f'{listed_pid} 0 test.codex']), \
                    patch.object(mac.subprocess, 'run', return_value=CompletedProcess([], 0, f'\tpid = {printed_pid}\n', '')):
                info = mac.launchd_owner('/test/socket')
                self.assertEqual(info['mode'], expected)
                if expected == 'launchd':
                    self.assertEqual(info['unit'], 'gui/502/test.codex')
                else:
                    self.assertNotIn('action', info)

    def test_mac_unmanaged_external_listener_has_no_restart_action(self):
        ops = api.RestartOperations(self.api)
        self.api.socket = str(ops.canonical_socket)
        response = SimpleNamespace(stdout=json.dumps({'status': 'running', 'socketPath': self.api.socket, 'backend': None}))
        unknown = {'mode': 'unknown', 'reason': 'external_unmanaged'}
        with patch.object(sys, 'platform', 'darwin'), patch.object(ops, 'command', return_value=response), \
                patch('auth_macos.launchd_owner', return_value=unknown), patch.object(m.subprocess, 'Popen') as worker:
            self.assertEqual(ops.capability(), unknown)
            failed = ops.schedule('1', self.revision)
            self.assertEqual(failed['failure']['code'], 'RESTART_RUNTIME_UNMANAGED')
            self.assertEqual(ops.auth_path.read_bytes(), self.active)
            worker.assert_not_called()

    def test_host_manual_restart_can_start_a_stopped_daemon_without_writing_auth(self):
        ops = api.RestartOperations(self.api)
        self.api.socket = str(ops.canonical_socket)
        absent = m.subprocess.CalledProcessError(1, 'version', stderr='socket not found')
        running = SimpleNamespace(stdout=json.dumps({'status': 'running', 'socketPath': self.api.socket, 'backend': 'pid'}))
        with patch.object(ops, 'command', side_effect=[absent, absent, None, running]) as command, \
                patch.object(m.subprocess, 'run'), patch.object(ops, 'runtime_matches', return_value=True):
            pending = ops.schedule(allow_stopped=True)
            final = ops.run(pending['operationId'])
        self.assertEqual([call.args[0] for call in command.call_args_list], ['version', 'version', 'start', 'version'])
        self.assertEqual((final['status'], final['command'], final['kind']), ('ready', 'start', 'manual'))
        self.assertEqual((m.BASE/'auth.json').read_bytes(), self.active)

    def test_ambiguous_switch_timeout_retains_evidence_and_protected_original(self):
        ops = api.RestartOperations(self.api)
        self.api.socket = str(ops.canonical_socket)
        timed_out = m.subprocess.TimeoutExpired('restart', 120, stderr=b'active-new')
        with patch.object(ops, 'owner', return_value='restart'), patch.object(m.subprocess, 'run'):
            pending = ops.schedule('1', self.revision)
            with patch.object(ops, 'command', side_effect=timed_out), patch.object(ops, 'runtime_matches', return_value=True):
                final = ops.run(pending['operationId'])
        self.assertEqual(final['failure']['timeoutSeconds'], 120)
        self.assertEqual(final['failure']['stage'], 'daemon_restart')
        self.assertEqual(final['failure']['stderr'], '[REDACTED]')
        self.assertEqual(final['recovery']['status'], 'verification_required')
        self.assertEqual(m.read(m.BASE/'auth.json')[1]['tokens']['account_id'], 'other-account')
        retained = m.STATE/'restart-recovery'/pending['operationId']/'auth.json'
        self.assertEqual(retained.read_bytes(), self.active)

    def test_expired_worker_cannot_restart_after_a_new_manual_operation(self):
        ops = api.RestartOperations(self.api)
        self.api.socket = str(ops.canonical_socket)
        with patch.object(ops, 'owner', return_value='restart'), patch.object(m.subprocess, 'run'):
            first = ops.schedule()
            with self.assertRaises(RestartError) as busy:
                ops.schedule()
            self.assertEqual(busy.exception.code, 'RESTART_ALREADY_PENDING')
            record = ops.records()[0]
            record['_expires'] = 0
            ops.save(record)
            second = ops.schedule()
        with patch.object(ops, 'command') as command:
            self.assertIsNone(ops.run(first['operationId']))
        command.assert_not_called()
        self.assertEqual(second['previousOperationId'], first['operationId'])
        self.assertEqual(ops.history()[1]['failure']['code'], 'RESTART_QUEUE_EXPIRED')

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

    def test_confirmed_revocation_from_query_pauses_future_daily_attempts_for_same_token(self):
        from datetime import datetime
        at = datetime(2026, 11, 25, 4, 1, tzinfo=m.BEIJING).timestamp()
        class Revoked(self.rpc()):
            def call(self, number, method, params):
                if method == 'account/read':
                    raise m.RpcError({'code': -32603, 'message':
                        'Your access token could not be refreshed because your refresh token was revoked.'})
                return {}
        with patch.object(m, 'RPC', Revoked), self.assertRaises(api.ProfileError) as caught:
            self.api.query('1', self.revision)
        self.assertEqual(caught.exception.code, 'REFRESH_TOKEN_REVOKED')
        self.assertEqual(self.api.error(caught.exception)['cause']['rpc']['code'], -32603)
        row = self.api.status()['profiles'][0]
        self.assertEqual(row['lastMaintenance']['diagnostic']['code'], 'REFRESH_TOKEN_REVOKED')
        with patch.object(m.time, 'time', return_value=at), patch.object(m, 'RPC') as rpc:
            self.assertEqual(self.api.maintenance()['results'][0]['status'], 'skipped_revoked')
            rpc.assert_not_called()
        # Rewriting identical tokens or renaming the slot cannot clear the pause.
        m.save_json(m.PROFILES/'1/auth.json', credential('other-account', 'inactive-original'))
        with patch.object(m.time, 'time', return_value=at + 86400):
            self.assertEqual(self.api.maintenance(dry=True)['results'][0]['status'], 'skipped_revoked')
            uploaded = m.BASE/'remodex_uploads/replacement.json'
            m.save_json(uploaded, credential('other-account', 'new-authorization'))
            revision = self.api.status()['profiles'][0]['revision']
            self.api.import_profile('1', revision, str(uploaded))
            self.assertIsNone(self.api.status()['profiles'][0]['lastMaintenance'])
            self.assertEqual(self.api.maintenance(dry=True)['results'][0]['status'], 'would_refresh')

    def test_ansi_401_and_revoked_access_alone_do_not_pause_refresh(self):
        class Unauthorized(self.rpc()):
            def __init__(self, work):
                super().__init__(work)
                self.errors = (b'models error: auth error code: token_revoked\n'
                               b'Failed to refresh token \x1b[3mstatus\x1b[0m\x1b[2m=\x1b[0m401 Unauthorized')
        with patch.object(m, 'RPC', Unauthorized), self.assertRaises(api.ProfileError) as caught:
            self.api.refreshToken('1', self.revision)
        self.assertEqual(caught.exception.code, 'TOKEN_REFRESH_UNCONFIRMED')
        self.assertEqual(self.api.error(caught.exception)['runtime']['httpStatuses'], ['401'])
        self.assertFalse(list((m.STATE/'refresh-revocations').glob('*.json')))

    def test_explicit_refresh_log_persists_revocation_with_original_diagnostic(self):
        class Invalidated(self.rpc()):
            def __init__(self, work):
                super().__init__(work)
                self.errors = b'Failed to refresh token status=401 code=refresh_token_invalidated'
        with patch.object(m, 'RPC', Invalidated), self.assertRaises(api.ProfileError) as caught:
            self.api.refreshToken('1', self.revision)
        report = self.api.error(caught.exception)
        self.assertEqual(report['code'], 'REFRESH_TOKEN_REVOKED')
        self.assertEqual(report['cause']['code'], 'TOKEN_REFRESH_UNCONFIRMED')
        self.assertEqual(report['cause']['runtime']['httpStatuses'], ['401'])

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

    def test_manual_active_revocation_blocks_future_automatic_refresh(self):
        from unittest.mock import MagicMock
        now = 1795021200
        self.api.socket = '/fixture/control.sock'
        self.expiring_current(now)
        active = self.api.list()['currentProfile']
        token = m.read(m.BASE/'auth.json')[1]['tokens']['access_token']
        connection = MagicMock()
        ws = connection.__enter__.return_value
        ws.recv.side_effect = [json.dumps(message) for message in (
            {'id': 1, 'result': {}}, {'id': 2, 'result': {'authToken': token}},
            {'id': 3, 'error': {'code': -32603, 'message': 'Your refresh token was revoked.'}},
        )]
        with patch.object(m.time, 'time', return_value=now), patch('websockets.sync.client.unix_connect', return_value=connection):
            with self.assertRaises(api.ProfileError) as caught:
                self.api.refreshToken('current', active['revision'])
        report = self.api.error(caught.exception)
        self.assertEqual(report['code'], 'REFRESH_TOKEN_REVOKED')
        self.assertEqual(report['stage'], 'refresh_current_request')
        self.assertEqual(report['cause']['rpc']['code'], -32603)
        restarted = api.Profiles(m, '/fixture/control.sock')
        with patch.object(m.time, 'time', return_value=now + 86400), patch.object(restarted, 'refreshToken') as refresh:
            self.assertEqual(restarted.maintain_current()['diagnostic']['code'], 'REFRESH_TOKEN_REVOKED')
            refresh.assert_not_called()

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
