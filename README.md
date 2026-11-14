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
| 识别模型 | `streaming-zipformer-small-bilingual-zh-en-2023-02-16` | **同一份模型**，int8 encoder/joiner + fp32 decoder，随 APK 放在 assets 内置 |
| 断句 | greedy_search + 端点检测，rule1 = 2.4 s | 同参数；rule2/rule3 跟随「口译 / 听写」档位 |
| 翻译 | CTranslate2 + Opus-MT | ML Kit 本地翻译模型（CTranslate2 没有 Android 版本） |
| 悬浮字幕 | NSPanel 置顶窗口，锁定穿透 | `TYPE_APPLICATION_OVERLAY`，锁定后 `FLAG_NOT_TOUCHABLE` |
| 导出 | Markdown（时间戳 + 原文译文成对） | 同格式，走系统分享 |
| 常驻 | 托盘 + 关窗不退出 | 前台服务 + 常驻通知，关界面继续听 |

## 已实现

- 系统声音 / 麦克风两种音源，实时 partial 刷新 + 句末定稿
- 双语悬浮字幕：字号、背景不透明度、行数、边框、锁定穿透、可拖动并记住位置
- 本机翻译（中 → 16 种语言，或英 → 中文），失败自动降级为「只显示原文」
- 定稿段落导出 Markdown 并分享
- 断句档位（口译 0.8 s / 听写 1.2 s）、电平指示、模型说明

## 尚未移植

- 混合音源（系统声音 + 麦克风同时）
- 多语言识别模型（X-ASR / Nemotron / Qwen3-ASR）与 LMT-60 / NLLB 翻译模型的可选下载
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

模型必须用 `Stored`（不压缩）方式进 APK，否则 native 层无法 mmap；`app/build.gradle.kts` 里的 `androidResources { noCompress += "onnx" }` 就是干这个的，别删。

## 打包说明

- `minSdk 26` / `targetSdk 34`，只打包 `arm64-v8a` + `armeabi-v7a`
- APK 体积主要来自内置 ONNX 模型（58 MB）与 sherpa-onnx 原生库（约 50 MB）
- Release 构建目前沿用 debug 签名，正式分发请换成自己的 keystore

## 许可

原项目 MIT。内置模型 Apache-2.0；ML Kit 本地翻译模型受 Google 条款约束。

## 预编译产物

`/workspace/AudioScript-android-0.7.1.apk`（154 MB，arm64-v8a + armeabi-v7a，debug 签名，可直接侧载安装）。

安装后首次使用：系统声音 → 授予录音权限 + 一次「共享屏幕」授权；悬浮字幕 → 授予「显示在其他应用上层」。
