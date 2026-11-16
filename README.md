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
| 识别模型 | `streaming-zipformer-small-bilingual-zh-en-2023-02-16` | 中文 / English 用**同一份模型**（int8 encoder/joiner + fp32 decoder，随 APK 内置）；日本語 用可下载的 ReazonSpeech 单语离线模型 |
| 断句 | greedy_search + 端点检测，rule1 = 2.4 s | 中英同参数；日文用 Silero VAD 静音切句（0.4 s）后整段解码 |
| 翻译 | CTranslate2 + Opus-MT | ML Kit 本地翻译模型（CTranslate2 没有 Android 版本） |
| 悬浮字幕 | NSPanel 置顶窗口，锁定穿透 | `TYPE_APPLICATION_OVERLAY`，锁定后 `FLAG_NOT_TOUCHABLE` |
| 导出 | Markdown（时间戳 + 原文译文成对） | 同格式，走系统分享 |
| 常驻 | 托盘 + 关窗不退出 | 前台服务 + 常驻通知，关界面继续听 |

## 已实现

- 系统声音 / 麦克风两种音源；中英实时 partial 刷新 + 句末定稿，日文 VAD 切句后整句定稿
- 源语言支持中文 / English / 日本語：日文模型在设置里按需下载（约 169 MB），下载后完全离线
- 双语悬浮字幕：字号、背景不透明度、行数、边框、锁定穿透、可拖动并记住位置
- 本机翻译（中 / 英 / 日 → 16 种目标语言），失败自动降级为「只显示原文」
- 定稿段落导出 Markdown 并分享
- 断句档位（口译 0.8 s / 听写 1.2 s）、电平指示、模型说明

## 日文识别模型

日文使用 sherpa-onnx 官方发布的 **ReazonSpeech 日本語 Zipformer**（35k 小时日语数据）离线模型，
配合 Silero VAD 切句后整段解码，也就是官方的 simulate-streaming 方案。

之前用的 8 语种流式模型 encoder 有 296 MB，在这类 8 核手机上解码速度低于实时速度，
录音缓冲持续溢出，表现就是「字幕过一会儿就不动了」；换成单语离线模型后 encoder 降到 154 MB，
而且耗 CPU 的解码放在独立线程，读音频不会被堵住。

- 运行路径：`设置 → 模型 → 下载`，下载完成后把「源语言」切到「日本語」即可
- 下载约 169 MB（4 个文件），逐个校验 sha256；走 hf-mirror（GitHub release 在国内基本下不动）
- 存放位置 `filesDir/asr/ja-reazon/`，可随时在设置里删除；识别线程通过 `OfflineRecognizer(config)` 直接读文件
- 不下载也不影响中英识别；APK 体积不变
- 出字幕时机：一句话说完（静音 0.4 s）后整句上屏；连续说话时 VAD 会在 10 s 后收紧阈值强制断句
- VAD 模型 `silero_vad.onnx`（1.8 MB）随 APK 内置，路径 `assets/models/vad/`
- 解码跟不上时丢弃最旧的一句并在界面提示，不会静默停住

如果希望做「首启即用」的完整安装包，可以构建前把模型放进 assets：

```bash
bash scripts/fetch-ja-model.sh   # 拉取到 app/src/main/assets/models/asr-ja-reazon
./gradlew :app:assembleDebug     # APK 会增大，设置里显示「已内置」
```

### 手动安装（网络慢时）

日文模型放在应用私有目录，路径固定为：

```
/data/data/com.looplooptech.audioscript/files/asr/ja-reazon/
```

需要 4 个文件（同一层，文件名保持原样）+ 一个空的 `.complete` 标记，且文件属主必须是应用 uid。在 Termux 里以普通用户运行（不要加 `su`，脚本会自己调用）：

```bash
bash scripts/install-ja-model.sh                       # 从 hf-mirror 下载并安装
bash scripts/install-ja-model.sh /sdcard/Download/模型目录
bash scripts/install-ja-model.sh sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2
```

脚本会校验 4 个文件的 sha256、复制文件、改成应用 uid 并写入 `.complete`；`/sdcard` 读不到时会先用 `su` 复制一份到临时目录。装完重开应用，「设置 → 模型」会显示「已安装」，不需要重新编译或重装 APK。

### 用 CI 构建与校验

`.github/workflows/android-build.yml` 可以在 Actions 页面手动触发（`workflow_dispatch`），不用在本地装 Kotlin / Android SDK：

| 选项 | 作用 |
| --- | --- |
| `bundle_ja_model` | 构建前先跑 `scripts/fetch-ja-model.sh`，把日文模型打进 APK，产物名带 `-ja` 后缀 |

默认每次都会跑 `:app:testDebugUnitTest`：

- `ModelCatalogTest`：检查源语言 → 模型的选择、内置模型约束、日文模型的 VAD 管线 / 镜像清单 / sha256

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
| `app/src/main/assets/models/vad/silero_vad.onnx` | 1.8 MB | 日文识别用的 Silero VAD，随包内置 |

日文模型默认不随包内置，由应用运行时按文件下载；`silero_vad.onnx` 随 APK 内置。`ModelManager` 仍保留 tar.bz2 解压能力（`commons-compress`），供以后接入其他官方归档模型时使用。

模型必须用 `Stored`（不压缩）方式进 APK，否则 native 层无法 mmap；`app/build.gradle.kts` 里的 `androidResources { noCompress += "onnx" }` 就是干这个的，别删。

## 打包说明

- `minSdk 26` / `targetSdk 34`，只打包 `arm64-v8a` + `armeabi-v7a`
- APK 体积主要来自内置 ONNX 模型（58 MB）与 sherpa-onnx 原生库（约 50 MB）
- Release 构建目前沿用 debug 签名，正式分发请换成自己的 keystore

## 许可

原项目 MIT。内置中英模型 Apache-2.0；日文 ReazonSpeech 模型来自 sherpa-onnx 官方发布（Apache-2.0）；ML Kit 本地翻译模型受 Google 条款约束。

## 预编译产物

在 GitHub Releases 下载最新 APK（arm64-v8a + armeabi-v7a，debug 签名，可直接侧载安装）。

安装后首次使用：系统声音 → 授予录音权限 + 一次「共享屏幕」授权；悬浮字幕 → 授予「显示在其他应用上层」。
