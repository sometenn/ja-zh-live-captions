# 日语实时字幕 · macOS

用 Mac 采集所选应用、屏幕播放的声音或麦克风，通过 Soniox 或 Gemini 显示日语原文和简体中文译文。置顶字幕支持调整字体、字号、译文颜色和背景透明度。

## 运行

克隆完整仓库，在 macOS 27 或更新版本上用 Xcode 27 或更新版本打开本目录的 `CallCaptions.xcworkspace`，选择 `CallCaptions` 和「My Mac」，点击运行。测试收音无需密钥，开始翻译前请在应用内保存所选服务的密钥。

也可以从仓库根目录执行以下命令。

```sh
cd macos
xcodebuild -workspace CallCaptions.xcworkspace -scheme CallCaptions \
  -configuration Release -destination 'platform=macOS' -derivedDataPath build build
open build/Build/Products/Release/CallCaptions.app
```

## 目录

- `Sources/` 包含 Mac 首页、原生置顶窗口和字幕外观设置。
- `Info.plist` 配置 Mac 应用信息和权限说明。
- `CallCaptions.xcworkspace` 引用仓库根目录的 Xcode 工程。两个 Apple 平台共用的收音、实时翻译和字幕逻辑位于 [../Sources/](../Sources/)。

应用操作见[使用说明](../使用说明.md)。
