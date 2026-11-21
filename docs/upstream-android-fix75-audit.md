# fix75 对照原作者 4.0.0 的 Android 功能复核

## fix77 补充（2026-11-19）

| 操作 | Android 与 XDD 桥接的处理 |
| --- | --- |
| 手机重新打开或从后台回来 | 连接就绪后刷新会话列表，并独立补读当前会话的最近历史。桌面替换了正在看的会话内容时，按正式消息标识合并，再使用服务端返回的新分页游标。 |
| 桌面调整会话列表 | 接收归档、恢复、删除事件，重新核对远端列表。无项目会话可按当前主机返回的目录分组。 |
| 文件与网络权限审批 | 显示本轮请求的权限，确认时仅返回所请求的范围，拒绝时返回空权限。 |
| 撤销与目标 | 单补丁撤销支持先看文件与冲突，再确认执行。当前会话有活动目标时提供目标详情入口。 |
| Mac 桥接管理 | 支持保持唤醒。使用 XDD 版本包托管的 Mac 桥接可检查并安装本仓库的正式服务端包。 |

离线推送按使用需求暂缓。在隔离的真实 Codex app-server 上断开模拟手机连接，由桌面发起一轮带模拟模型回复的对话，新连接随后通过原生历史分页读回了用户消息和助手回答。Android 回归测试也覆盖列表刷新出错后仍能补回正文。手机实际界面和 macOS 上的服务重启仍待设备验证。

## fix76 落地情况（2026-11-19）

下文的差异表记录 fix75 的行为。fix76 已接入以下 Android 入口：

| 入口 | fix76 的行为 |
| --- | --- |
| 打开会话 | 首屏先接收原生分页。当前视图缺少用户和助手正文时，沿已有游标自动补页；出现正文或附件、到达历史起点、退出界面或被刷新取代时停止。首屏窗口保留最近的正文。手动翻页继续使用已提交的游标。 |
| 压缩与目标 | 选择 `/compact` 发起当前会话的压缩。`/goal` 可读取目标，修改目标和预算，暂停、恢复和清除。 |
| 模型与推理强度 | 支持设置同步的桥接使用会话级设置写入、变更通知及重连快照。空模型列表可在选择器打开时补查。 |
| 归档与目录 | 恢复归档使用服务器确认。具备目录作用域时对连续缺席的非活跃会话单独核实；核实后的目录移除保留本地内容，服务器重新列出时可重新显示。 |
| 失败与审批 | 可恢复的模型连接错误提供人工「继续」。操作前检查最后一轮；自动审批审查显示状态及理由，现场有效且可表达的拒绝动作支持用户确认重试。 |
| 通知、正文、语音 | 完成提醒检查轮次、状态和回放标记；助手记忆引用标记在正文以外处理，代码示例保留；开始录音时可预热转写。 |
| 撤销 | 先使用检查点。只有精确捕获多段补丁才提供整批逆序预览和撤销；手机上的变更记录迁入 Android Keystore 保护的加密文件。 |

本次首屏个案通过 Codex 原生分页只读核对。反馈发生前连续 120 项为命令或思考，第 121 项为助手消息，按每页 30 项落在第五页。隔离 app-server 验证覆盖设置更新、目标、压缩请求受理、归档和恢复。压缩生成结果、桌面同步显示和手机实际滚动观感仍需端到端实测。

最终签名 Release 的 1012 项 Android 单元测试及 lintVitalRelease 通过，三个桥接测试文件共 45 项通过。Android 15 ReDroid 使用独立临时目录运行 Release 中的变更记录持久化代码，验证明文迁移、加密重读、篡改拒绝与保留原文件。APK 版本码为 93，签名与 fix75 一致。

2026-11-18 核对本地 main `af7026a` 与原作者 main `c06dcb1`。远端查询与本地 original/main 一致，merge-base 即原作者提交，CodexMobile 目录逐文件无差异。Android 交付的是 Kotlin 代码，Swift 用作对照。此前的 [fix73 对照及提交索引](upstream-comparison-2026-11-18.md) 描述合并前状态。

本轮重读共同祖先以来的普通提交标题，用两个客户端的请求与事件差集查候选，再查操作入口、状态处理、桥接和原有回归。结论限于 Codex 的 Android 日常操作及两端协作。服务器代管和连接已有 app-server 的路径分别判断。性能收益、所有 SwiftUI 行为以及生产手机上异常事件的发生率未经本轮测量。

## 两个可以用相同输入验证的差异

