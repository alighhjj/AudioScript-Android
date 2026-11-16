package com.looplooptech.audioscript.ui

/** 界面文案。桌面端有 13 种语言，这里先只带中文，结构上留好扩展位。 */
object Copy {
    const val AppName = "声稿"
    const val AppSubtitle = "AudioScript · 离线双语字幕"

    const val Idle = "待机"
    const val Preparing = "加载模型…"
    const val Running = "聆听中"
    const val Stop = "停止"
    const val Start = "开始"

    const val EmptyTitle = "让语言不再是一道坎"
    const val EmptyBody = "监听手机正在播放的声音，外语播客、视频与会议实时变成原文 + 译文双语字幕。识别与翻译都在本机完成。"
    const val EmptyBullet1 = "100% 本机识别与翻译，音频不上传"
    const val EmptyBullet2 = "内置中英混说模型；日文模型可在设置里按需下载"
    const val EmptyBullet3 = "悬浮字幕锁屏穿透，不挡你看视频"

    const val SourceSystem = "系统声音"
    const val SourceMic = "麦克风"
    const val Overlay = "悬浮字幕"
    const val Export = "导出"
    const val Settings = "设置"
    const val Clear = "清空"

    const val Translating = "翻译中…"
    const val TranslateFailed = "翻译失败"
    const val TranslateUnavailable = "本地翻译模型未就绪，先显示原文"
    const val TapToStop = "点按停止"

    const val SettingsTitle = "设置"
    const val EndpointTitle = "断句档位"
    const val EndpointInterp = "口译"
    const val EndpointDict = "听写"
    const val EndpointHint = "口译：静音 0.8 秒即定稿，适合看视频；听写：等 1.2 秒，长句更完整。"
    const val OverlayTitle = "悬浮字幕"
    const val FontSize = "字号"
    const val Opacity = "背景不透明度"
    const val Lines = "显示行数"
    const val Glow = "流光边框"
    const val LockOverlay = "锁定穿透（点击穿过字幕）"
    const val OverlayPermission = "需要「显示在其他应用上层」权限才能显示悬浮字幕"
    const val Grant = "去授权"

    const val ModelTitle = "模型"
    const val AboutTitle = "关于"
    const val AboutBody = "本应用由 AudioScript 声稿（MIT）桌面版移植：识别沿用 sherpa-onnx 推理引擎，中英模型随安装包内置，日文模型可按需下载；翻译在本机运行 ML Kit 本地模型。"
    const val Repo = "github.com/niyongsheng/AudioScript"
}
