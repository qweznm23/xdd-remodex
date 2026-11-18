# 维护约定

本仓库统一维护 Android 客户端、桥接、中继和更新服务。命令入口与目录职责见 README。

- 修改前查看 Git 状态，保留其他工作。按功能提交，说明行为变化、验证和未验证部分。
- 使用 `make test-server`、`make android-check` 验证相应组件；Android 构建通过共享锁串行。
- `make android-release` 只打包。发布需要显式执行发布脚本，不能把构建成功当作已发布。
- 真实凭据、签名配置、运行状态、缓存、安装包及排障记录不得提交，统一放在 `.local/` 或 Codex home。
- 不编辑 `.local/archive/` 来修改线上功能；现行服务代码位于 `phodex-bridge/`、`relay/`、`tools/`。
- 保留错误的操作、阶段、原始类型、错误码和原因链，敏感值脱敏；未知原因不要归类为令牌过期。
- 远程推送前核对 `git remote -v`，新的维护地址使用 `origin`；`upstream` 仅记录原来源，不向原上游推送。
