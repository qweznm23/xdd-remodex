# 安装 Remodex 服务端（Linux / macOS）

把服务端装在平时运行 Codex 的电脑上，Android 手机上安装同一 Release 的 APK。下面以手机和电脑连接同一个局域网为例。

## 准备电脑

1. 从 [fix166 发布页](https://github.com/qweznm23/xdd-remodex/releases/tag/v0.1.7-ws-fix166) 下载 `xdd-remodex-server-0.1.7-ws-fix166.tar.gz`，解压到长期使用的目录。中继和桥接所需的 Node 依赖已包含在包里。
2. 安装适合电脑芯片的 [Node.js 24](https://nodejs.org/en/download)。用平时使用 Codex 的账户打开终端，运行 `node -v` 确认显示 v24，再运行 `codex` 确认能正常进入已登录的会话，然后退出 Codex。
3. 查电脑的局域网 IPv4 地址。Linux 可以用 `ip -brief -4 address` 查看当前网卡，Mac 可以在「系统设置 → 网络 → 当前连接 → 详细信息 → TCP/IP」查看。下面所有 `192.168.1.20` 都换成这个地址。

## 启动中继（两种系统相同）

打开两个终端，**分别进入解压后含有 relay 和 phodex-bridge 的目录**。在第一个终端运行：

```sh
RELAY_BIND_HOST=192.168.1.20 PORT=10004 node relay/server.js
```

用手机浏览器访问 `http://192.168.1.20:10004/health`。能打开表示手机可以访问中继。如果打不开，检查两台设备是否在同一局域网，以及电脑防火墙是否允许该局域网访问 TCP 10004。

## 启动桥接并配对（选择电脑系统）

**Linux**：在第二个终端运行以下命令，再在电脑浏览器打开 `http://127.0.0.1:19092/` 查看二维码。无桌面的服务器可以在自己的电脑运行 `ssh -L 19092:127.0.0.1:19092 用户名@服务器地址`，再用本机浏览器打开配对页。

```sh
install -d -m 700 .local/runtime/workspace
cd .local/runtime/workspace
REMODEX_RELAY=ws://192.168.1.20:10004/relay node ../../../scripts/launch-bridge.cjs
```

**macOS**：在第二个终端运行以下命令，二维码会直接显示在终端中。示例关闭每日自动授权维护，仍可通过手机手动切换文件授权。

```sh
REMODEX_AUTH_SCHEDULE_ENABLED=0 REMODEX_RELAY=ws://192.168.1.20:10004/relay node phodex-bridge/bin/remodex.js run
```

在手机「设置 → 账户与授权」选择账号。桥接按当前连接识别运行方式，确认后倒计时 3 秒，切换完成时核对 Codex 实际使用的账号。重启可能中断当前任务。

- 默认的 `run` 或 `up` 由桥接为手机启动独立 Codex。切换会替换这个子进程；Mac 桌面上已打开的其他 Codex 实例有各自的运行状态。
- 通过 `REMODEX_CODEX_ENDPOINT` 共用本机 Codex 标准 Unix 套接字时，桥接可调用现有 Codex 守护进程，或重启已经核实由当前用户的 launchd 作业直接运行的 Codex。该模式需要桥接的 Python 环境已安装 `phodex-bridge/requirements.txt` 中的 websockets。
- 如果外部进程由桌面或终端直接启动、桥接无法核实其重启方式，手机会保留当前授权。在原启动程序中完成切换并重新打开 Codex，再刷新手机上的账号信息。TCP 远端连接继续由运行 Codex 的主机管理。Mac 的 launchd 和系统套接字识别仍待真机验证。

在 Mac 上验证账号切换与重启时，请按[真机验收步骤](macos-auth-restart-test.md)核对手机实际连接的 Codex。

在 Android 手机上打开 **Remodex 的扫码配对**，扫描电脑上的二维码，连接后打开一个会话。使用期间保持这两个终端运行。局域网 IP 改变后，按新地址重新启动并扫码。

### 从 Android 管理 Mac 桥接

fix77 在手机「设置 → 组件」中提供「保持 Mac 唤醒」。远程更新需要使用 XDD 的版本化服务端目录，并将桥接作为当前 macOS 用户的 LaunchAgent 运行。保留解压后的目录名，例如 `xdd-remodex-server-0.1.7-ws-fix77`。在该目录中，用 `node phodex-bridge/bin/remodex.js up` 代替上面的 `run`，沿用启动时的中继和授权维护环境配置。中继继续使用自己的启动方式。

点「更新 XDD 桥接」并确认后，桥接会检查 XDD 仓库的最新稳定 Release。找到更高版本时，它按发布资产的大小和 SHA-256 校验服务端包，将其解压到同级新目录，并安排使用新版 CLI 重启桥接。手机重新连接后可在组件信息中核对已安装的 XDD 版本。旧版目录保留，回退时可在旧目录执行 `node phodex-bridge/bin/remodex.js restart`。使用前台 `run` 或从源码启动时，可按下文的手动升级流程操作。

## 已有部署和可选功能

- 升级时，下载同一个 Release 的服务端包和 APK。先停止旧服务，把新包解压到新目录。沿用原运行账户、环境配置、配对状态路径和工作目录；使用包内默认路径的 Linux 用户应先将旧目录的 `.local` 保留到新目录。启动新服务并确认连接后，再安装新版 APK。
- 桥接默认启动当前账户的 `codex app-server`。要连接已有 Codex 桌面服务，可在启动桥接前设置 `REMODEX_CODEX_ENDPOINT`。地址格式见包内 `ops/runtime.env.example`。
- Linux 的 systemd 自启模板位于 `ops/systemd/`。图片处理和账号管理所需的 Python 依赖列在 `phodex-bridge/requirements.txt`。授权管理调用 `/usr/bin/python3`。

### 从中继分发 Android 更新

从 fix154 起，启动中继前设置 `REMODEX_UPDATE_DIR` 为发布目录的绝对路径。目录里放 `update.json` 和其中 `apkUrl` 指定的同目录 `remodex-*.apk`。手机沿用中继的主机和端口，请求 `/updates/update.json`。发布新包后会直接生效。

从独立下载服务迁移时，保留原目录、`UPDATE_BIND` 和 `UPDATE_PORT`，停止旧下载单元，再重启中继。原下载端口继续提供根路径下的更新清单、APK 和下载页。两个端口均验证成功后，禁用旧单元的开机自启。新部署只需开放中继端口。分开托管更新文件的部署可继续仅设置 `REMODEX_UPDATE_ORIGIN` 指向原更新源；同时设置时优先读取本地发布目录。
