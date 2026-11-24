import SwiftUI
import WidgetKit

@main
struct CaptionsControls: WidgetBundle {
    var body: some Widget { StartCaptionsControl() }
}

private struct StartCaptionsControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "StartLiveCaptions") {
            ControlWidgetButton(action: StartCaptionsIntent()) {
                Label("开启实时字幕", systemImage: "captions.bubble.fill")
            }
        }
        .displayName("开启实时字幕")
        .description("使用上次选好的收音来源和翻译服务，开始日语实时字幕。")
    }
}
