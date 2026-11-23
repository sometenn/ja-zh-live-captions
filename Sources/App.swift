import SwiftUI

#if os(iOS)
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
                    Picker("翻译服务", selection: Binding(get: { session.provider }, set: { session.select($0) })) {
                        ForEach(Provider.allCases, id: \.self) { provider in
                            Text(verbatim: provider.rawValue).tag(provider)
                        }
                    }
                    .pickerStyle(.segmented)
                    .disabled(session.stage == .picking || session.stage == .stopping)
                    if !session.switchNotice.isEmpty {
                        Text(session.switchNotice).font(.footnote).foregroundStyle(.secondary)
                    }
                    Picker("收音来源", selection: Binding(get: { session.source }, set: { session.selectSource($0) })) {
                        ForEach(AudioSource.allCases, id: \.self) { source in
                            Text(source.title).tag(source)
                        }
                    }
                    .pickerStyle(.segmented)
                    .disabled(session.stage != .idle)
                    Picker("字幕显示", selection: Binding(get: { session.displayMode }, set: { session.selectDisplayMode($0) })) {
                        ForEach(CaptionDisplayMode.allCases, id: \.self) { mode in
                            Text(mode.title).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    .disabled(session.stage == .stopping)
                    OverlayPanel(overlay: session.overlay,
                                 displayMode: session.displayMode,
                                 listening: session.stage == .listening,
                                 onAppear: { session.refreshPreviewIfIdle() })
                    Label(session.status, systemImage: statusIcon)
                        .foregroundStyle(session.isReconnecting ? .orange : .primary)
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
                        Text(session.source == .microphone ? "对着麦克风说话，观察音量条是否跳动。测试仅在本机运行。" : "测试时戴上耳机，开启悬浮字幕，再切回通话。请对方说话，观察音量条是否跳动。声音测试仅在本机运行。")
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
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("密钥", systemImage: "key") { settings = true }.disabled(session.stage != .idle)
                }
            }
            .sheet(isPresented: $settings) { KeySheet(session: session) }
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

struct KeySheet: View {
    @ObservedObject var session: Session
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @State private var error = ""
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField(session.hasKey ? "输入新密钥以替换" : "输入 " + session.provider.rawValue + " API 密钥", text: $draft)
                        #if os(iOS)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        #endif
                    Button("保存密钥") { store(draft) }.disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if session.hasKey {
                        Button("移除本机密钥", role: .destructive) { store("") }
                    }
                } footer: { Text("密钥保存在这台设备的钥匙串中。") }
                if session.provider == .gemini {
                    LabeledContent("实时翻译模型", value: Gemini.model)
                    Text("日语转写和中文翻译共用一条 Gemini 连接，译文以字幕显示。").font(.footnote)
                }
                if !error.isEmpty { Text(error) }
            }
            .navigationTitle(session.provider.rawValue + " 密钥")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }
    }
    private func store(_ value: String) {
        do { try session.saveKey(value); dismiss() }
        catch { self.error = error.localizedDescription }
    }
}
