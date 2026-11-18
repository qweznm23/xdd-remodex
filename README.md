# XDD Remodex

Android 客户端、Codex 桥接服务、中继及更新服务统一在这个 Git 仓库维护。
这是沿用原 Remodex Git 历史的维护仓库，保留 Apache-2.0 许可证及来源说明。当前功能以本仓库代码为准。
GitHub：[qweznm23/xdd-remodex](https://github.com/qweznm23/xdd-remodex)，公开 Fork。
来源与基线说明见 [Legal/UPSTREAM.md](Legal/UPSTREAM.md)。

## 首次安装

**[下载 Android 安装包（0.1.7-ws-fix73）](https://github.com/qweznm23/xdd-remodex/releases/download/v0.1.7-ws-fix73/remodex-0.1.7-ws-fix73.apk)** · [查看版本说明和 SHA-256](https://github.com/qweznm23/xdd-remodex/releases/tag/v0.1.7-ws-fix73)

在 Android 8.0 或更高版本的手机上打开下载的 APK。如果系统询问，允许当前浏览器或文件管理器安装此应用。已有服务端的用户可直接打开 Remodex，扫描电脑上的配对二维码。第一次部署服务端，请完成下面的第 1、2 步，然后跳到第 4 步配对。

**从源码自建**时，下面以 Linux 电脑和同一局域网内的 Android 手机为例。使用平时运行 Codex 的 Linux 用户，先确认该用户能在终端正常使用已登录的 `codex`。电脑准备 Git、Node.js 24 和 npm。

1. **下载源码并安装服务端依赖。**

   ```bash
   git clone --branch v0.1.7-ws-fix73 https://github.com/qweznm23/xdd-remodex.git
   cd xdd-remodex
   npm --prefix phodex-bridge ci --ignore-scripts
   npm --prefix relay ci --ignore-scripts
   install -d -m 700 .local/config .local/runtime/workspace
   ```

   这个标签包含上方 APK 对应的桥接和中继。也可以直接下载[同版服务端源码](https://github.com/qweznm23/xdd-remodex/archive/refs/tags/v0.1.7-ws-fix73.tar.gz)，解压后进入源码目录，从两条 npm 命令继续。

2. **在电脑上启动中继和桥接。** 打开两个终端，都进入刚克隆的仓库。下面的 `192.168.1.20` 代表电脑的局域网 IP，两处都替换成手机能访问的实际地址。

   第一个终端启动中继并保持运行：

   ```bash
   RELAY_BIND_HOST=192.168.1.20 PORT=10004 node relay/server.js
   ```

   第二个终端启动桥接并保持运行：

   ```bash
   cd .local/runtime/workspace
   REMODEX_RELAY=ws://192.168.1.20:10004/relay node ../../../scripts/launch-bridge.cjs
   ```

   桥接默认从当前用户的 PATH 启动 `codex app-server`。如果要连接已经运行的 Codex 桌面服务，把 `REMODEX_CODEX_ENDPOINT` 设为该用户实际的控制套接字地址，格式见 [运行配置示例](ops/runtime.env.example)。在手机浏览器打开 `http://192.168.1.20:10004/health` 可以检查手机到中继的连通性。

3. **可选：从源码构建测试 APK。** 在装有 JDK 21、Android SDK Platform 36、Build Tools 35.0.0 的 Linux 电脑上，从仓库根目录执行：

   ```bash
   install -d -m 700 .local/config
   cp ops/build.env.example .local/config/build.env
   ```

   编辑刚复制的文件，把 `JAVA_HOME` 和 `ANDROID_HOME` 改为本机路径。直接联网时删除示例里的两行 `GRADLE_PROXY_*`，自测时可留空 `APP_UPDATE_URL`。然后执行：

   ```bash
   ./scripts/build-android.sh :app:assembleDebug
   ```

   把生成的 `android/app/build/outputs/apk/debug/app-debug.apk` 发到手机安装。测试包使用本机调试签名，已有其他签名的同包名应用时 Android 会拒绝覆盖安装。需要保留原应用数据时请使用同签名的更新包。

4. **扫码配对。** 在运行桥接的电脑浏览器打开 `http://127.0.0.1:19092/`，用手机 Remodex 扫描页面上的二维码。使用无桌面服务器时，可在自己的电脑运行 `ssh -L 19092:127.0.0.1:19092 用户名@服务器地址`，再在自己电脑的浏览器打开同一地址。

需要开机自启时使用 [systemd 模板](ops/systemd/) 和 [运行配置示例](ops/runtime.env.example)。额外的授权管理与图片处理功能使用 [Python 依赖清单](phodex-bridge/requirements.txt)。配对页中的下载入口需要管理员另外部署更新服务并放入 APK，首次自建可直接使用第 3 步的产物。

## 目录

| 目录 | 用途 |
| --- | --- |
| `android/` | Android 客户端；版本号在 `app/build.gradle.kts` |
| `phodex-bridge/` | Codex 桥接、会话、文件传输、授权管理和自动维护 |
| `relay/` | 手机连接中继、更新转发和加密文件转发 |
| `tools/` | APK 更新下载服务及测试 |
| `scripts/` | 本机统一构建、测试、发布、启动和服务管理入口 |
| `ops/` | 可复用配置示例与 systemd 模板 |
| `.local/` | 本机配置、密钥、缓存、运行状态、安装包和历史归档；不进 Git |

## 日常操作

**升级已有部署**时，从目标 Release 取同一标签的服务端源码和 APK。先在服务端仓库运行 `git status --short` 并保存本地源码改动，再运行 `git fetch origin tag <目标标签>` 和 `git switch --detach <目标标签>`。重新执行上面两条 npm 命令，用原来的配置、工作目录和配对状态重启服务，确认桥接已连接后，再安装该 Release 的 APK。单独更新手机应用不会更新电脑上的服务端。后续发布新 APK 时，同步更新首页下载链接和安装命令中的标签。

在仓库根目录执行：

```bash
make status                       # 桥接、中继、更新下载服务状态
make test-server                  # 桥接、授权管理、中继和下载服务测试
make android-check                # Android 单元测试及发布必要 lint
make android-release              # 测试、发布检查、签名和压缩打包；不自动发布
./scripts/remodexctl restart-bridge
./scripts/remodexctl restart       # 重启三个 Remodex 服务，不重启 Codex
```

Android 配置放在 `.local/config/build.env`，参考 `ops/build.env.example`。
构建沿用项目 Gradle/JDK/JVM/worker 策略，通过共享 Android 验证锁排队。
`android/gradle.properties` 是可提交的构建配置；`android/key.properties` 和签名私钥不得提交。
签名配置在本机以链接接入 `.local/secrets/`，继续使用现有安装包签名。

发布前先构建。发布工具默认只检查，明确加 `--publish` 才更新下载目录和更新清单：

```bash
./scripts/publish-android.sh --notes '本次更新说明'
./scripts/publish-android.sh --publish --notes '本次更新说明'
```

发布入口检查版本递增、发布构建日志、测试报告及与上一版本一致的 APK 签名。
完整 lint 可单独执行 `./scripts/build-android.sh :app:lintRelease`。
整理时仓库已有完整 lint 错误，发布必要 lint 可以通过；不要把完整 lint 报告写成全通过。

## 本机部署与数据

三个现有 systemd 单元保持原服务名，由 `scripts/remodexctl` 统一管理。
实际配置在 `.local/config/`；模板在 `ops/systemd/`，替换占位路径后才能部署。
Node 运行时在 `.local/toolchains/node/`，与其他项目的临时工具目录分离。

`.local/runtime/` 保存配对身份和工作目录，`.local/artifacts/downloads/` 提供现有更新，
`.local/artifacts/releases/` 保存发布映射及校验记录，`.local/cache/` 保存缓存。
原测试脚本、日志和排障样本归入 `.local/archive/`；它们是历史资料，不作为新的维护入口。
旧 `/root/remodex-test` 路径仅保留兼容链接，避免旧脚本和会话路径失效。

Codex 当前授权及编号库仍由 Codex home 管理，不搬入源码或 Git。
授权维护规则、接口及诊断见 [AUTHORIZATION.md](phodex-bridge/AUTHORIZATION.md)。
配对凭据、真实授权、签名密钥、运行状态和 APK 均不得加入 Git。

首次在其他电脑部署，先安装 Android SDK/JDK、Node 和 Python 依赖，并按包锁文件安装两个服务的依赖；
不要直接复用本机 `.local` 的凭据或配对身份。仓库本身不包含这些内容。
服务目前调用 `/usr/bin/python3`，对应依赖清单是 `phodex-bridge/requirements.txt`，不要只安装到其他 Python 环境。
Node 依赖可用 `npm --prefix phodex-bridge ci --ignore-scripts` 和 `npm --prefix relay ci --ignore-scripts` 安装；Codex 运行时另行管理。

原 Git 的本机提交、分支和历史已沿用到当前仓库，原元数据备份、iOS/桌面代码及旧说明保存在 `.local/archive/upstream-source/`。
归档不参与提交；当前维护分支为 `main`，原工作分支也保留。原仓库的浅克隆已补全，可追溯原提交历史。
原上游地址保留为 `upstream`；`origin` 指向 `qweznm23/xdd-remodex`。推送前核对目标，不向原上游推送。
本机使用仅授权此仓库的部署密钥，保存在 `.local/secrets/`；SSH 配置仅写入本仓库 Git 配置，不修改全局配置。
