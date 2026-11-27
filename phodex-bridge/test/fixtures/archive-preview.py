import stat
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1], "w", compression=zipfile.ZIP_DEFLATED) as archive:
    archive.writestr("聊天记录.txt", "聊天记录")
    archive.writestr("图片/图片.jpg", b"jpeg fixture")
    archive.writestr("目录/", b"")
    archive.writestr("../outside.txt", b"not readable")
    link = zipfile.ZipInfo("link.txt")
    link.create_system = 3
    link.external_attr = (stat.S_IFLNK | 0o777) << 16
    archive.writestr(link, b"/etc/passwd")
    archive.writestr("large.txt", b"x" * (256 * 1024 + 1))
    for i in range(65):
        archive.writestr("item-%d.txt" % i, str(i))
