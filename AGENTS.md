# XDD Remodex 维护规范

AGENTS.md 与 CLAUDE.md 保持一致。本仓库独立维护 Android 客户端和服务端（Linux / macOS）。

## 工作区与 Git

- `android/` 是客户端；`phodex-bridge/` 是 Codex 桥接；`relay/` 是中继；`tools/` 是更新下载服务。
- 常用操作使用根目录 Makefile、`scripts/`，环境配置示例位于 `ops/`。以 README 为操作入口。
- 沿用原 Git 历史，维护分支为 `main`。原来源保留为 `upstream`；`origin` 指向用户的公开 Fork `qweznm23/xdd-remodex`。未经用户授权不推送，不向原上游推送。
- 修改前检查 Git 状态，保留其他工作，只提交当前任务和用户授权整理的内容；不得破坏性回退。
- `.local/` 是私有运行数据、密钥、缓存、安装包和历史归档，不得加入 Git。不要通过强制添加绕过忽略规则。
- `.local/archive/` 不是现行源码。原 Git 备份、旧平台代码和 fixXX 排障脚本仅供查阅，不继续在那里开发。
- 旧 `/root/remodex-test` 是兼容入口，现行代码和新维护脚本都在本仓库。
- 保留原许可证、来源及源文件版权声明。不要把旧上游代码误称为新写的实现。

Android Compose 主题、组件和界面验收使用 [remodex-m3e](.agents/skills/remodex-m3e/SKILL.md)，按组件迁移清单记录本批验证结果。

## 构建、验证与部署

- `make test-server` 验证服务端；`make android-check` 验证客户端；`make android-release` 测试并打包，但不会发布。
- Android 必须通过 `scripts/build-android.sh` 使用跨项目共享验证锁。不要并行另起一套 Gradle 构建。
- 保留项目 JDK、Gradle、AGP、Kotlin、JVM 参数、worker 和编译策略，非必要不改；Android 不套前端 build-limited。
- 编译、发布必要 lint、完整 lint、单元测试、真机检查与发布是不同结果，分别据实报告。已有完整 lint 错误不得说成通过。
- 发布要使用 `scripts/publish-android.sh` 的显式 `--publish`，校验版本递增、签名和包完整性，不自动覆盖安装设备。
- 服务管理使用 `scripts/remodexctl`。部署前保留可恢复的代码及配置步骤，不回滚新生成的授权和配对凭据。
- 本机交互式网页检查使用 Ubuntu :0 的可见浏览器，通过 linux-browser 或现有 CDP 9222 操作；不要另起隐藏浏览器，不暴露 CDP 端口。
- 服务器诊断和运维使用可审查的明文脚本文件，不编码或压缩脚本后交给 exec/eval 执行。

## 授权与错误处理

- Codex auth.json、编号库、签名密钥和配对身份不得写进源码、状态响应或日志。操作按既有主机和受保护文件传输边界处理。
- 当前凭据与编号库按账号身份和凭据版本比较，不能只比较文件修改时间。保留较新和无法比较的版本；规则见 phodex-bridge/AUTHORIZATION.md。
- 查看页面和读取状态不能强制刷新 Token。自动补刷新、手动刷新、同步和切换使用已有共享锁、原子写入及操作标识。
- 保留原始异常类型、消息、堆栈、cause、suppressed、阶段及 HTTP/RPC/业务错误码。清理和补偿失败不能覆盖首个错误。
- 不把 401、403、超时、解析错误、取消一律归为过期或网络异常；未知原因明确未知。取消继续传播，只在拥有超时的边界转换超时。
- 用户简短提示与诊断记录分开；诊断有界、截断明确标记。迟到回调不能覆盖较新的状态。
- 错误测试要验证原因链、错误码、阶段、取消和凭据恢复，不只检查成功/失败布尔值。

## 聊天与连接

- 保持工作目录、会话及消息身份边界，跨项目会话切换不能按当前仓库过滤掉其他内容。
- 保持已有配对、重连、实时消息与历史分页；不要通过重置配对、丢消息或大幅延迟更新来掩盖问题。
- 消息按稳定条目标识合并；恢复和迟到消息不能覆盖较新的会话状态。
- 不记录完整 relay sessionId 或其他 bearer 类标识，使用脱敏或哈希。
