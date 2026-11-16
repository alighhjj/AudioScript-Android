# 声稿 AudioScript · Android 版

把 [niyongsheng/AudioScript](https://github.com/niyongsheng/AudioScript)（Tauri 桌面版，MIT）移植到 Android 的工程。

监听手机正在播放的声音，把外语播客、视频与会议实时变成 **原文 + 译文** 双语字幕，识别与翻译都在本机完成。

## 与桌面版的关系

| 能力 | 桌面版 | Android 版 |
| --- | --- | --- |
| 界面 | Tauri + React | 原生 Kotlin + Jetpack Compose，配色取自 `src/shared/theme.css` |
| 系统声音采集 | Core Audio Process Tap / WASAPI loopback | `AudioPlaybackCapture`（需一次录屏授权） |
| 麦克风采集 | flexaudio / cpal | `AudioRecord` |
| 识别引擎 | `sherpa-onnx` 1.13 (Rust) | `sherpa-onnx` 1.13 (官方 AAR，同一推理引擎) |
| 识别模型 | `streaming-zipformer-small-bilingual-zh-en-2023-02-16` | 中文 / English 用**同一份模型**（int8 encoder/joiner + fp32 decoder，随 APK 内置）；日本語 用可下载的 8 语种流式 Zipformer |
| 断句 | greedy_search + 端点检测，rule1 = 2.4 s | 同参数；rule2/rule3 跟随「口译 / 听写」档位 |
| 翻译 | CTranslate2 + Opus-MT | ML Kit 本地翻译模型（CTranslate2 没有 Android 版本） |
| 悬浮字幕 | NSPanel 置顶窗口，锁定穿透 | `TYPE_APPLICATION_OVERLAY`，锁定后 `FLAG_NOT_TOUCHABLE` |
| 导出 | Markdown（时间戳 + 原文译文成对） | 同格式，走系统分享 |
| 常驻 | 托盘 + 关窗不退出 | 前台服务 + 常驻通知，关界面继续听 |

## 已实现

- 系统声音 / 麦克风两种音源，实时 partial 刷新 + 句末定稿
- 源语言支持中文 / English / 日本語：日文模型在设置里按需下载（约 247 MB），下载后完全离线
- 双语悬浮字幕：字号、背景不透明度、行数、边框、锁定穿透、可拖动并记住位置
- 本机翻译（中 / 英 / 日 → 16 种目标语言），失败自动降级为「只显示原文」
- 定稿段落导出 Markdown 并分享
- 断句档位（口译 0.8 s / 听写 1.2 s）、电平指示、模型说明

## 日文识别模型

日文没有桌面端使用的同系列单语流式模型，这里接入 sherpa-onnx 官方发布的
`streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10`（含日语，同时覆盖中英等 8 种语言）：

- 运行路径：`设置 → 模型 → 下载`，下载完成后把「源语言」切到「日本語」即可
- 下载约 247 MB（tar.bz2），应用内校验 sha256，只解压 encoder / decoder / joiner / tokens
- 网络兜底：GitHub release 下不动时自动改用 hf-mirror 逐个文件下载（四个文件各自校验 sha256，共约 339 MB）
- 存放位置 `filesDir/asr/ja-multi/`，可随时在设置里删除；识别线程通过 `OnlineRecognizer(config)` 直接读文件
- 不下载也不影响中英识别；APK 体积不变
- 语言标签补丁：这个模型来自 PengChengStarling，训练时用 `<JA>` 替换 `<SOS>` 来指定语种，而 sherpa-onnx 只把 `<blk>` 当首帧上下文（上游明确说明不支持语言标签）。安装时 `core/JaModelPatch.kt` 会把 decoder embedding 里 `<blk>` 那一行原地换成 `<JA>`，让首帧上下文回到训练时的样子；补丁失败会自动保留原文件，不影响加载

如果希望做「首启即用」的完整安装包，可以构建前把模型放进 assets：

```bash
bash scripts/fetch-ja-model.sh   # 拉取并解压到 app/src/main/assets/models/asr-ja（同时打好语言标签补丁）
./gradlew :app:assembleDebug     # APK 会增大，设置里显示「已内置」
```

### 手动安装（网络慢时）

日文模型放在应用私有目录，路径固定为：

```
/data/data/com.looplooptech.audioscript/files/asr/ja-multi/
```

需要 4 个文件（同一层，文件名保持原样即可）+ 一个空的 `.complete` 标记，且文件属主必须是应用 uid。已经有 root 的话，直接跑：

```bash
bash scripts/install-ja-model.sh /sdcard/Download/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2
# 也可以传已经解压的目录
```

脚本会校验 sha256、给 decoder 打语言标签补丁、复制文件、改成应用 uid 并写入 `.complete`。装完重开应用，「设置 → 模型」会显示「已安装」，不需要重新编译或重装 APK。

### 用 CI 构建与校验

`.github/workflows/android-build.yml` 可以在 Actions 页面手动触发（`workflow_dispatch`），不用在本地装 Kotlin / Android SDK：

| 选项 | 作用 |
| --- | --- |
| `bundle_ja_model` | 构建前先跑 `scripts/fetch-ja-model.sh`，把日文模型打进 APK，产物名带 `-ja` 后缀 |
| `verify_ja_patch` | 从 hf-mirror 下载真实的日文 decoder、校验 sha256，再跑 `JaModelPatchTest` 核对补丁结果 |

默认每次都会跑 `:app:testDebugUnitTest`：

- `JaModelPatchTest`：用合成的 ONNX 覆盖「把 `<blk>` 行换成 `<JA>` 行、其余字节不变、文件长度不变」，以及缺少张量 / token 越界 / 文件损坏时不改文件
- `ModelCatalogTest`：检查源语言 → 模型的选择、内置模型约束、日文模型的下载地址 / 镜像清单 / sha256
- 勾选 `verify_ja_patch` 后，额外断言真实 decoder 打补丁后的 sha256 为 `1d822929242004647cd9c41881599c52918254a8dbfd502933f9b20b61c962a2`

如果需要在本机复现完整校验（装了 JDK 17 与 Android SDK 时），把 decoder 路径传给 Gradle 即可：

```bash
./gradlew :app:testDebugUnitTest -PjaDecoder=/path/to/decoder-epoch-75-avg-11-chunk-16-left-128.onnx
```

## 尚未移植

- 混合音源（系统声音 + 麦克风同时）
- 更多识别模型（X-ASR / Nemotron / Qwen3-ASR）与 LMT-60 / NLLB 翻译模型的可选下载
- 全局快捷键、开机自启、13 种界面语言（当前仅中文）
- 匿名统计与崩溃上报（桌面版可关闭，这里默认不带）

## 构建

```bash
# 依赖：JDK 17、Android SDK（platform 34、build-tools 34.0.0）
# 没有 local.properties 时，先指定 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

工程有两个大二进制依赖，**完整包**已内置、可直接编译；**精简包**需要先拉一次：

```bash
bash scripts/fetch-deps.sh   # 拉 AAR + 识别模型，约 40 MB
```

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `app/libs/sherpa-onnx-1.13.0.aar` | 54 MB | 官方 Android 预编译包，含 arm64-v8a / armeabi-v7a JNI 库 |
| `app/src/main/assets/models/asr/*.onnx` | 55 MB | 双语流式 Zipformer，int8 encoder/joiner + fp32 decoder |

日文模型默认不随包内置，由应用运行时下载；构建时另外引入 `org.apache.commons:commons-compress` 负责解压官方 tar.bz2。

模型必须用 `Stored`（不压缩）方式进 APK，否则 native 层无法 mmap；`app/build.gradle.kts` 里的 `androidResources { noCompress += "onnx" }` 就是干这个的，别删。

## 打包说明

- `minSdk 26` / `targetSdk 34`，只打包 `arm64-v8a` + `armeabi-v7a`
- APK 体积主要来自内置 ONNX 模型（58 MB）与 sherpa-onnx 原生库（约 50 MB）
- Release 构建目前沿用 debug 签名，正式分发请换成自己的 keystore

## 许可

原项目 MIT。内置中英模型 Apache-2.0；日文 8 语种模型来自 sherpa-onnx 官方发布（见模型包内许可）；ML Kit 本地翻译模型受 Google 条款约束。

## 预编译产物

在 GitHub Releases 下载最新 APK（arm64-v8a + armeabi-v7a，debug 签名，可直接侧载安装）。

安装后首次使用：系统声音 → 授予录音权限 + 一次「共享屏幕」授权；悬浮字幕 → 授予「显示在其他应用上层」。
