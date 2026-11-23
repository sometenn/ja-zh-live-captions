#if MAC_CAPTIONS
import AppKit
import SwiftUI

@main
struct MacCaptionsApp: App {
    @StateObject private var session = Session()

    var body: some Scene {
        Window("日语实时字幕", id: "home") {
            MacHome(session: session, overlay: session.overlay)
                .frame(minWidth: 490, idealWidth: 580, minHeight: 440)
                .onDisappear { session.finish() }
        }
        .defaultSize(width: 580, height: 480)
    }
}

private struct MacHome: View {
    @ObservedObject var session: Session
    @ObservedObject var overlay: Overlay
    @State private var settings = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("日语 → 简体中文").font(.title2.bold())
                Spacer()
                Button("密钥", systemImage: "key") { settings = true }
                    .disabled(session.stage != .idle)
            }
            Grid(alignment: .leading, horizontalSpacing: 18, verticalSpacing: 8) {
                GridRow {
                    Text("翻译服务")
                    Picker("翻译服务", selection: Binding(get: { session.provider }, set: session.select)) {
                        ForEach(Provider.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }.disabled(session.stage == .picking || session.stage == .stopping)
                }
                GridRow {
                    Text("收音来源")
                    Picker("收音来源", selection: Binding(get: { session.source }, set: session.selectSource)) {
                        ForEach(AudioSource.allCases, id: \.self) { Text($0.title).tag($0) }
                    }.disabled(session.stage != .idle)
                }
                GridRow {
                    Text("字幕显示")
                    Picker("字幕显示", selection: Binding(get: { session.displayMode }, set: session.selectDisplayMode)) {
                        ForEach(CaptionDisplayMode.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                }
            }.pickerStyle(.segmented).labelsHidden()
            MacCaptionDisplay(frame: session.frame)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            Text(session.switchNotice.isEmpty ? session.status : session.switchNotice)
                .foregroundStyle(.secondary)
            HStack {
                if session.stage == .idle {
                    Button("开始翻译") {
                        if session.hasKey { session.begin(.translate) } else { settings = true }
                    }.buttonStyle(.borderedProminent)
                    Button("测试收音") { session.begin(.probe) }
                } else {
                    Button("结束收音") { session.finish() }.disabled(session.stage == .stopping)
                    Button(overlay.active ? "收起置顶字幕" : "开启置顶字幕") {
                        if overlay.active { overlay.stop() } else { overlay.start() }
                    }.disabled(session.stage != .listening)
                }
            }
            Text("声音测试在本机完成。翻译时，所选声音会发给 " + session.provider.rawValue + "。")
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(24)
        .sheet(isPresented: $settings) { KeySheet(session: session).padding().frame(width: 440, height: 300) }
    }
}

private struct MacCaptionDisplay: View {
    let frame: OverlayFrame

    var body: some View {
        GeometryReader { geometry in
            content(width: max(1, geometry.size.width - 36))
                .padding(18)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
        .background(Color(red: 0.045, green: 0.075, blue: 0.11))
    }

    private func caption(_ text: String, width: CGFloat, size: CGFloat, weight: NSFont.Weight) -> Text {
        let font = NSFont.systemFont(ofSize: size, weight: weight)
        let recent = CaptionTextLayout.fitRecentPhrase(text) {
            ($0 as NSString).size(withAttributes: [.font: font]).width <= width
        }
        return Text(verbatim: recent).font(Font(font))
    }

    private func content(width: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if let banner = frame.banner {
                Text(banner.text).font(.caption).foregroundStyle(.yellow)
            }
            switch frame.style {
            case let .bilingual(top, bottom):
                caption(top, width: width, size: 17, weight: .regular).foregroundStyle(.white.opacity(0.72))
                caption(bottom, width: width, size: 23, weight: .semibold)
            case let .chineseOnly(text, _):
                caption(text, width: width, size: 30, weight: .semibold)
            case let .probe(status, meter):
                Text(status).font(.headline)
                Text(meter).font(.system(size: 22, design: .monospaced)).foregroundStyle(.mint)
            }
        }
        .lineLimit(2).truncationMode(.head)
        .foregroundStyle(.white)
    }
}

private struct FloatingCaptions: View {
    @ObservedObject var overlay: Overlay
    var body: some View { MacCaptionDisplay(frame: overlay.frame) }
}

@MainActor
final class Overlay: NSObject, ObservableObject, NSWindowDelegate {
    @Published private(set) var frame = CaptionPresentation.idleFrame(mode: .bilingual)
    @Published private(set) var active = false
    private var panel: NSPanel?

    func prepare() {}
    func draw(_ next: OverlayFrame) { if frame != next { frame = next } }

    func start() {
        if panel == nil {
            let window = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 620, height: 142),
                                 styleMask: [.titled, .closable, .resizable, .nonactivatingPanel],
                                 backing: .buffered, defer: false)
            window.title = "置顶字幕"
            window.level = .floating
            window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            window.hidesOnDeactivate = false
            window.isReleasedWhenClosed = false
            window.isMovableByWindowBackground = true
            window.contentMinSize = NSSize(width: 300, height: 100)
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
