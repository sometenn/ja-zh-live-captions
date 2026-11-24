# 日语实时字幕 · CallCaptions

iPhone、Mac 和 Android 上的实时日语转写和简体中文翻译。支持自填 Soniox 或 Gemini 密钥，收录机内声音或麦克风。iPhone 使用画中画字幕，Mac 使用可拖动、调整大小的原生置顶窗口，Android 使用系统悬浮窗。

iOS 当前版本为 **0.3.7（构建 10）**，支持从控制中心启动字幕。普通运营商电话音频接入仍在调研。功能边界和真机操作见[使用说明](使用说明.md)。

## iOS 版本（`ios/`）

iPhone 首页、画中画字幕、应用配置和开发入口位于 [ios/](ios/)。用 Xcode 打开 `ios/CallCaptions.xcworkspace`，选择 `CallCaptions` 和目标 iPhone 即可运行。真机签名和命令行构建见 [iOS 开发说明](ios/README.md)。

## Mac 版本（`macos/`）

Mac 专属界面、置顶字幕窗、外观设置和应用配置在 [macos/](macos/)。用 Xcode 打开该目录的 `CallCaptions.xcworkspace`，选择 `CallCaptions` 和「My Mac」即可运行。收音与实时翻译逻辑由两个 Apple 平台共享。环境要求与命令行构建见 [Mac 开发说明](macos/README.md)。

## Android 版本（`android/`）

Android 端位于 `android/` 目录，使用 Kotlin + Jetpack Compose 实现，最低支持 **Android 10（API 29）**，功能对齐 iOS 0.3.6（Soniox / Gemini、双语与仅大号中文、本地加密密钥、麦克风与机内声音采集、可拖动系统悬浮字幕窗）。

运行 Android 单元测试并编译 Debug APK：

```sh
cd android
./gradlew test assembleDebug
```

编译产物位于 `android/app/build/outputs/apk/debug/app-debug.apk`（由 Git 忽略）。真机使用与机内声音限制见[使用说明](使用说明.md)。

## Apple 共享代码与本地检查

iOS 和 macOS 共用根目录的 Xcode 工程、`Sources/`、`Assets.xcassets/` 和回归测试。可以从仓库根目录在 Mac 上运行会话测试。

```sh
swiftc -parse-as-library Sources/{Captions,PCM,Soniox,Gemini,Vault,Capture,Session}.swift \
  ios/Sources/Overlay.swift Tests/SessionTests.swift -o /tmp/callcaptions-session-tests
/tmp/callcaptions-session-tests
```

## 多台电脑协作

每台电脑克隆同一个仓库。每个 AI 或开发任务从最新 `main` 开独立分支，推送后发 Pull Request。跨电脑继续同一项工作时，检出对应分支。签名和 API 密钥按设备配置。

第三方代码的署名和许可见 [ThirdPartyNotices.txt](ThirdPartyNotices.txt)。