| 操作 | Android fix75 | 原作者实现与结论 |
| --- | --- | --- |
| 输入 `/comp`，点选压缩上下文 | 候选提供 `/compact `，替换输入片段后，点击处理又追加一次。按 Release 类与点击表达式得到草稿 `/compact /compact`。正式入口没有发起压缩 RPC。Android 本身能展示历史中的压缩记录。[候选](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnConversationPaneAutocomplete.kt) · [点击处理](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnConversationPane.kt) | iOS 选择后通过 `thread/compact/start` 压缩当前会话。此请求在共同祖先中已存在，属于原有客户端差异。应修正 Android 菜单动作，并验证选中目标会话、仅执行一次、保留原草稿。[原作者入口](../CodexMobile/CodexMobile/Services/CodexService+ThreadsTurns.swift) |
| 后台收到 `turn/completed`，负载中却明确给出进行中或未知状态 | 实际调用 Release 通知分类器，`inProgress`、`futureStatus` 均得到 Completed，`completed` 得到 Completed，`interrupted` 不产生完成提醒。Android 有前台抑制和进程内 60 秒去重。这里复现的是分类结果，通知实际出现还取决于后台状态、系统权限等。[事件入口](../android/app/src/main/kotlin/com/remodex/mobile/data/IncomingEventRouter.kt) · [分类](../android/app/src/main/kotlin/com/remodex/mobile/core/notification/TurnCompletionNotificationLogic.kt) | iOS 对显式状态仅接受已知成功值，并检查历史事件、完成时间及所跟踪轮次。旧服务省略状态有单独兼容。若移植，保留真实失败和正常完成，同时防止重连回放再次通知。[原作者通知策略](../CodexMobile/CodexMobile/Services/CodexService+Notifications.swift) |

## 已经覆盖和仍有差别的操作

