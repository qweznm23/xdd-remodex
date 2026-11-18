# Upstream attribution

This project derives from Remodex, including its Android port, bridge and relay.

- Original checkout: https://github.com/Stivy-01/remodex.git
- Original Git HEAD before local maintenance: `1d5171ffbfffe541656c0cac24482942ca99d157`
- License: Apache-2.0; see the root LICENSE and retained source notices.

XDD Remodex retains the original checkout's available Git history and branches.
The original shallow checkout has been unshallowed from upstream.
Local Android and server work and the workspace reorganization continue from that
history. The original remote is retained as `upstream`; a new maintenance remote
is configured as `origin`: https://github.com/qweznm23/xdd-remodex (public fork).
Original metadata backups and unmaintained platform sources are in the private
host archive, outside the current tracked working tree.

Starting with fix74, the bridge and relay incorporate the original author’s
[Remodex](https://github.com/Emanuele-web04/remodex) through commit
`c06dcb16a7059603a41e26a2cb4a81b6d4b5514d`. The Android client continues
from the XDD fork.
