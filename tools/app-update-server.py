#!/usr/bin/env python3
"""Local APK distribution with single byte ranges, validators and bounded idle sockets."""
import argparse
import functools
import http.server
import os
import re


class UpdateHandler(http.server.SimpleHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def setup(self):
        super().setup()
        self.connection.settimeout(30)

    def end_headers(self):
        if self.path.split('?', 1)[0].endswith('update.json'):
            self.send_header('Cache-Control', 'no-store')
        super().end_headers()

    def send_head(self):
        self.byte_range = None
        path = self.translate_path(self.path)
        if not path.endswith('.apk') or not os.path.isfile(path):
            return super().send_head()
        file = open(path, 'rb')
        try:
            stat = os.fstat(file.fileno())
            total = stat.st_size
            etag = f'"{stat.st_mtime_ns:x}-{total:x}"'
            modified = self.date_time_string(stat.st_mtime)
            requested = self.headers.get('Range')
            if_range = self.headers.get('If-Range')
            if requested and if_range and if_range not in (etag, modified):
                requested = None
            start, end = 0, total - 1
            if requested:
                match = re.fullmatch(r'bytes=(\d*)-(\d*)', requested.strip())
                valid = bool(match and (match[1] or match[2]))
                if valid:
                    if match[1]:
                        start = int(match[1]); end = min(int(match[2]), total - 1) if match[2] else total - 1
                    else:
                        length = int(match[2]); start = max(0, total - length)
                    valid = 0 <= start <= end < total
                if not valid:
                    self.send_response(416)
                    self.send_header('Content-Range', f'bytes */{total}')
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    file.close()
                    return None
            self.send_response(206 if requested else 200)
            self.send_header('Content-Type', 'application/vnd.android.package-archive')
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('ETag', etag)
            self.send_header('Last-Modified', modified)
            self.send_header('Content-Length', str(end - start + 1))
            if requested:
                self.send_header('Content-Range', f'bytes {start}-{end}/{total}')
            self.end_headers()
            self.byte_range = (start, end - start + 1)
            return file
        except BaseException:
            file.close()
            raise

    def copyfile(self, source, outputfile):
        if self.byte_range is None:
            return super().copyfile(source, outputfile)
        start, count = self.byte_range
        if count:
            outputfile.flush()
            self.connection.sendfile(source, offset=start, count=count)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=19093)
    parser.add_argument('--directory', required=True)
    args = parser.parse_args()
    handler = functools.partial(UpdateHandler, directory=args.directory)
    http.server.ThreadingHTTPServer((args.bind, args.port), handler).serve_forever()
