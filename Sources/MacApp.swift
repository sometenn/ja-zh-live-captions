#if MAC_CAPTIONS
import AppKit
import SwiftUI

@main
struct MacCaptionsApp: App {
    @StateObject private var session = Session()

    var body: some Scene {
        Window("日语实时字幕", id: "home") {
            MacHome(session: session, overlay: session.overlay)
                .frame(minWidth: 535, minHeight: 620)
                .onDisappear { session.finish() }
        }
        .defaultSize(width: 600, height: 650)
    }
}

private struct MacHome: View {
    @ObservedObject var session: Session
    @ObservedObject var overlay: Overlay
    @State private var keysVisible = false
    @State private var editingAppearance = false
    private let accent = Color(red: 0.10, green: 0.51, blue: 0.45)
    private var appearance = MacCaptionAppearance()

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Image(systemName: "captions.bubble.fill")
                    .font(.system(size: 21, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 43, height: 43)
                    .background(accent.gradient, in: RoundedRectangle(cornerRadius: 13))
                VStack(alignment: .leading, spacing: 3) {
                    Text("日语实时字幕").font(.system(size: 23, weight: .semibold))
                    Text("日语 → 简体中文").foregroundStyle(.secondary).font(.subheadline)
                }
                Spacer()
                Button("密钥", systemImage: "key") { keysVisible = true }
                    .disabled(session.stage != .idle)
            }
            VStack(alignment: .leading, spacing: 9) {
                HStack {
                    Label(session.stage == .idle ? "字幕预览" : "实时字幕", systemImage: "captions.bubble")
                        .font(.headline)
                    Spacer()
                    Button(overlay.active ? "收起置顶字幕" : "开启置顶字幕", systemImage: "macwindow.on.rectangle") {
                        if overlay.active { overlay.stop() } else { overlay.start() }
                    }
                    .controlSize(.small)
                    .disabled(session.stage == .stopping)
                }
                MacCaptionDisplay(frame: session.stage == .idle ? MacCaptionDisplay.sample(session.displayMode) : session.frame)
                    .frame(minHeight: 145, maxHeight: 210)
                    .background(LinearGradient(colors: [Color.gray.opacity(0.2), Color.gray.opacity(0.55)],
                                               startPoint: .topLeading, endPoint: .bottomTrailing))
                    .clipShape(RoundedRectangle(cornerRadius: 15))
            }
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Picker("设置", selection: $editingAppearance) {
                        Text("会话设置").tag(false)
                        Text("字幕外观").tag(true)
                    }.pickerStyle(.segmented).labelsHidden().fixedSize()
                    Spacer()
                    if editingAppearance {
                        Button("恢复默认外观") { appearance.restoreDefaults() }.controlSize(.small)
                    }
                }
                Divider()
                if editingAppearance {
                    MacAppearanceControls()
                } else {
                    sessionControls
                }
            }
            .padding(16)
            .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 14))
            .overlay { RoundedRectangle(cornerRadius: 14).strokeBorder(.quaternary) }
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 10) {
                Divider()
                HStack {
                    Text(session.switchNotice.isEmpty ? session.status : session.switchNotice)
                        .foregroundStyle(.secondary).font(.callout).textSelection(.enabled)
                    Spacer()
                    if session.stage == .idle {
                        Button("测试收音") { session.begin(.probe) }
                        Button("开始翻译", systemImage: "play.fill") {
                            if session.hasKey { session.begin(.translate) } else { keysVisible = true }
                        }.buttonStyle(.borderedProminent)
                    } else {
                        Button("结束收音", systemImage: "stop.fill") { session.finish() }
                            .disabled(session.stage == .stopping)
                    }
                }
            }
        }
        .padding(24)
        .tint(accent)
        .background(Color(nsColor: .windowBackgroundColor))
        .sheet(isPresented: $keysVisible) { KeySheet(session: session).padding().frame(width: 440, height: 300) }
    }

    private var sessionControls: some View {
        Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 14) {
            GridRow {
                Label("翻译服务", systemImage: "globe")
                Picker("翻译服务", selection: Binding(get: { session.provider }, set: session.select)) {
                    ForEach(Provider.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }.disabled(session.stage == .picking || session.stage == .stopping)
            }
            GridRow {
                Label("收音来源", systemImage: "waveform")
                Picker("收音来源", selection: Binding(get: { session.source }, set: session.selectSource)) {
                    ForEach(AudioSource.allCases, id: \.self) { Text($0.title).tag($0) }
                }.disabled(session.stage != .idle)
            }
            GridRow {
                Label("字幕显示", systemImage: "text.alignleft")
                Picker("字幕显示", selection: Binding(get: { session.displayMode }, set: session.selectDisplayMode)) {
                    ForEach(CaptionDisplayMode.allCases, id: \.self) { Text($0.title).tag($0) }
                }
            }
            Text("测试收音仅在本机进行。翻译时，所选声音会发送给 " + session.provider.rawValue + "。")
                .font(.caption).foregroundStyle(.secondary)
        }.pickerStyle(.segmented).labelsHidden()
    }
}

private struct FloatingCaptions: View {
    @ObservedObject var overlay: Overlay
    var body: some View {
        MacCaptionDisplay(frame: overlay.previewFrame)
    }
}

@MainActor
final class Overlay: NSObject, ObservableObject, NSWindowDelegate {
    @Published private(set) var frame = CaptionPresentation.idleFrame(mode: .bilingual)
    @Published private(set) var active = false
    private var panel: NSPanel?

    var previewFrame: OverlayFrame {
        for mode in CaptionDisplayMode.allCases where frame == CaptionPresentation.idleFrame(mode: mode) {
            return MacCaptionDisplay.sample(mode)
        }
        return frame
    }

    func prepare() {}
    func draw(_ next: OverlayFrame) { if frame != next { frame = next } }

    func start() {
        if panel == nil {
            let window = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 620, height: 170),
                                 styleMask: [.titled, .closable, .resizable, .nonactivatingPanel],
                                 backing: .buffered, defer: false)
            window.title = "置顶字幕"
            window.isOpaque = false
            window.backgroundColor = .clear
            window.level = .floating
            window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            window.hidesOnDeactivate = false
            window.isReleasedWhenClosed = false
            window.isMovableByWindowBackground = true
            window.contentMinSize = NSSize(width: 300, height: 158)
            window.contentView = NSHostingView(rootView: FloatingCaptions(overlay: self))
            window.center()
            window.setFrameAutosaveName("FloatingCaptions")
            window.delegate = self
            panel = window
        }
        panel?.orderFrontRegardless()
        active = true
    }

    func stop() { panel?.orderOut(nil); active = false }
    func windowWillClose(_ notification: Notification) { active = false }
}
#endif
