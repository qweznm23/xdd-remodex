# XDD Remodex

Android 客户端、Codex 桥接服务、中继及更新服务统一在这个 Git 仓库维护。
这是沿用原 Remodex Git 历史的维护仓库，保留 Apache-2.0 许可证及来源说明。当前功能以本仓库代码为准。
GitHub：[qweznm23/xdd-remodex](https://github.com/qweznm23/xdd-remodex)，公开 Fork。
来源与基线说明见 [Legal/UPSTREAM.md](Legal/UPSTREAM.md)。

## 首次安装

手机和电脑分别安装一个文件，两个文件取自[同一版本 fix140](https://github.com/qweznm23/xdd-remodex/releases/tag/v0.1.7-ws-fix140)。

| 在哪里安装 | 下载 | 安装后做什么 |
| --- | --- | --- |
| 运行 Codex 的电脑（Linux / macOS） | [下载服务端压缩包](https://github.com/qweznm23/xdd-remodex/releases/download/v0.1.7-ws-fix140/xdd-remodex-server-0.1.7-ws-fix140.tar.gz) | 解压，按[服务端安装指南](docs/server-install.md)启动中继和桥接，显示配对二维码 |
| Android 手机（8.0 及以上） | [下载 Android APK](https://github.com/qweznm23/xdd-remodex/releases/download/v0.1.7-ws-fix140/remodex-0.1.7-ws-fix140.apk) | 打开 APK 安装，按系统提示允许安装，再用 Remodex 扫描电脑上的二维码 |

两个系统使用同一个服务端包，包内安装说明在 `docs/server-install.md`。从 fix73 升级时请先保存 APK，再切换桥接和中继，最后覆盖安装手机应用。已有设备可以沿用原配对。[V2 升级与回退步骤](docs/v2-upgrade.md)

**网络与端口**：按指南部署时，手机和电脑桥接都通过 WebSocket 连接中继。聊天、配对后的连接和重连共用一个 TCP 入口，无需放行 UDP。

| TCP 端口 | 用途 | 谁需要访问 |
| --- | --- | --- |
| 10004 | WebSocket 中继、HTTP 健康检查 | 手机需要能访问电脑的这个端口 |
| 19092 | Linux 启动器的二维码网页 | 仅电脑本机。macOS 指南直接在终端显示二维码 |
| 19093 | 可选的 APK 下载和更新服务 | 手机使用独立下载直链时需要可达；配置中继转发时可仅供中继访问 |
| 19094 | 可选的文件传输服务 | 仅电脑本机，通过中继转发给手机 |

<details>
<summary>开发者：从源码部署或编译 Android 测试包</summary>

从 Release 对应标签检出源码，并安装两个服务的 Node 依赖：

```bash
git clone --branch v0.1.7-ws-fix140 https://github.com/qweznm23/xdd-remodex.git
cd xdd-remodex
npm --prefix phodex-bridge ci --ignore-scripts
npm --prefix relay ci --ignore-scripts
```

然后按[安装指南](docs/server-install.md)启动中继，并选用对应系统的桥接命令。

**可选：从源码构建测试 APK。** 在装有 JDK 21、Android SDK Platform 36、Build Tools 35.0.0 的 Linux 电脑上，从仓库根目录执行：

```bash
install -d -m 700 .local/config
cp ops/build.env.example .local/config/build.env
```

编辑刚复制的文件，把 `JAVA_HOME` 和 `ANDROID_HOME` 改为本机路径。直接联网时删除示例里的两行 `GRADLE_PROXY_*`，自测时可留空 `APP_UPDATE_URL`。然后执行：

```bash
./scripts/build-android.sh :app:assembleDebug
```

把生成的 `android/app/build/outputs/apk/debug/app-debug.apk` 发到手机安装。测试包使用本机调试签名，已有其他签名的同包名应用时 Android 会拒绝覆盖安装。需要保留原应用数据时请使用同签名的更新包。

</details>

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

从源码开发时，按包锁文件安装服务端依赖；编译 Android 时准备 Android SDK 和 JDK。
不要直接复用本机 `.local` 的凭据或配对身份。仓库本身不包含这些内容。
服务目前调用 `/usr/bin/python3`，对应依赖清单是 `phodex-bridge/requirements.txt`，不要只安装到其他 Python 环境。
Node 依赖可用 `npm --prefix phodex-bridge ci --ignore-scripts` 和 `npm --prefix relay ci --ignore-scripts` 安装；Codex 运行时另行管理。

原 Git 的本机提交、分支和历史已沿用到当前仓库，原元数据备份、iOS/桌面代码及旧说明保存在 `.local/archive/upstream-source/`。
归档不参与提交；当前维护分支为 `main`，原工作分支也保留。原仓库的浅克隆已补全，可追溯原提交历史。
原上游地址保留为 `upstream`；`origin` 指向 `qweznm23/xdd-remodex`。推送前核对目标，不向原上游推送。
本机使用仅授权此仓库的部署密钥，保存在 `.local/secrets/`；SSH 配置仅写入本仓库 Git 配置，不修改全局配置。
