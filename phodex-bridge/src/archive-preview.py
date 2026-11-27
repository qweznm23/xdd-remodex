"""Read metadata or one bounded member of an already-authorized ZIP on stdin."""
import json
import os
import stat
import sys
import traceback
import zipfile

PAGE_SIZE = 64
IMAGE_LIMIT = 8 * 1024 * 1024
TEXT_LIMIT = 256 * 1024


class PreviewError(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def describe(index, item, transport_limit):
    name = item.filename
    ext = os.path.splitext(name)[1].lower()
    kind = "none"
    if ext in {".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp"}:
        kind = "image"
    elif ext in {".txt", ".md", ".log", ".csv", ".json", ".xml", ".yaml", ".yml",
                 ".html", ".css", ".js", ".kt", ".java", ".py", ".sh", ".ini", ".toml"}:
        kind = "text"
    directory = item.is_dir()
    mode = stat.S_IFMT(item.external_attr >> 16) if item.create_system == 3 else 0
    unsafe = (item.orig_filename != name or any(ord(c) < 32 for c in name) or
              any(p in {"", ".", ".."} for p in name.rstrip("/").replace("\\", "/").split("/")) or
              ":" in name.split("/")[0] or len(name) > 1024)
    if (directory or unsafe or mode not in {0, stat.S_IFREG} or item.flag_bits & 1 or
            item.compress_type not in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED} or
            item.file_size > min(transport_limit, IMAGE_LIMIT if kind == "image" else TEXT_LIMIT) or
            item.compress_size > IMAGE_LIMIT + 65536):
        kind = "none"
    return dict(index=index, name=name[:1024], size=item.file_size, kind=kind, directory=directory)


def main():
    action, cursor, transport_limit = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
    # ZipFile materializes its central directory. Bound it before constructing ZipFile,
    # including ZIP64 archives (the stdlib end-record reader resolves the ZIP64 record).
    source = sys.stdin.buffer
    end = zipfile._EndRecData(source)
    if not end:
        raise zipfile.BadZipFile("ZIP end of central directory missing")
    if end[zipfile._ECD_SIZE] > 2 * 1024 * 1024 or end[zipfile._ECD_ENTRIES_TOTAL] > 8192:
        raise PreviewError("archive_directory_limit", "ZIP directory exceeds the preview limit")
    with zipfile.ZipFile(source) as archive:
        items = archive.infolist()
        if action == "list" and 0 <= cursor <= len(items):
            stop = min(len(items), cursor + PAGE_SIZE)
            rows = [describe(i, items[i], transport_limit) for i in range(cursor, stop)]
            page = dict(entries=rows, total=len(items), nextOffset=stop)
            sys.stdout.buffer.write(json.dumps(page, ensure_ascii=False).encode("utf-8"))
        elif action == "read" and 0 <= cursor < len(items):
            item = items[cursor]
            if describe(cursor, item, transport_limit)["kind"] == "none":
                raise PreviewError("archive_entry_unsupported", "This ZIP entry cannot be previewed")
            with archive.open(item) as member:
                data = member.read(item.file_size + 1)
            if len(data) != item.file_size:
                raise zipfile.BadZipFile("ZIP entry length differs from directory")
            sys.stdout.buffer.write(data)
        else:
            raise PreviewError("invalid_archive_offset", "Invalid ZIP entry or directory offset")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        code = getattr(error, "code", "invalid_zip" if isinstance(error, zipfile.BadZipFile) else "archive_read_failed")
        print(json.dumps(dict(code=code, message=str(error), stack=traceback.format_exc())), file=sys.stderr)
        sys.exit(1)
