# 日语实时字幕 · Windows

Windows 桌面端位于 `windows/` 目录，使用 C# / .NET 8 + WPF 实现，功能对齐 macOS 版：

- **翻译服务**：支持 Soniox 与 Gemini（`gemini-3.5-live-translate-preview`），支持翻译中实时切换服务
- **收音来源**：支持 WASAPI 机内声音（Loopback）与麦克风采集，并在本机重采样为 16 kHz 单声道 PCM，支持无网络的「测试收音」音量条模式
- **置顶字幕窗**：无边框半透明置顶窗口，支持拖动、调整大小与位置记忆，关闭小窗不影响后台收音
- **字幕外观**：支持选择系统字体、分别调整日语与中文字号、译文颜色（白色 / 薄荷 / 暖黄）和底色浓度，设置即时生效并保存
- **本地加密密钥**：使用 Windows DPAPI（`ProtectedData` / `CurrentUser`）将密钥加密保存在 `%LocalAppData%\CallCaptions\`

## 环境要求

- Windows 10 / Windows 11（x64）
- .NET 8 SDK 或更高版本（已在 .NET SDK 8.0.303 / 10.0.301 验证）

## 运行与测试

```powershell
cd windows
dotnet test CallCaptions.sln
dotnet run --project CallCaptions.App\CallCaptions.App.csproj
```

打包为 Release 版本：

```powershell
cd windows
dotnet build CallCaptions.sln -c Release
```

## 工程结构

- `CallCaptions.Core/`：与界面解耦的核心逻辑（字幕增量状态机与简繁转换、短语换行排版、有界 PCM 队列、重采样、WebSocket 客户端、错误分类与重连策略、DPAPI 密钥存储与会话状态机）
- `CallCaptions.App/`：WPF 桌面端界面、WASAPI 音频采集与置顶悬浮字幕窗
- `CallCaptions.Tests/`：xUnit 离线单元测试与会话状态机回归测试
