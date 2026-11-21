# Android 组件审计 · fix91

以 fix90 的 407 个正式 Kotlin 源文件为起点，复查 fix91 的 408 个文件。范围为 Android 客户端的 Compose 界面及底层绘制入口。仓库还包含 Apple 客户端、桥接服务和网页内容，未把这些计入 Android Material 控件数量。

## 数量与口径

每个调用位置计一次，共用组件即使出现在多个页面也只计一次。搜索非导入行中的点击和手势修饰符、基础输入框、独立窗口、Canvas、绘制修饰符，并另查自定义 Painter 的 onDraw。补查已使用 Surface、IconButton 等标准组件却单独画背景、边框、阴影的公共样式。静态数字用于复查入口；页面上的控件实例数和视觉不一致处数无法直接由它推导。

| Android Kotlin 源码调用 | fix90 | fix91 |
|---|---:|---:|
| clickable | 51 | 22 |
| combinedClickable | 3 | 3 |
| selectable / toggleable | 6 | 13 |
| pointerInput | 7 | 7 |
| BasicTextField | 5 | 3 |
| 普通 Dialog（AlertDialog 不计在内） | 7 | 5 |
| Canvas（含一处附件位图处理） | 6 | 4 |
| drawWithContent | 1 | 1 |
| 以上合计 | 86 | 58 |
| 另查 Painter.onDraw | 2 | 2 |

按直接画界面图形的口径，排除附件缩略图，再合并 Canvas、drawWithContent 和 Painter.onDraw，**8 处降为 6 处**。剩下的是侧栏引导遮罩、活动点、录音波形、周额度圆环、内嵌图片绘制和视频封面。运行中的会话已换用标准进度环，继续共用一个由可见状态控制的动画时钟。

## 组件用法

- 会话内的次要操作使用低强调的 TextButton。工作详情、思考、连续工具调用和代理摘要共用同一套展开样式，箭头与文字继承按钮颜色。
- 设置导航、文件夹和选择器使用 ListItem。标题、说明、选中状态分槽排列。普通操作用 AssistChip，有开关状态的终端修饰键用 FilterChip，主要操作与取消分别用 Button、OutlinedButton。
- 问答和普通帮助用 AlertDialog，选项、自由输入与操作留在同一弹框。新建会话去掉固定在第一步的装饰步骤条，列表和底部操作栏各自占用布局空间。
- 终端的旧通用装饰修饰符已移除。命令示例使用带主题容器色的 Surface，资料卡使用 OutlinedCard；同一组卡片不再各自叠加边框与阴影。

## fix91 复查位置

下表列出 58 处扫描命中及 2 处 Painter。标准组件常用 selectable 或 toggleable 使整行可操作，这些也计入上表。文件差异、引用、工具执行等复合消息行还保留专用布局。

