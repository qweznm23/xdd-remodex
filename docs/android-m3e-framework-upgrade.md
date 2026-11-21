# Android M3 Expressive 框架升级记录

## 2026-11-19 构建批次

开始时位于 main，HEAD 为 af7026a23db3，领先 origin/main 两次提交。工作区已有 127 个已跟踪文件改动和 45 个未跟踪条目。验证基线为当时的整个工作区，包含此前的 fix91 界面改动。原构建入口 make android-check 增量执行通过。

| 项目 | 升级前 | 本批构建 |
|---|---|---|
| Gradle Wrapper / 运行 JDK | 9.5.0 / Temurin 21 | 9.5.0 / Temurin 21 |
| Android Gradle Plugin | 8.10.1 | 9.3.3 |
| Kotlin Android | 独立插件 2.2.0 | AGP 内置 Kotlin，KGP 2.2.10 |
| Compose 编译插件 / Serialization 插件 | 2.2.0 | 2.2.10 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | 36 / 36 / 26 |
| Java / Kotlin 字节码目标 | 17 | 17，已检查 Release MainActivity.class 的 major version 61 |
| Compose BOM / Material 3 Android | 2026.02.01 / 1.5.0-alpha14 | 2026.02.01 / 1.5.0-alpha14 |

AGP 9.3.3 的 POM 声明 KGP 2.2.10。客户端改用 AGP 内置 Kotlin，Compose 与序列化编译插件对齐该版本。Java 的 compileOptions 给出字节码目标。Release 变体通过 HostTestBuilder 启用现有 JVM 测试套件。首次迁移试跑找不到 testReleaseUnitTest，启用后测试编译报 kotlin.test.Test 与 AfterTest 无法解析，显式选择 kotlin-test-junit 后恢复既有 JUnit 4 入口。项目 JVM 参数、worker 和共享验证锁沿用原配置。[AGP 9.3 兼容表](https://developer.android.com/build/releases/agp-9-3-0-release-notes) · [内置 Kotlin 迁移](https://developer.android.com/build/migrate-to-built-in-kotlin)

## 稳定版依赖

Android 官方发布页列出了 Material 3 1.5.0。本机从 Google Maven 获取稳定版 POM 和 Android AAR 均返回 HTTP 404。使用同一 Gradle Wrapper、项目代理及 google() 仓库单独解析 material3-android:1.5.0，任务以 Could not find 退出。RC01 AAR 可以下载，其中 aar-metadata.properties 要求 minCompileSdk=37、minAndroidGradlePluginVersion=9.1.0。稳定版的 AAR 约束仍待获取。后续以可解析的稳定版 AAR 和传递构件为准，再决定 compileSdk 和对应 BOM。[Material 3 发布页](https://developer.android.com/jetpack/androidx/releases/compose-material3#1.5.0) · [Compose BOM 映射](https://developer.android.com/develop/ui/compose/bom/bom-mapping) · [API 与 AGP 兼容表](https://developer.android.com/build/releases/about-agp)

## 系统边距和首批界面

MainActivity 启用 edge-to-edge，窗口配置为 adjustResize。MainShell 的 Scaffold 为内容提供水平和底部 safeDrawing，在应用 innerPadding 后消费同一份 inset。聊天页读取 IME 高度用于时间线定位，后续修改输入栏时需要同时检查键盘动画、列表锚点和会话切换。聊天发送已使用 FilledIconButton，图标继承内容色，停止使用 errorContainer/onErrorContainer。组件清单单独记录各入口的交互语义与验收要求。

## 验证入口与产物

脚本共用 /var/lock/android-toolchain-validation.lock。make android-check 运行 testReleaseUnitTest、lintVitalRelease。make android-release 还会执行 assembleRelease、R8、资源收缩和 Picnic 裁剪校验。Release JVM 测试覆盖 169 个套件、1,028 个测试，失败、错误、跳过均为零。此次 lint 范围为发布必要检查。

releaseRuntimeClasspath 实际选用了 Compose UI、Foundation、Runtime、Animation Android 1.10.5 和 Material 3 Android 1.5.0-alpha14。产物入口为 android/app/build/outputs/apk/release/app-release.apk，包名 com.remodex.mobile，版本 0.1.7-ws-fix91（108）。使用 apksigner verify 核验 v2 签名并校验 ZIP 完整性。本地框架验证沿用现行版本号。

本机验证日志、基线状态及补丁保存在 .local/validation/m3e-upgrade/。其中 toolchain.patch 以开始执行时的文件为基准，只包含两个 Gradle 构建文件的本批差异；已用 git apply --reverse --check 校验逆向应用。回退前按当时工作区重新检查该补丁，可保留原有的 fix91 改动。

## 模拟器回归

在 API 35 的 ReDroid 上，以独立 applicationId 构建了开启 R8 和资源收缩的 Release 验收包。测试活动使用项目的 MainShell、主题、输入栏和时间线，仓库接口注入 40 轮会话、分页和流式数据。线上连接和账号未参与这项 UI 验收。

| 路径 | 实测结果 |
|---|---|
| 蓝色发送按钮 | 从截图采样，浅色、深色、自选蓝色的箭头与底色对比度分别为 8.06、7.71、8.49。三次从中心向右偏约 20dp 点击均发送成功；本项只验证了这个方向的命中。 |
| 键盘与草稿 | 在 1.5 倍系统字体下唤起键盘并输入，关闭键盘、打开侧栏再关闭侧栏，草稿仍在。三键与手势导航下均验证了键盘和输入，完成后恢复模拟器系统设置。 |
| 侧栏、设置、弹层 | 浅深色下验证最近、置顶、项目、搜索及工作区选择器。问答验证了选项、自由输入、提交与跳过，终端输入框和 Ctrl 切换通过。设置进入外观页可见主题、配色和字体。 |
| 历史与流式更新 | 在 Release 验收包里向上滚动并同时注入 100 次流式更新，看到补入的旧页，返回底部可见第 100 次更新。另有 Release JVM 测试覆盖历史锚点和草稿逻辑。 |

测试夹具初版用启动时的消息列表写入每次流式更新，会覆盖中途补入的旧页。修正夹具后以当前列表合并同一条流式消息，并重跑同场景。此结果来自合成数据。TalkBack 实际朗读、物理设备帧时间、真实账号的网络链路仍待独立验收。

dumpsys gfxinfo 记录的启动后滚动及流式更新样本为 148 帧，8 帧标记卡顿，P95 为 89ms。进入页面 3.25 秒后重置计数再测，187 帧中 1 帧标记卡顿，P95 为 21ms，P99 为 350ms。这是单台模拟器的一次采样，仍有长尾，未做新旧版本同机对照。验收结束后已移除独立测试包。

验证结束时，本项目 Gradle 用户目录下有两个 9.5.0 daemon，状态均为 IDLE。独立 Maven 解析探针首次执行时使用了不同的 JVM 参数，日志显示原 daemon 不兼容，另起了一个。项目的 daemon 与 worker 策略沿用原设置。
