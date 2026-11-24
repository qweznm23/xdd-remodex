# macOS 账号切换与重启验收

待验的是手机切换账号时，桥接能否在真实 Mac 上找到正确的 Codex、按原方式重启，并核实新进程实际使用的账号。Linux 上已经验证桥接子进程的替换和重连。自动化测试覆盖了 macOS 所有者匹配、拒绝误重启和启动命令，但其中的系统调用使用了模拟结果。Darwin 套接字返回的 PID、登录用户的 launchd 域和实际重启结果仍需在 Mac 上核实。

## 准备

使用包含本文的源码提交、Node.js 24、测试用 Android 客户端（fix161 或相应源码构建）以及专用的 Mac 登录用户。按服务端安装文档连接测试中继，在这个用户的独立 Codex 环境中准备两份可用的测试授权 A 和 B，关闭每日自动授权维护。所有操作以该登录用户执行。先在手机确认运行账号为 A，并发一条消息取得回复，作为切换前的对照。

在源码根目录先运行针对性测试。这两条测试命令可重复执行，使用测试夹具。

```sh
npm ci --prefix phodex-bridge --ignore-scripts
node --test phodex-bridge/test/auth-owned-child.test.js
/usr/bin/python3 phodex-bridge/test/auth-profiles-test.py -k test_mac_
```

共用 Unix 套接字的用例还需要桥接实际调用的 Python 能导入同步 WebSocket 客户端。

```sh
/usr/bin/python3 -c 'from websockets.sync.client import unix_connect'
```

## 从手机验证

| 场景 | 操作与通过条件 |
| --- | --- |
| 桥接自己启动 Codex | 分别用前台 `run` 和测试用户的 LaunchAgent `up` 启动桥接，保持默认的子进程连接。从「设置 → 账户与授权」切换 A 到 B。记录子进程重启前后 PID，确认桥接自身与其他 Codex 实例继续运行，手机最终显示运行账号 B，新消息能得到回复。再点一次「重启 Codex」，确认仍使用 B。 |
| 共用官方守护进程 | 将 `REMODEX_CODEX_ENDPOINT` 指向测试用户 Codex 的标准 Unix 控制套接字。核对桥接识别的启动方式，执行切换，验证手机恢复连接且目标账号与实际账号一致。 |
| 共用专属 launchd 作业 | 另用一个由当前用户的 launchd 作业直接启动、监听该标准套接字的 Codex。用 `launchctl list` 取得 PID 和标签，用 `launchctl print gui/$(id -u)/标签`（按实际域也可能是 `user/$(id -u)/标签`）核对同一作业。手机切换后，该作业应换成新 PID，并能继续对话。分别记录桥接和 Codex 的启动方式，以免混淆两个作业。 |
| 未受支持的外部进程 | 让独立终端启动的 Codex 监听测试套接字。核对桥接无法确认重启方式时，手机能说明原因，原账号与监听进程保持原状。再在独立测试作业中模拟确认后、执行前更换监听者，核对 `restart_preflight` 和 `RESTART_RUNTIME_MISMATCH`，并确认替代进程仍在、授权文件未被改写。 |

分别记录手机收到操作回执、开始重启、核实运行账号的时间。3 秒是触发重启前的等待时间，账号核实与恢复连接另行计时。遇到失败时，检查手机是否显示失败阶段、切换目标、已核实的实际账号（无法核实时应明确提示），以及与当前运行方式匹配的下一步操作。

## 回报

每个场景请回填源码短提交号、macOS 版本与芯片、Node/Python/Codex 版本、启动方式、前后 PID、三个时间点、运行账号 A/B 和首条消息结果。失败项附阶段、错误码和脱敏原因；无法搭建的场景注明未测及原因。账号用 A/B 标识，回报中省略令牌、授权文件、配对码和中继会话标识。
