import SwiftUI

#if os(iOS)
import AppIntents
@main
struct CallCaptionsApp: App {
    @StateObject private var session = Session()
    var body: some Scene {
        WindowGroup { Home(session: session) }
    }
}

private struct Home: View {
    @ObservedObject var session: Session
    @State private var settings = false
    @State private var appearanceVisible = false

    @Environment(\.scenePhase) private var scenePhase
    @State private var startRequested = false
    @State private var startAfterKey = false
    @State private var openOverlayWhenReady = false

    private func handleStartRequest() {
        guard scenePhase == .active else { return }
        if startRequested {
            startRequested = false
            if session.stage == .idle {
                guard session.hasKey else {
                    startAfterKey = true
                    settings = true
                    return
                }
                session.begin(.translate)
                openOverlayWhenReady = session.stage == .picking || session.stage == .listening
            } else if session.mode == .translate && session.stage != .stopping {
                openOverlayWhenReady = true
            }
        }
        if openOverlayWhenReady && session.stage == .listening {
            openOverlayWhenReady = false
            session.overlay.start()
        }
    }

    private var statusIcon: String {
        if session.isReconnecting { return "arrow.triangle.2.circlepath" }
        return session.stage == .listening ? "waveform" : "circle"
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    HStack {
                        Text("日语").foregroundStyle(.secondary)
                        Image(systemName: "arrow.right")
                        Text("简体中文").fontWeight(.semibold)
                    }
                    .font(.headline)
                    Label(session.status, systemImage: statusIcon)
                        .foregroundStyle(session.isReconnecting ? .orange : .primary)
                    Text(session.stage == .idle ? "字幕预览" : "实时字幕").font(.subheadline.weight(.semibold))
                    OverlayPanel(overlay: session.overlay,
                                 displayMode: session.displayMode,
                                 listening: session.stage == .listening,
                                 onAppear: { session.refreshPreviewIfIdle() })
                    SaveTranscriptButton(session: session)
                    GroupBox("会话设置") {
                        VStack(alignment: .leading, spacing: 8) {
                            LabeledContent("翻译服务") {
                                Picker("翻译服务", selection: Binding(get: { session.provider }, set: { session.select($0) })) {
                                    ForEach(Provider.allCases, id: \.self) { provider in
                                        Text(verbatim: provider.rawValue).tag(provider)
                                    }
                                }
                                .pickerStyle(.menu)
                                .labelsHidden()
                                .disabled(session.stage == .picking || session.stage == .stopping)
                            }
                            if !session.switchNotice.isEmpty {
                                Text(session.switchNotice).font(.footnote).foregroundStyle(.secondary)
                            }
                            LabeledContent("收音来源") {
                                Picker("收音来源", selection: Binding(get: { session.source }, set: { session.selectSource($0) })) {
                                    ForEach(AudioSource.allCases, id: \.self) { source in
                                        Text(source.title).tag(source)
                                    }
                                }
                                .pickerStyle(.menu)
                                .labelsHidden()
                                .disabled(session.stage != .idle)
                            }
                            LabeledContent("字幕显示") {
                                Picker("字幕显示", selection: Binding(get: { session.displayMode }, set: { session.selectDisplayMode($0) })) {
                                    ForEach(CaptionDisplayMode.allCases, id: \.self) { mode in
                                        Text(mode.title).tag(mode)
                                    }
                                }
                                .pickerStyle(.menu)
                                .labelsHidden()
                                .disabled(session.stage == .stopping)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if session.stage == .idle {
                        Button {
                            if session.hasKey { session.begin(.translate) } else { settings = true }
                        } label: {
                            Label(session.hasKey ? "开始翻译" : "设置 " + session.provider.rawValue + " 密钥", systemImage: "captions.bubble")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        Button(session.source == .system ? "测试通话声音" : "测试麦克风") { session.begin(.probe) }
                            .buttonStyle(.bordered)
                        Text(session.source == .microphone ? "对着麦克风说话即可测试音量。测试仅在本机运行。" : "播放视频或让对方讲话，观察音量条。测试仅在本机运行。")
                            .font(.footnote).foregroundStyle(.secondary)
                    } else {
                        Button(session.source == .system ? "结束共享" : "结束收音", role: .destructive) { session.finish() }
                            .buttonStyle(.bordered).disabled(session.stage == .stopping)
                    }
                    Text(session.source == .microphone ? "翻译时，麦克风收到的声音会直接发给 \(session.provider.rawValue)。" : "翻译时，系统共享的声音会直接发给 \(session.provider.rawValue)。其他正在播放的应用也可能被听到。字幕仅在本次共享期间显示。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .padding()
            }
            .navigationTitle("日语实时字幕")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("外观") { appearanceVisible = true }
                    Button("密钥") { settings = true }.disabled(session.stage != .idle)
                }
            }
            .sheet(isPresented: $appearanceVisible) { PhoneAppearanceSheet(overlay: session.overlay) }
            .sheet(isPresented: $settings, onDismiss: {
                guard startAfterKey else { return }
                startAfterKey = false
                startRequested = session.hasKey
                handleStartRequest()
            }) { KeySheet(session: session) }
        }
        .tint(Color(red: 0.10, green: 0.51, blue: 0.45))
        .onAppIntentExecution(StartCaptionsIntent.self) { _ in
            startRequested = true
            handleStartRequest()
        }
        .onChange(of: scenePhase) { _, _ in handleStartRequest() }
        .onChange(of: session.stage) { _, stage in
            if stage == .idle || stage == .stopping { openOverlayWhenReady = false }
            handleStartRequest()
        }
    }
}

private struct PhoneAppearanceSheet: View {
    @ObservedObject var overlay: Overlay
    @Environment(\.dismiss) private var dismiss
    private static let families = UIFont.familyNames.sorted()

