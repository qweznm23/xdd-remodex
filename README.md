# XDD Remodex

Android 客户端、Codex 桥接服务、中继及更新服务统一在这个 Git 仓库维护。
这是沿用原 Remodex Git 历史的维护仓库，保留 Apache-2.0 许可证及来源说明。当前功能以本仓库代码为准。
GitHub：[qweznm23/xdd-remodex](https://github.com/qweznm23/xdd-remodex)，公开 Fork。
来源与基线说明见 [Legal/UPSTREAM.md](Legal/UPSTREAM.md)。

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
