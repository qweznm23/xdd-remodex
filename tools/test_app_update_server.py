import functools
import http.server
import importlib.util
import tempfile
import threading
import unittest
import urllib.request
import urllib.error
from pathlib import Path

spec = importlib.util.spec_from_file_location('server', Path(__file__).with_name('app-update-server.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RangeServerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.data = bytes(range(256)) * 1024
        Path(self.directory.name, 'test.apk').write_bytes(self.data)
        Path(self.directory.name, 'update.json').write_text('{}')
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(module.UpdateHandler, directory=self.directory.name))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f'http://127.0.0.1:{self.server.server_port}'
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.directory.cleanup()

    def get(self, headers=None, method='GET', path='/test.apk'):
        return self.opener.open(urllib.request.Request(self.base + path, headers=headers or {}, method=method), timeout=5)

    def test_full_and_resumed_file_match(self):
        with self.get() as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.read(), self.data)
            etag = response.headers['ETag']
        with self.get({'Range': 'bytes=1024-', 'If-Range': etag}) as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(self.data[:1024] + response.read(), self.data)
            self.assertEqual(response.headers['Content-Range'], f'bytes 1024-{len(self.data)-1}/{len(self.data)}')

    def test_suffix_exact_and_invalid_ranges(self):
        for value, expected in [('bytes=-32', self.data[-32:]), ('bytes=10-19', self.data[10:20])]:
            with self.get({'Range': value}) as response:
                self.assertEqual(response.status, 206); self.assertEqual(response.read(), expected)
        for value in ['bytes=999999-', 'bytes=10-2', 'bytes=-0', 'bytes=0-1,3-4', 'bytes=-']:
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                self.get({'Range': value})
            self.assertEqual(ctx.exception.code, 416)
            ctx.exception.close()

    def test_changed_file_restarts_and_head_has_no_body(self):
        with self.get({'Range': 'bytes=10-', 'If-Range': '"old"'}) as response:
            self.assertEqual(response.status, 200); self.assertEqual(response.read(), self.data)
        with self.get({'Range': 'bytes=0-31'}, method='HEAD') as response:
            self.assertEqual(response.status, 206); self.assertEqual(response.read(), b'')
            self.assertEqual(response.headers['Content-Length'], '32')
        with self.get(path='/update.json') as response:
            self.assertEqual(response.headers['Cache-Control'], 'no-store')

if __name__ == '__main__': unittest.main()
