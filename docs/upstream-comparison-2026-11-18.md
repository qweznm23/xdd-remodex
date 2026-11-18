# 原作者更新与 XDD fix73 对照（2026-11-18）

本次比较的是原作者 main 的 `c06dcb1`、XDD 的 `46136e1`，共同祖先是 `e63cf05`。XDD 这一侧相对 fix73 标签的后续提交只改了文档。原作者一侧独有 238 个提交，其中 226 个普通提交、12 个合并提交。相对共同祖先，原作者改动的路径中有 331 个在 CodexMobile、99 个在桥接、9 个在中继。原作者桥接包版本为 4.0.0，XDD 为 1.5.6 加本地修改；版本数字不能代表 Android 的功能差距。[全部 238 个提交索引](upstream-2026-11-18.csv) · [原作者基线](https://github.com/Emanuele-web04/remodex/tree/c06dcb1) · [XDD 基线](https://github.com/qweznm23/xdd-remodex/tree/46136e1)

**取舍**：优先对照桥接私有请求、语音凭据的旧接口和失败后的消息归属。历史、列表和渲染以 Android 已有实现为基线，先移植上游的故障场景，再决定是否移植代码。新功能中，原生会话目标边界最清楚；OpenCode 和桌面 IPC 主控权交接都需要独立设计。配对变更列在索引里，本轮维持此前约定的暂缓状态。

## 这段时间更新了什么，Android 要看哪部分

从 5 月 22 日的共同祖先到 11 月 3 日的原作者 main，下面按每个普通提交实际触及的目录计数。iOS 指 `CodexMobile/`，服务端指 `phodex-bridge/` 或 `relay/`。一个提交可以同时修改说明或其他目录。12 个合并提交另计，避免重复统计。

| 触及 iOS | 触及服务端 | 普通提交数 | XDD 的对照方式 |
| --- | --- | ---: | --- |
| 否 | 是 | 71 | 审桥接和中继的实现、依赖、测试，再判断 XDD 两种运行模式是否走这条路径 |
| 是 | 是 | 67 | 对照两端的请求、响应、事件和降级，再到 Android 查对应的消费入口 |
| 是 | 否 | 86 | 筛选与平台无关的行为和回归场景；SwiftUI、Dynamic Island 等原生实现不直接移植 |
| 否 | 否 | 2 | 单独检查说明、仓库配置等改动 |

所以有 **138 个普通提交触及服务端**，其中 67 个也触及 iOS。原作者 main 没有 Android 工程。查看服务端改动时要结合这 67 个提交里的手机调用方；对其余 86 个客户端提交，还应挑出消息归属、历史合并、交互恢复等产品行为。目录重叠仅用于筛选，不能直接解释为协议变化，提交数也不等于新增功能数。


## 长期跟进原作者的分支方案

如果目标是今后持续吸收原作者的更新，建议把原作者 main 作为**服务端基线**，继续用现有 XDD 仓库交付 Android 和服务端。在 Git 中保留每次采用的上游提交作为合并提交的父节点。这样下次可以从上一次合入点比较新增改动；GitHub 页面显示的直接 Fork 来源不决定这件事。

首次迁移可从 XDD main 开候选分支，对固定的原作者提交做普通双亲合并。候选中的桥接和中继以原作者实现为起点，逐项接回 XDD 的接口；android 目录延续现有历史。原作者的 CodexMobile 建议随上游原样保留在 Git，用于阅读客户端协议和接受后续合并，服务端打包继续只选运行所需目录。这样能减少我们反复删除而对方持续修改 iOS 文件产生的冲突。此为候选目录策略，尚未修改工作区结构。

| 阶段 | 可交付结果 | 通过条件 |
| --- | --- | --- |
| 1. 固定基线和范围 | 记录 XDD 稳定标签 fix73、候选的原作者 SHA、桥接包版本和传输版本。用测试工作区与独立配对状态运行候选 | 2026-11-18 的 merge-tree 预演显示 234 个冲突路径，223 个在 CodexMobile，桥接 4 个、中继 1 个。预演中的 Android 树与当前 main 完全相同。无 Git 冲突的文件也要审查协议和行为 |
| 2. 打通最小客户端 | 原作者桥接 4.0.0 加 Android 候选版先跑一个完整会话 | fix73 安全传输为 v1，原作者当前实现仅接受 v2；两边二维码格式均为 v2。以原作者协议样例验证握手、收发、断线恢复和重放缺口，保留实时消息。旧 fix73 和旧服务继续成套使用，候选包和候选桥接单独验证 |
| 3. 接回 XDD 功能 | 给每组本地扩展列出手机入口、RPC、服务端处理和回归用例 | 依次验证原生历史分页与问答、模型和消息状态、文件/媒体/更新下载、建议回复、授权与额度。授权使用测试账号文件。已有上游实现更好的部分沿用上游；纯 XDD 功能尽量通过独立模块接入 |
| 4. 配套发布和回退 | 同一 Release 提供候选验证过的 APK、服务端、协议兼容说明，保留旧 Release | 在 linux 外接 endpoint 和自行启动两种模式验证；macOS 支持范围按实测公布。切换前核对客户端和服务端版本；回退按匹配的整套版本及相应状态执行，Android 原地降版受版本码和数据兼容约束 |
| 5. 每轮同步 | 在同步候选分支执行 fetch，比较上次合入 SHA 与新 SHA；测试通过后把普通合并历史带回 main | 服务端、协议和 Android 需要跟进的行为分别记录；用能力或版本区分兼容范围。紧急修复可单挑并记原始 SHA，定期基线同步保留上游亲缘，避免 squash 丢失合入点 |

当前本地远程名为 original（原作者）、upstream（Stivy Android 来源）、origin（XDD 发布仓库）。基线同步示例，以本轮确定的 SHA 为例：

```bash
git fetch --no-tags original main
git merge-base main original/main
git log --oneline main..original/main -- phodex-bridge relay
# 在独立候选工作区的 XDD 同步分支执行
git merge --no-ff --no-commit c06dcb16a7059603a41e26a2cb4a81b6d4b5514d
# 完成冲突处理和验证后创建合并提交，并在发布说明中记录上游 SHA
```

## 哪边修得更好

**先采用上游的分页错误处理；保留 XDD 的 Android 问答语义。** 发送失败的会话归属和旧语音接口的凭据边界也值得向上游对齐。以下比较用 XDD `46136e1` 和原作者 `c06dcb1`，结论限定到对应入口和场景。

### 同一输入的双版本对照

两份源码解压到独立目录，使用同一个 Node.js 24 和合成数据，分别调用两边实际导出的函数。结果不涉及生产会话或真实凭据。

| 相同输入与观测点 | XDD fix73 | 原作者 main | 裁决 |
| --- | --- | --- | --- |
| 官方分页函数请求第一页，正常请求和缩小页重试都抛出 `-32001` | 调用两次，返回成功结果 `data=[]、nextCursor=null` | 调用两次，保留 `-32001` 和原错误消息 | **上游更好，优先采用。** 失败和“确实没有更多历史”是两种结果，手机需要据此决定是否允许重试。只移植错误传播，使真实空页仍成功、有可用数据的降级仍能返回；无须同时接入 JSONL 补全。 |
| JSONL 中的一次异步问答，带两道题、选项和固定 call ID | 输出 `agentMessage`，题目数组与输入一致，ID 保留 | 输出 `tool_call`，ID 保留，没有同格式的顶层 questions 数组 | **对 XDD 现有 Android 问答合同，我们更好。** 若借用上游的历史恢复，应保留这个转换，并验证补回的历史项不能冒充待答请求。该试验只证明解析器差异，不代表原作者 iOS 无法显示问答，也不意味着 XDD 正常分页使用 JSONL。 |
| 同一份 session_meta，先用短首行，再给首行添加 96 KiB 无关字段；调用读取 cwd 的文件入口 | 短首行成功，长首行取不到 cwd；最多读 64 KiB | 两次都成功；默认读头部上限 256 KiB | **长首行容错上游更好，普通首行两边相当。** XDD 的上限更小。尚无线上首行分布或耗时数据，不建议把常见路径一律改读 256 KiB。合理的组合是保留小窗口，仅在首条记录不完整时有界扩读。 |
| 对旧版语音授权函数提供同一模拟 ChatGPT 账号响应 | 调用账号接口 1 次，返回含 token 字段的结果 | 调用账号接口 0 次，返回 `update_required` | **按凭据留在主机的要求，上游更好；按旧手机可用性，XDD 更宽容。** 保留当前 `voice/transcribe`，以明确的升级提示退役旧语音取凭据接口。变更前回归当前 Android 和承诺兼容的旧版本。不要影响独立、显式授权的文件导出功能。 |

复核入口依次为 `fetchAdaptiveThreadTurnsListForRelay`、`parseSessionJsonlTurns`、`readSessionJsonlMetadataFromFile`、`resolveVoiceAuth`。分页使用 limit=2、固定 threadId 与总是抛出同一错误的 fetchPage；问答使用 XDD 的 [session-jsonl-history 测试](../phodex-bridge/test/session-jsonl-history.test.js) 中的 async input 样例。这里只运行了函数对照，没有模拟手机 UI 或断言现网中某次缺失由此引起。

### 其余重叠修复的裁决

| 场景 | 哪边更合适，依据是什么 | 采取什么做法 |
| --- | --- | --- |
| 原会话在服务端已经不存在，手机发送或发起 review | **保持用户选定的会话身份，上游更好（源码）。** 上游移除了遇到缺失后自动新建并转发的分支；XDD 的 [发送入口](../android/app/src/main/kotlin/com/remodex/mobile/services/CodexServiceTurn.kt) 和 [review 入口](../android/app/src/main/kotlin/com/remodex/mobile/services/CodexServiceReview.kt) 仍可切换到 continuation。按钮状态按 threadId 保存不能约束底层最终发送的目标。 | 采用上游的失败归属，保留 XDD 的重试 UI。如要续聊，明确让用户选择创建新会话。A→B 异步回调是另一个测试场景，本次未在两台手机上复现。 |
| 桥接内部请求超时后的迟到回包；中继请求超限 | **上游的错误边界更完整（源码）。** 私有响应识别不依赖 waiter 仍在；中继的超限处理给写出 HTTP 413 留出机会。XDD 当前分别有 waiter 缺失后放行和构造 413 后销毁请求的分支。 | 分开移植，分别从正常边界断言迟到消息不会送给手机，以及调用方实际收到 HTTP 状态和业务码。本次没有拿生产请求做验证。 |
| 正常大历史第一页，与官方页暂缺最后一轮 | **没有单一赢家。** fix73 直接走官方分页，常规路径简单；上游对“已完成纯文本缺失”有更强的恢复手段，另外包含有界读取与合并成本。 | 保留 XDD 正常路径，借上游的末轮身份、时间和去重校验。拿真实大小的脱敏样例同时测首屏、重复率和数据完整性后决定是否启用。 |
| 桌面改名／确认删除，与 Android 项目顺序、置顶和问答 | **双向目录同步上游覆盖更细，现有 Android 展示与交互保留 XDD（源码）**。上游对删除结论有作用域；XDD 的稳定项目排序、置顶折叠及历史问答服务于不同需求。 | 保留列表 UI，只借用可证明的桌面变更；不能用“当前页缺席”删除本地记录。 |
| 重复初始化、空闲心跳、推送状态表 | **上游对明确的重复初始化、减少多余 ping、给状态表设硬上限的设计更有针对性（源码）。** 当前部署确实走外接 endpoint 与推送跟踪器。 | 借用窄范围改法，测重叠初始化只返回一次、休眠恢复、长会话状态不被过早淘汰。实际耗电、RSS 和断线发现时间尚无 A/B 数据。 |
| 滚动帧率、模型选择、图文合并、当前会话与桌面共同编辑 | **暂不能判整体优劣。** Swift 和 Compose 不是同一渲染路径；获得模型列表、发出所选模型、桌面显示更新也不是同一结果。上游的 live-owner 和增量 IPC 解决另一种运行方式，不能凭上游基准给现网直接判优。 | 留用 XDD 的手机实现，借上游的混合图文、模型回写、迟到事件等回归样例；有失败证据再改。对 IPC 只在实际启用该路径的部署上做性能比较。 |

功能新增不作“修得更好”计分。例如 OpenCode 和原生目标是产品能力选择；XDD 的授权维护、文件传输也不能作为其历史分页更正确的证据。新增功能取舍见第二节。

## 一、双方处理过的问题，修到了哪一层

| 问题 | XDD 已有行为 | 原作者的差异与建议验证 |
| --- | --- | --- |
| 大会话首屏、历史分页 | 桥接请求官方 `thread/turns/list`、`thread/items/list`；查 cwd 时只读取 JSONL 头部最多 64 KiB。Android 有加载进度、页合并和滚动锚点。 | 上游既优化了 JSONL 字符串的逐行解析，也给“倒序第一页缺最新已完成纯文本轮次”加了有条件的补全。前者不会让 XDD 官方分页自动变快；后者可单测。构造第一页缺一轮、下一次读又包含该轮的情况，验证只出现一次并保留游标。保留官方分页，不重建全量索引。[0660196](https://github.com/Emanuele-web04/remodex/commit/0660196) · [c36269f](https://github.com/Emanuele-web04/remodex/commit/c36269f) |
| 历史与实时消息合并、图文消失 | Android 的 HistoryMessageMerge、MessageTimelineStore 和 ThreadHistoryDecoder 已处理历史回填、稳定顺序和媒体。 | 上游覆盖助手临时 ID 转成正式 ID、完成后混合图片消息仍保留、回到已关闭会话时重新核对历史。测试“文字先到、图片后到、历史使用新 ID”及“离开期间完成、回来才看到官方结果”。这些可直接借用样例，Swift 合并器无需移植。[f03bee8](https://github.com/Emanuele-web04/remodex/commit/f03bee8) · [4bee370](https://github.com/Emanuele-web04/remodex/commit/4bee370) · [c7778e4](https://github.com/Emanuele-web04/remodex/commit/c7778e4) |
| 正文掉帧、大差异渲染 | Compose 已有 TimelineDisplayFlow、Markdown 缓存、历史插入与底部跟随逻辑。 | 上游在 Swift/UIKit 按帧揭示增量 Markdown，并对大型 diff 的详细渲染设限。可借“长表格、长 diff、边滚边收流”的输入比较掉帧、首个可见增量延迟和锚点漂移。不能将上游 iOS 帧率视作 Android 收益。[0a374c7](https://github.com/Emanuele-web04/remodex/commit/0a374c7) · [d492e01](https://github.com/Emanuele-web04/remodex/commit/d492e01) |
| 会话列表、运行状态、项目分组 | XDD 已有已读状态、置顶/最近列表、项目稳定排序、状态刷新保护。重命名有 `thread/name/set` 入口。 | 上游补充活动投影、旧会话归组、桌面改名和确认删除。特别是上游的删除判断有作用域，在外接 endpoint 下主动不发本地删除作用域。不能把某一页没出现的会话当作已删除。验证桌面改名、跨项目切换、分页缺席、回到前台后的状态。[97c3989](https://github.com/Emanuele-web04/remodex/commit/97c3989) · [90ab463](https://github.com/Emanuele-web04/remodex/commit/90ab463) · [bbbb23f](https://github.com/Emanuele-web04/remodex/commit/bbbb23f) |
| 发送失败、切换会话、交互问答 | XDD 有附件上传重试、消息重发；TurnConversationPane 的重试状态以 threadId 和设备为键。问答区分待答和已提交，发起、解码、记录回复也分阶段。 | 上游强调 A 会话的发送错误在切到 B 后仍归 A，关闭一个输入面板也不能吞掉另一条异步错误。按会话保存一个 Compose 状态并不能单独证明切回后还能恢复失败项。先测 A 发消息→切 B→A 失败→回 A，及两条并发输入中一条失败。区分“用户消息没有送达”和“服务端已开始但流中断”，避免重复开轮。[669f677](https://github.com/Emanuele-web04/remodex/commit/669f677) · [4c131cf](https://github.com/Emanuele-web04/remodex/commit/4c131cf) · [248ca96](https://github.com/Emanuele-web04/remodex/commit/248ca96) |
| 模型、推理强度和运行配置 | XDD 从 `model/list` 获取模型，已有会话级模型选择、运行配置 UI 和兼容处理。 | 上游还做了远端模型选择器重试、桌面输入框的模型/强度回写、运行配置与 Desktop IPC 同步。应分开验证“能列出”“发给运行时的值正确”“电脑 UI 同步”；这三个并非同一能力。[9c6c9fb](https://github.com/Emanuele-web04/remodex/commit/9c6c9fb) · [3eeec7c](https://github.com/Emanuele-web04/remodex/commit/3eeec7c) · [7ca6e0f](https://github.com/Emanuele-web04/remodex/commit/7ca6e0f) |
| 桥接私有 RPC 与语音转写 | XDD 用 waiter 映射截获桥接内部响应，超时会删除 waiter；手机通常用 `voice/transcribe`，另保留 `voice/resolveAuth` 旧路径。 | 上游即使 waiter 已超时也识别桥接私有响应，另将旧语音接口改为明确要求更新。XDD 当前找不到 waiter 会返回 false，旧语音接口也确实存在。应优先验证私有迟到响应的最终去向；用合成数据测试。淘汰旧语音路径前先列出支持的旧客户端，保留“旧版需升级”的明确错误。不能据静态差异断言已发生信息泄露。[bc2575f](https://github.com/Emanuele-web04/remodex/commit/bc2575f) · [caf7874](https://github.com/Emanuele-web04/remodex/commit/caf7874) |
| 资源限制与错误语义 | XDD 的中继 JSON 请求已有 64 KiB 上限及 413 错误对象；超限分支同时调用 req.destroy()。文件传输另外有独立通路。 | 上游修正了“服务端构造了 413，调用方却只收到连接关闭”。应验证客户端实际收到的状态、业务码，以及独立文件接口不受影响。此项不需要提高上传限制。[3a84d9b](https://github.com/Emanuele-web04/remodex/commit/3a84d9b) |
| 内存、心跳、桌面同步开销 | XDD 当前部署连接已有 Unix WebSocket；自行启动 Codex 的部署才走 stdout 解析、rollout 实时镜像和桌面 IPC follower。推送 tracker 则仍观察发出的消息。 | 推送 Map 的数量上限与当前路径相关；上游静默才探测的心跳可以测空闲流量和失联发现时间。IPC 分片线性读取和增量投影适合自行启动模式。此前四模块临时试移植的 45 项测试只验证兼容性，没有证明现网性能收益。[0660196](https://github.com/Emanuele-web04/remodex/commit/0660196) · [dc4e8c1](https://github.com/Emanuele-web04/remodex/commit/dc4e8c1) · [1f75373](https://github.com/Emanuele-web04/remodex/commit/1f75373) |
| 初始化与桌面并发 | XDD 按连接类型区分冷/热初始化，会记录已经初始化的响应。 | 上游还对重叠初始化的返回值做归一，并显式区分外接 app-server 和委托运行模式。可在已连接的会话里测试两个初始化重叠，只将协议明确的重复初始化认作可恢复，其他错误原样返回。本项与扫码建立信任是不同阶段。[8fcad25](https://github.com/Emanuele-web04/remodex/commit/8fcad25) · [81e0b77](https://github.com/Emanuele-web04/remodex/commit/81e0b77) |

XDD 核对入口：[桥接调度与分页](../phodex-bridge/src/bridge.js) · [JSONL 读取](../phodex-bridge/src/session-jsonl-history.js) · [Android 历史合并](../android/app/src/main/kotlin/com/remodex/mobile/data/HistoryMessageMerge.kt) · [消息发送与重试](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnConversationPane.kt) · [交互问答](../android/app/src/main/kotlin/com/remodex/mobile/services/ConversationQuestionService.kt) · [语音调用](../android/app/src/main/kotlin/com/remodex/mobile/services/CodexServiceVoice.kt) · [中继 HTTP](../relay/server.js)。

## 二、上游新增或明显扩展的能力

“未见”指在固定的 XDD 源码中未找到对应的完整入口，不能据此推断所连 Codex 自身不支持该协议。

| 能力 | 上游做到了什么 | Android 是否值得引入 |
| --- | --- | --- |
| 原生会话目标 | iOS 有查看、设置、清除目标的 RPC，以及更新通知、暂停和恢复交互。XDD 的模型选择、建议回复和普通对话均不等于目标管理。 | **最适合先做产品原型。** 先探测所连 Codex 是否支持 `thread/goal/get`，以 threadId 存状态，换会话后不能串目标；旧服务端隐藏入口。再加设置和暂停/恢复。[e4afef8](https://github.com/Emanuele-web04/remodex/commit/e4afef8) · [314d045](https://github.com/Emanuele-web04/remodex/commit/314d045) |
| 中断后继续执行 | 上游区分可恢复的流中断，提供继续操作。XDD 已有发送失败重试，但“没发出”和“已执行、流断了”语义不同。 | **比新增模型供应商更贴近当前使用。** 先读取服务端轮次状态，若仍运行就恢复订阅；只对已确认可恢复的中断显示继续。验收时检查不会把上一条用户消息发两遍。[248ca96](https://github.com/Emanuele-web04/remodex/commit/248ca96) |
| 桌面与手机共同操作同一轮 | 上游实现 live owner、只读投影、操作交接、队列与运行配置同步；XDD 有较早的 action follower，没有整套新模块。 | 对频繁在电脑和手机间切换有价值，但属于子系统。先在独立实验中验证“谁拥有当前轮次、同一输入只执行一次”，分开覆盖外接 endpoint 和自行启动模式。[be67349](https://github.com/Emanuele-web04/remodex/commit/be67349) · [33bef30](https://github.com/Emanuele-web04/remodex/commit/33bef30) |
| OpenCode / 多运行时 | 上游有可执行的 OpenCode 适配器和 provider 专用模型、会话处理；Cursor 在所比 main 中有集成设计文档，不能按同等成熟度列为已交付功能。XDD 现有多授权是同一 Codex 运行时下的账号管理。 | 作为独立产品选项保留。需要同时隔离 provider、主机、会话标识、模型和权限；只合并桥接文件不会得到可用 Android 体验。[OpenCode 实现](https://github.com/Emanuele-web04/remodex/blob/c06dcb1/phodex-bridge/src/opencode-runtime.js) · [Cursor 设计](https://github.com/Emanuele-web04/remodex/blob/c06dcb1/Docs/cursor-cli-sdk-acp-integration.md) |
| 自动审批审查与工具活动 | 上游增加 auto-review 状态/时间线、通用工具调用历史、已完成命令折叠、原生文件改动与合成补丁对账。XDD 已有审批、历史问答及文件预览，未见对应的整套审查表现。 | 优先借用“等待用户”和“系统正在审查”的状态区分，以及相同改动只显示一次。涉及批准权限的功能需匹配运行时语义。不能因为看见历史审批卡就恢复旧授权。[0038d89](https://github.com/Emanuele-web04/remodex/commit/0038d89) · [c46e404](https://github.com/Emanuele-web04/remodex/commit/c46e404) · [c06dcb1](https://github.com/Emanuele-web04/remodex/commit/c06dcb1) |
| 通知、锁屏与本地数据保护 | 上游处理晚到/重复完成通知、加密本地变更记录，以及 iOS Dynamic Island 和小组件生命周期。XDD 有运行和未读标记、SecureStore，不能据此推定所有本地历史均已加密。 | 借用“历史事件不能再次触发完成提醒”的场景。若做持久化变更记录，再按 Android Keystore 设计密钥恢复；Dynamic Island、图标素材和 TestFlight 版本号不移植。[4c66a98](https://github.com/Emanuele-web04/remodex/commit/4c66a98) · [b712ecd](https://github.com/Emanuele-web04/remodex/commit/b712ecd) |
| Mac 与 worktree 运维 | 上游改善 Codex.app 定位、LaunchAgent 重启循环与卸载，增加 worktree 来源和文件复制规则。 | 若进一步支持 Mac 常驻服务，优先对照前两项并做 Mac 真机验收。worktree 规则仅适用于明确启用的操作，维持 XDD 打开普通会话时不要求初始化 Git 的体验。[42ed1f1](https://github.com/Emanuele-web04/remodex/commit/42ed1f1) · [5af99da](https://github.com/Emanuele-web04/remodex/commit/5af99da) · [99308ec](https://github.com/Emanuele-web04/remodex/commit/99308ec) |

## 三、XDD 迁移时要保留的能力

- **授权管理与额度**：fix73 有持锁的导入、刷新、切换、定时维护及状态查询，存储语义属于这个分支。合并 bridge.js 时需保留对应 RPC；用测试账号文件验证较新版本优先、错误阶段及原子写入。[授权接口](../phodex-bridge/AUTHORIZATION.md)
- **文件和媒体**：保留加密流、断点续传、Android 内嵌播放和上传重试。替换 relay/server.js 时核对文件和更新路由，分别测试大附件下载与同时收聊天消息。[文件流](../phodex-bridge/src/file-stream.js) · [Android 加密读取](../android/app/src/main/kotlin/com/remodex/mobile/services/EncryptedFileStream.kt)
- **Android 会话体验**：保留固定项目排序、置顶/最近列表、历史问答、建议回复与输入框的采用规则。上游 UI 可作为交互参考，Swift 组件不能代替 Compose 的状态保存与滚动测试。[建议服务](../phodex-bridge/src/composer-prediction.js) · [Android 侧栏](../android/app/src/main/kotlin/com/remodex/mobile/services/CodexSidebarController.kt)
- **部署兼容**：用现有 Android fix73 对候选桥接做契约回归，并单独用候选 Android 对旧桥接测试降级。原作者的 4.0.0 与自家 APK 版本属于不同版本序列。首轮不修改协议版本、已发布标签或现有部署。

## 四、建议实施顺序与验收

1. **先修分页错误传播**，以两次失败保留原始错误、合法空页保持成功作为验收。然后验证三个小边界：私有 RPC 超时后迟到、语音旧接口兼容、超限 HTTP 请求的真实状态。每项独立提交，只用合成凭据。接受条件是原始错误能诊断、私有内容留在桥接、当前客户端转写成功。
2. **补一组双方都修过的行为回归**：跨会话失败重试、混合图文去重、最后纯文本轮次恢复、桌面改名和分页缺席。先跑 fix73；能通过的只保留测试或记录，复现差异才移植。桌面通知、列表和历史应共用同一组会话身份样例。
3. **并行规划但分别交付产品功能**：先做会话目标的只读能力探测，再设计“流中断后继续”。两者都有明确的服务器事实可校验，且能按能力隐藏。
4. **最后评估大模块**：桌面 owner/投影和 OpenCode 单独立项。性能部分分别测两种传输模式的大历史首屏、消息延迟、Android 滚动帧和桥接峰值内存。没有基线前，不把“补丁可应用”算作性能收益。

**证据边界**：这是代码与提交历史对照。索引列出全部独有提交及相对第一父提交涉及的目录；上表聚合同一功能的迭代，未声称对 238 次提交分别运行过测试。另做了本报告开头的四组隔离函数对照；此前四模块试移植通过 45 项相关测试。没有 Mac 真机、跨版本端到端协议或性能实测结果。以上优先级是迁移建议，不代表已经合入。