| 功能 | 已核实的 Android 行为 | 仍有的差别 |
| --- | --- | --- |
| 会话模型和推理强度 | 会话自己的元数据优先于全局默认，显式手机选择优先；发送时使用该会话所选参数。已有 8 项 Release 回归通过。[选择及测试](../android/app/src/test/kotlin/com/remodex/mobile/services/ThreadRuntimeSelectionTest.kt) | 发送前的两端设置联动独立于打开时显示。iOS 发 `thread/settings/update`；桥接观察运行时 `thread/settings/updated`，给手机发 `remodex/runtimeSettings/updated`。Android 未接这条主动写入和通知链路。桌面 UI 的实时效果仍取决于运行方式。[桥接](../phodex-bridge/src/bridge.js) · [iOS](../CodexMobile/CodexMobile/Services/CodexService+RuntimeSettingsSync.swift) |
| 模型列表 | Android 用 `model/list`；进入新草稿或会话且列表为空时可重查。[运行时](../android/app/src/main/kotlin/com/remodex/mobile/services/CodexServiceRuntime.kt) | iOS 连着服务、列表为空且没有正在加载的请求时，打开模型选择器即可重试。Android 在同一会话中单独重新点选择器没有对应触发。无需重做列表或模型同步。[原作者](../CodexMobile/CodexMobile/Services/CodexService+RuntimeConfig.swift) |
| 归档与恢复 | Android 可以归档、查看和选择归档记录。归档页的选择操作只设活动会话。分组归档先改本地再发远端请求，该入口现有 runCatching 没有向调用方返回 RPC 失败。[归档页](../android/app/src/main/kotlin/com/remodex/mobile/ui/archived/ArchivedChatsScreen.kt) · [归档服务](../android/app/src/main/kotlin/com/remodex/mobile/services/CodexServiceThreadGroupOperations.kt) | iOS 有明确的取消归档操作并发 `thread/unarchive`。这一操作在共同祖先中已有。Android 产品入口缺这一反向操作。若补，远端失败与本地显示应分别处理。[原作者操作](../CodexMobile/CodexMobile/Views/Sidebar/ArchivedChatsView.swift) |
| 名称和删除同步 | Android 有 `thread/name/updated` 处理，保留项目排序、置顶和最近列表。[Android 路由](../android/app/src/main/kotlin/com/remodex/mobile/data/IncomingEventRouter.kt) | iOS 读取桥接发出的目录作用域并核实可能消失的会话。现桥接连接外部 endpoint 时作用域为空。不能凭一次分页缺席移除会话；也不能直接推定该机制会改善当前外接部署。[桥接作用域](../phodex-bridge/src/bridge.js) · [iOS 目录检查](../CodexMobile/CodexMobile/Services/CodexService+ThreadsTurns.swift) |
| 手机重连、发送失败、回复中途失败 | 手机切网自动重连已有真机验收；fix75 已有原会话消失时保留草稿和人工决定去向的实现。[验收](upstream-4-migration-plan.md) | iOS 另有针对本轮已终止且符合可恢复条件的「继续」。会在原会话发起新一轮。Android 可手动发「继续」，缺的是按失败状态提供的快捷操作。[原作者](../CodexMobile/CodexMobile/Services/CodexService+StreamRecovery.swift) |
| Goals | 普通消息、建议和计划模式均可用。Android 未找到原生目标查询、设置、清除与状态展示入口。 | iOS 支持按会话查看目标、设置预算、暂停和恢复。属独立产品功能，适合持续执行的长任务。需按实际运行时能力显示。[原作者](../CodexMobile/CodexMobile/Services/CodexService+ThreadGoal.swift) |
| 审批与历史问答 | Android 已有普通审批、结构化问答和提醒。 | 上游新加的是自动审查状态、拒绝理由，以及服务端提供有效重试信息时的人工操作。历史记录不会自行获得新审批权限。[原作者](../CodexMobile/CodexMobile/Services/CodexService+AutoApprovalReview.swift) |
| 消息正文 | Android 的用户和助手聊天气泡均能渲染 Markdown，另有搜索引用附件。[渲染](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnMessageRow.kt) | iOS 对助手正文中的 Codex 记忆来源块另有过滤，能处理流式半个标签并保留代码示例。Android 未找到对应过滤器。实际是否看到原始块取决于输出，本次未复现真机画面。[原作者解析](../CodexMobile/CodexMobile/Views/Turn/Messages/AssistantMemoryCitationParser.swift) |
| 语音 | Android 使用 `voice/transcribe`，凭据由桥接处理。[Android 转写](../android/app/src/main/kotlin/com/remodex/mobile/services/CodexServiceVoice.kt) | iOS 录音流程可先调 `voice/prewarm`。当前服务端已有预热；Android 没有该触发。只对使用语音的等待时间有潜在收益，未测端到端时延。[原作者录音](../CodexMobile/CodexMobile/Views/Turn/Voice/VoiceInputCoordinator.swift) |
| 多段修改的撤销 | Android 撤销服务走检查点或单个统一补丁；手机本地保存变更记录。Git 管理入口遵循 XDD 的关闭策略。[撤销](../android/app/src/main/kotlin/com/remodex/mobile/services/AiChangeSetRevertService.kt) · [功能开关](../android/app/src/main/kotlin/com/remodex/mobile/core/config/FeatureFlags.kt) | 缺少聚合补丁、但捕获了多批补丁时，iOS 可逆序预览和执行整批撤销；这条路径在共同祖先中已有。新增加密只针对本地变更记录。这两项可独立暂缓。[原作者撤销](../CodexMobile/CodexMobile/Services/CodexService+AIChangeSets.swift) |
| 电脑交接和排队 | Android 已有「在电脑继续」、唤醒和按会话的本地 FIFO 草稿队列。服务端已合入 Desktop IPC 处理电脑排队追问的代码。[手机交接](../android/app/src/main/kotlin/com/remodex/mobile/services/DesktopHandoffService.kt) · [手机队列](../android/app/src/main/kotlin/com/remodex/mobile/data/TurnDraftQueueStore.kt) | IPC 队列广播面向桌面，独立运行与外接 endpoint 启用的模块不同。仅凭手机使用本地队列，不能判定原作者 iOS 已能查看、编辑一份跨端共享队列。此项不列为应移植的缺失功能。[桥接 owner](../phodex-bridge/src/desktop-ipc-live-owner.js) |

## 保留的分支取舍与证据范围

- Android 使用官方历史分页入口，桥接专门识别 Android 请求。iOS 对部分历史缺口的 rollout 补读走另一条路径。保留 Android 常规路径；未做同一台设备同一会话的首屏、滚动帧率或图文重复率实验，不能判定哪端整体更快。[历史入口](../phodex-bridge/src/bridge.js) · [Android 合并](../android/app/src/main/kotlin/com/remodex/mobile/data/HistoryMessageMerge.kt)
- 手机 APK 更新、IP 和自选端口、HTTP/WS、多授权文件和文件流继续按 XDD 部署使用。iOS 的 Mac 保持唤醒设置及手机触发桥接包更新，在共同祖先已有；它们与 Android APK 更新是不同操作。当前 Linux 自托管服务使用配套服务端包，不宜直接套用 Mac 的管理按钮。[桌面操作](../CodexMobile/CodexMobile/Services/DesktopHandoffService.swift) · [部署](server-install.md)
- 本轮运行设置存储、Codex 设置参数和语音处理三个 Node 测试文件，45 项通过。Android 使用现有 Release 类和合成输入检查命令补全与通知分类；模型 8 项取自已有 Release XML。另用本机 codex-cli 0.169.0 生成含实验项的 schema，核实其中声明压缩、取消归档、目标、设置更新和 Guardian 人工重试。协议声明不证明每台服务已开放能力。本轮未操作生产会话，未做手机、桌面与 Mac 的联测。
