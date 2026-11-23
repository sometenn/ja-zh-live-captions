# 日语实时字幕 · CallCaptions

iPhone 和 Mac 上的实时日语转写和简体中文翻译。支持自填 Soniox 或 Gemini 密钥，收录机内声音或麦克风。iPhone 使用画中画字幕，Mac 使用可拖动、调整大小的原生置顶窗口。

当前版本为 **0.3.6（构建 9）**。普通运营商电话音频接入仍在调研。功能边界和真机操作见[使用说明](使用说明.md)。

## 开始开发

1. 克隆此仓库，在 macOS 上用 Xcode 27 或更新版本打开 `CallCaptions.xcodeproj`。
2. 真机签名前，在项目根目录创建 `Signing.local.xcconfig`，写入 `DEVELOPMENT_TEAM = 你的团队ID`。如果需要自己的应用 ID，再加一行 `PRODUCT_BUNDLE_IDENTIFIER = 你的应用ID`。该文件由 Git 忽略。
3. 连接并解锁运行 iOS 27 或更新版本的 iPhone，选择该设备并运行。翻译服务的 API 密钥可在应用内「密钥」页面设置。测试音量无需 API 密钥。

Mac 版需要 macOS 27 或更新版本。在同一工程选择「My Mac」并运行即可，默认使用本机签名。也可以从命令行构建。

```sh
xcodebuild -project CallCaptions.xcodeproj -scheme CallCaptions \
  -configuration Release -destination 'platform=macOS' -derivedDataPath build build
open build/Build/Products/Release/CallCaptions.app
```

## 本地检查

不签名编译 iPhone 目标：

```sh
xcodebuild -project CallCaptions.xcodeproj -scheme CallCaptions \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

在 macOS 运行会话回归测试：

```sh
swiftc -parse-as-library Sources/{Captions,PCM,Soniox,Gemini,Vault,Capture,Overlay,Session}.swift \
  Tests/SessionTests.swift -o /tmp/callcaptions-session-tests
/tmp/callcaptions-session-tests
```

## 多台电脑协作

每台电脑克隆同一个仓库。每个 AI 或开发任务从最新 `main` 开独立分支，推送后发 Pull Request。跨电脑继续同一项工作时，检出对应分支。签名和 API 密钥按设备配置。

第三方代码的署名和许可见 [ThirdPartyNotices.txt](ThirdPartyNotices.txt)。