| 文件 | 入口与行号 | 用途 |
|---|---|---|
| TurnAttachmentCodec | [Canvas 114](../android/app/src/main/kotlin/com/remodex/mobile/data/TurnAttachmentCodec.kt#L114) | 生成上传缩略图，属于位图处理。 |
| TerminalFormChrome | [toggleable 175](../android/app/src/main/kotlin/com/remodex/mobile/terminal/TerminalFormChrome.kt#L175) | 整行选择或切换。由行处理点击，RadioButton、Checkbox、Switch 或 ListItem 展示状态。 |
| TermuxTerminalSurface | [clickable 96](../android/app/src/main/kotlin/com/remodex/mobile/terminal/TermuxTerminalSurface.kt#L96)、[BasicTextField 124](../android/app/src/main/kotlin/com/remodex/mobile/terminal/TermuxTerminalSurface.kt#L124) | 终端的输入焦点和 IME 代理。 |
| ConversationHeader | [clickable 120](../android/app/src/main/kotlin/com/remodex/mobile/ui/agent/ConversationHeader.kt#L120) | 点击路径、完成通知或用量摘要进入详情。 |
| SidebarDrawerContent | [Canvas 263](../android/app/src/main/kotlin/com/remodex/mobile/ui/agent/SidebarDrawerContent.kt#L263)、[clickable 266](../android/app/src/main/kotlin/com/remodex/mobile/ui/agent/SidebarDrawerContent.kt#L266) | 引导遮罩的镂空及关闭。 |
| TimelineActivityDots | [Canvas 34](../android/app/src/main/kotlin/com/remodex/mobile/ui/agent/TimelineActivityDots.kt#L34) | 分别绘制活动点、录音波形和周额度圆环。 |
| CanvasSnapshotViewer | [pointerInput 96](../android/app/src/main/kotlin/com/remodex/mobile/ui/design/canvas/CanvasSnapshotViewer.kt#L96)、[pointerInput 164](../android/app/src/main/kotlin/com/remodex/mobile/ui/design/canvas/CanvasSnapshotViewer.kt#L164) | 缩放、画布或拖动手势。 |
| BridgeConnectionTestPanel | [Dialog 397](../android/app/src/main/kotlin/com/remodex/mobile/ui/dev/BridgeConnectionTestPanel.kt#L397) | 独立预览或扫码窗口，内容需要全屏、缩放或专用视图。 |
| ThreadCompletionBanner | [clickable 206](../android/app/src/main/kotlin/com/remodex/mobile/ui/home/ThreadCompletionBanner.kt#L206) | 点击路径、完成通知或用量摘要进入详情。 |
| MyDevicesScreen | [clickable 356](../android/app/src/main/kotlin/com/remodex/mobile/ui/mydevices/MyDevicesScreen.kt#L356) | 设备、项目或目录的 M3 ListItem 行交互。 |
| PetCompanionOverlay | [pointerInput 193](../android/app/src/main/kotlin/com/remodex/mobile/ui/pet/PetCompanionOverlay.kt#L193)、[pointerInput 199](../android/app/src/main/kotlin/com/remodex/mobile/ui/pet/PetCompanionOverlay.kt#L199) | 缩放、画布或拖动手势。 |
| AuthManagementPanel | [selectable 256](../android/app/src/main/kotlin/com/remodex/mobile/ui/settings/AuthManagementPanel.kt#L256) | 整行选择或切换。由行处理点击，RadioButton、Checkbox、Switch 或 ListItem 展示状态。 |
| SessionAppearanceOptions | [selectable 68](../android/app/src/main/kotlin/com/remodex/mobile/ui/settings/SessionAppearanceOptions.kt#L68) | 整行选择或切换。由行处理点击，RadioButton、Checkbox、Switch 或 ListItem 展示状态。 |
| SettingsScreen | [selectable 374](../android/app/src/main/kotlin/com/remodex/mobile/ui/settings/SettingsScreen.kt#L374)、[clickable 505](../android/app/src/main/kotlin/com/remodex/mobile/ui/settings/SettingsScreen.kt#L505)、[selectable 616](../android/app/src/main/kotlin/com/remodex/mobile/ui/settings/SettingsScreen.kt#L616) | 整行选择或切换。由行处理点击，RadioButton、Checkbox、Switch 或 ListItem 展示状态。 |
| GitActionBottomSheet | [toggleable 257](../android/app/src/main/kotlin/com/remodex/mobile/ui/shell/GitActionBottomSheet.kt#L257)、[selectable 471](../android/app/src/main/kotlin/com/remodex/mobile/ui/shell/GitActionBottomSheet.kt#L471) | 整行选择或切换。由行处理点击，RadioButton、Checkbox、Switch 或 ListItem 展示状态。 |
| GitRepoDiffBottomSheet | [clickable 560](../android/app/src/main/kotlin/com/remodex/mobile/ui/shell/GitRepoDiffBottomSheet.kt#L560)、[clickable 701](../android/app/src/main/kotlin/com/remodex/mobile/ui/shell/GitRepoDiffBottomSheet.kt#L701)、[BasicTextField 818](../android/app/src/main/kotlin/com/remodex/mobile/ui/shell/GitRepoDiffBottomSheet.kt#L818) | 差异编辑器、文件路径和可展开的补丁内容。 |
| StructuredInputDialog | [selectable 85](../android/app/src/main/kotlin/com/remodex/mobile/ui/shell/StructuredInputDialog.kt#L85) | 整行选择或切换。由行处理点击，RadioButton、Checkbox、Switch 或 ListItem 展示状态。 |
| ProjectIndex | [pointerInput 89](../android/app/src/main/kotlin/com/remodex/mobile/ui/sidebar/ProjectIndex.kt#L89)、[clickable 135](../android/app/src/main/kotlin/com/remodex/mobile/ui/sidebar/ProjectIndex.kt#L135) | 右侧拖动选项目，以及选择器中的 ListItem。 |
| SidebarProjectPickerSheet | [selectable 869](../android/app/src/main/kotlin/com/remodex/mobile/ui/sidebar/SidebarProjectPickerSheet.kt#L869)、[clickable 893](../android/app/src/main/kotlin/com/remodex/mobile/ui/sidebar/SidebarProjectPickerSheet.kt#L893) | 设备、项目或目录的 M3 ListItem 行交互。 |
| SidebarScreen | [combinedClickable 1054](../android/app/src/main/kotlin/com/remodex/mobile/ui/sidebar/SidebarScreen.kt#L1054) | 会话和项目同时支持点击与长按菜单。 |
| SidebarThreadRow | [combinedClickable 80](../android/app/src/main/kotlin/com/remodex/mobile/ui/sidebar/SidebarThreadRow.kt#L80) | 会话和项目同时支持点击与长按菜单。 |
| ConversationImageAlbum | [clickable 43](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/ConversationImageAlbum.kt#L43) | 点击媒体内容进入预览。内嵌图还通过 Painter 控制占用的版面。 |
| ConversationImageGallery | [Dialog 132](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/ConversationImageGallery.kt#L132) | 独立预览或扫码窗口，内容需要全屏、缩放或专用视图。 |
| ConversationMarkdownImages | [Painter.onDraw 30](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/ConversationMarkdownImages.kt#L30)、[clickable 42](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/ConversationMarkdownImages.kt#L42)、[clickable 52](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/ConversationMarkdownImages.kt#L52) | 点击媒体内容进入预览。内嵌图还通过 Painter 控制占用的版面。 |
| ConversationVideoPlayer | [Dialog 109](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/ConversationVideoPlayer.kt#L109) | 独立预览或扫码窗口，内容需要全屏、缩放或专用视图。 |
| InteractivePageDialog | [Dialog 93](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/InteractivePageDialog.kt#L93) | 独立预览或扫码窗口，内容需要全屏、缩放或专用视图。 |
| SharedDraftDialog | [toggleable 57](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/SharedDraftDialog.kt#L57) | 整行选择或切换。由行处理点击，RadioButton、Checkbox、Switch 或 ListItem 展示状态。 |
| ToolExecutionCard | [clickable 77](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/ToolExecutionCard.kt#L77) | 复合消息行，保留文件状态、工具详情、代理或来源的上下文和展开操作。 |
| TurnAttachmentViews | [clickable 170](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnAttachmentViews.kt#L170) | 点击媒体内容进入预览。内嵌图还通过 Painter 控制占用的版面。 |
| TurnComposerBar | [BasicTextField 294](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnComposerBar.kt#L294)、[combinedClickable 346](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnComposerBar.kt#L346)、[clickable 416](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnComposerBar.kt#L416) | 草稿编辑含提及、光标、IME 和收放；同时保留附件长按与点击恢复输入。 |
| TurnComposerUnifiedModelRuntimeChip | [toggleable 174](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnComposerUnifiedModelRuntimeChip.kt#L174) | 整行选择或切换。由行处理点击，RadioButton、Checkbox、Switch 或 ListItem 展示状态。 |
| TurnComposerUsageStrip | [clickable 98](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnComposerUsageStrip.kt#L98) | 点击路径、完成通知或用量摘要进入详情。 |
| TurnFileChangeDetailCard | [clickable 138](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnFileChangeDetailCard.kt#L138)、[clickable 192](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnFileChangeDetailCard.kt#L192)、[clickable 296](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnFileChangeDetailCard.kt#L296) | 复合消息行，保留文件状态、工具详情、代理或来源的上下文和展开操作。 |
| TurnGitBranchAccessory | [selectable 509](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnGitBranchAccessory.kt#L509) | 整行选择或切换。由行处理点击，RadioButton、Checkbox、Switch 或 ListItem 展示状态。 |
| TurnMermaidFallbackCard | [Dialog 193](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnMermaidFallbackCard.kt#L193) | 独立预览或扫码窗口，内容需要全屏、缩放或专用视图。 |
| TurnReviewAccessory | [selectable 290](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnReviewAccessory.kt#L290) | 整行选择或切换。由行处理点击，RadioButton、Checkbox、Switch 或 ListItem 展示状态。 |
| TurnSearchCitationAccessory | [clickable 76](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnSearchCitationAccessory.kt#L76) | 复合消息行，保留文件状态、工具详情、代理或来源的上下文和展开操作。 |
| TurnSubagentActionCard | [clickable 147](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/TurnSubagentActionCard.kt#L147) | 复合消息行，保留文件状态、工具详情、代理或来源的上下文和展开操作。 |
| VideoCardPainter | [Painter.onDraw 45](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/VideoCardPainter.kt#L45) | 视频封面占位以及播放图标。 |
| VoiceRecordingCapsule | [Canvas 142](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/VoiceRecordingCapsule.kt#L142) | 分别绘制活动点、录音波形和周额度圆环。 |
| WeeklyQuota | [drawWithContent 96](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/WeeklyQuota.kt#L96) | 分别绘制活动点、录音波形和周额度圆环。 |
| ZoomablePreviewImage | [pointerInput 68](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/ZoomablePreviewImage.kt#L68)、[pointerInput 76](../android/app/src/main/kotlin/com/remodex/mobile/ui/turn/ZoomablePreviewImage.kt#L76) | 缩放、画布或拖动手势。 |

## 验证

隔离包名的 Android 模拟器使用测试会话。浅色、深色下验证了会话搜索和取消搜索后的返回、问答表单的单选或手动输入提交、跳过、终端字段与 Ctrl 状态，以及新建会话选择模式后提交。会话主页使用 320 dp 宽的内容区域检查侧栏；另从侧栏进入设置，再从设置进入外观。新建会话的可滚动列表到屏幕纵坐标 1668 像素处结束，底部创建操作的文本从 1718 像素开始，两种主题均无重叠。服务端交互在测试中使用模拟数据，真实手机的系统字体、缩放和输入法仍会影响布局。