    var body: some View {
        NavigationStack {
            Form {
                Section("字幕预览") {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("今日はどんな一日でしたか？")
                            .font(Font(overlay.appearance.font(size: overlay.appearance.sourceSize / 1.6, translated: false)))
                            .foregroundStyle(.white.opacity(0.82))
                        Text("你今天过得怎么样？")
                            .font(Font(overlay.appearance.font(size: overlay.appearance.translationSize / 1.6, translated: true)))
                            .foregroundStyle(Color(uiColor: overlay.appearance.translationColor))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding()
                    .background(overlay.appearance.background == "纯黑" ? Color.black : Color(red: 0.045, green: 0.13, blue: 0.14),
                                in: RoundedRectangle(cornerRadius: 12))
                }
                Picker("字幕字体", selection: $overlay.appearance.family) {
                    Text("系统字体").tag("")
                    ForEach(Self.families, id: \.self) { Text($0).tag($0) }
                }
                Stepper("日语字号 · \(Int(overlay.appearance.sourceSize))", value: $overlay.appearance.sourceSize, in: 16...40)
                Stepper("中文字号 · \(Int(overlay.appearance.translationSize))", value: $overlay.appearance.translationSize, in: 20...54)
                Stepper("最多显示 · \(overlay.appearance.maxLines) 行", value: $overlay.appearance.maxLines, in: 1...4)
                Picker("译文颜色", selection: $overlay.appearance.ink) {
                    ForEach(["白色", "薄荷", "暖黄"], id: \.self) { Text($0) }
                }
                Picker("画中画底色", selection: $overlay.appearance.background) {
                    ForEach(["墨绿", "纯黑"], id: \.self) { Text($0) }
                }
                Button("恢复默认外观") { overlay.appearance = PhoneCaptionAppearance() }
            }
            .navigationTitle("字幕外观")
            .toolbar { Button("完成") { dismiss() } }
        }
    }
}

private struct OverlayPanel: View {
    @ObservedObject var overlay: Overlay
    let displayMode: CaptionDisplayMode
    let listening: Bool
    let onAppear: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SurfaceView(surface: overlay.surface)
                .aspectRatio(768.0 / 224.0, contentMode: .fit)
                .background(Color.black)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .accessibilityLabel(displayMode.accessibilityLabel)
                .onAppear { if !listening { onAppear() } }
            if listening {
                Button(overlay.stopping ? "正在收起悬浮字幕…" : overlay.active ? "悬浮字幕已开启" : overlay.starting ? "正在开启悬浮字幕…" : "开启悬浮字幕", systemImage: "pip.enter") { overlay.start() }
                    .buttonStyle(.borderedProminent).disabled(overlay.active || overlay.starting || overlay.stopping)
                if overlay.active { Text("现在可以切回通话或视频。").font(.footnote) }
                if !overlay.notice.isEmpty { Text(overlay.notice).font(.footnote) }
            }
        }
    }
}

#endif
