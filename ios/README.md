# 日语实时字幕 · iOS

`Sources/` 包含 iPhone 首页和画中画字幕，`Info.plist` 包含应用信息和权限说明。工作区引用仓库根目录的 Xcode 工程，与 Mac 版共享收音、转写和翻译代码。

## 在 iPhone 上运行

1. 克隆完整仓库，用 Xcode 27 或更新版本打开本目录的 `CallCaptions.xcworkspace`。
2. 在本目录创建 `Signing.local.xcconfig`，写入 `DEVELOPMENT_TEAM = 你的团队ID`。如需指定应用 ID，再写入 `PRODUCT_BUNDLE_IDENTIFIER = 你的应用ID`。已有项目仍会读取仓库根目录的同名文件，本目录的设置优先。个人签名配置由 Git 忽略。
3. 连接并解锁运行 iOS 27 或更新版本的 iPhone，在 Xcode 选择 `CallCaptions` 和该设备，点击运行。首次使用可以先测试收音，翻译前再设置所选服务的密钥。

## 命令行编译

从仓库根目录执行以下命令可构建未签名的 iPhone 应用。

```sh
cd ios
xcodebuild -workspace CallCaptions.xcworkspace -scheme CallCaptions \
  -destination 'generic/platform=iOS' -derivedDataPath build CODE_SIGNING_ALLOWED=NO build
```

收音与悬浮字幕的操作见[使用说明](../使用说明.md)。
