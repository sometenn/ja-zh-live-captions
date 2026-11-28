#if MAC_CAPTIONS
import AppKit
import SwiftUI

enum CaptionInk: String, CaseIterable {
    case white = "白色", mint = "薄荷", amber = "暖黄"

    var color: Color {
        switch self {
        case .white: .white
        case .mint: Color(red: 0.65, green: 0.97, blue: 0.83)
        case .amber: Color(red: 1, green: 0.87, blue: 0.56)
        }
    }
}

struct MacCaptionAppearance: DynamicProperty {
    @AppStorage("mac.caption.fontFamily") var family = ""
    @AppStorage("mac.caption.sourceSize") var sourceSize = 18.0
    @AppStorage("mac.caption.translationSize") var translationSize = 30.0
    @AppStorage("mac.caption.mainMaxLines") var mainMaxLines = 4
    @AppStorage("mac.caption.maxLines") var maxLines = 1
    @AppStorage("mac.caption.ink") var ink = CaptionInk.white
    @AppStorage("mac.caption.opacity") var opacity = 92.0

    func font(size: CGFloat, translated: Bool) -> NSFont {
        if !family.isEmpty, let font = NSFontManager.shared.font(
            withFamily: family, traits: [], weight: translated ? 8 : 5, size: size
        ) { return font }
        return .systemFont(ofSize: size, weight: translated ? .semibold : .regular)
    }

    func restoreDefaults() {
        mainMaxLines = 4
        maxLines = 1
        family = ""
        sourceSize = 18
        translationSize = 30
        ink = .white
        opacity = 92
    }
}

struct MacAppearanceControls: View {
    private var style = MacCaptionAppearance()
    private struct Family {
        let name: String
        let label: String
    }
    private static let families: [Family] = {
        let manager = NSFontManager.shared
        return manager.availableFontFamilies.map {
            Family(name: $0, label: manager.localizedName(forFamily: $0, face: nil))
        }.sorted { $0.label.localizedStandardCompare($1.label) == .orderedAscending }
    }()

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 12) {
            GridRow {
                Label("字幕字体", systemImage: "textformat")
                Picker("字幕字体", selection: style.$family) {
                    Text("系统字体").tag("")
                    Divider()
                    ForEach(Self.families, id: \.name) { Text($0.label).tag($0.name) }
                }.labelsHidden().frame(maxWidth: .infinity)
            }
            slider("日语字号", icon: "textformat.size.smaller", value: style.$sourceSize, range: 12...34, unit: "pt")
            slider("中文字号", icon: "textformat.size.larger", value: style.$translationSize, range: 16...52, unit: "pt")
            GridRow {
                Label("译文颜色", systemImage: "paintpalette")
                Picker("译文颜色", selection: style.$ink) {
                    ForEach(CaptionInk.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }.pickerStyle(.segmented).labelsHidden()
            }
            GridRow {
                Label("主窗口行数", systemImage: "text.alignleft")
                Stepper("\(style.mainMaxLines) 行", value: style.$mainMaxLines, in: 1...8)
            }
            GridRow {
                Label("置顶窗行数", systemImage: "text.alignleft")
                Picker("最多显示", selection: style.$maxLines) {
                    ForEach(1...4, id: \.self) { Text("\($0) 行").tag($0) }
                }.pickerStyle(.segmented).labelsHidden()
            }
            slider("底色浓度", icon: "circle.lefthalf.filled", value: style.$opacity, range: 25...100, unit: "%")
        }
    }

    private func slider(_ label: String, icon: String, value: Binding<Double>,
                        range: ClosedRange<Double>, unit: String) -> some View {
        GridRow {
            Label(label, systemImage: icon)
            HStack {
                Slider(value: value, in: range, step: 1) { Text(label) }
                    .labelsHidden()
                Text("\(Int(value.wrappedValue)) \(unit)")
                    .monospacedDigit().foregroundStyle(.secondary).frame(width: 52, alignment: .trailing)
            }
        }
    }
}

struct MacCaptionDisplay: View {
    let frame: OverlayFrame
    var isMainWindow = false
    private var style = MacCaptionAppearance()

    static func sample(_ mode: CaptionDisplayMode) -> OverlayFrame {
        OverlayFrame(banner: nil, style: mode == .bilingual
                     ? .bilingual(top: "今日はどんな一日でしたか？", bottom: "你今天过得怎么样？")
                     : .chineseOnly(text: "你今天过得怎么样？", isPlaceholder: false))
    }

    var body: some View {
        GeometryReader { geometry in
            let available = max(1, geometry.size.height - (frame.banner == nil ? 8 : 40))
            let ratio = style.sourceSize / (style.sourceSize + style.translationSize)
            VStack(alignment: .leading, spacing: 7) {
                if let banner = frame.banner {
                    Text(banner.text).font(.callout).foregroundStyle(.yellow)
                }
                switch frame.style {
                case let .bilingual(top, bottom):
                    caption(top, width: geometry.size.width - 40, height: available * ratio, translated: false)
                    caption(bottom, width: geometry.size.width - 40, height: available * (1 - ratio), translated: true)
                case let .chineseOnly(text, _):
                    caption(text, width: geometry.size.width - 40, height: available, translated: true)
                case let .probe(status, meter):
                    Text(status).font(.headline)
                    Text(meter).font(.system(size: 24, design: .monospaced)).foregroundStyle(.mint)
                }
            }
            .padding(.horizontal, 20)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
        .foregroundStyle(.white)
        .background(Color(red: 0.045, green: 0.13, blue: 0.14).opacity(style.opacity / 100))
    }

    private func caption(_ text: String, width: CGFloat, height: CGFloat, translated: Bool) -> some View {
        let font = style.font(size: translated ? style.translationSize : style.sourceSize, translated: translated)
        let lineHeight = NSLayoutManager().defaultLineHeight(for: font)
        let lines = min(isMainWindow ? style.mainMaxLines : style.maxLines, max(1, Int(height / max(1, lineHeight))))
        let recent = CaptionTextLayout.fitRecentPhrase(text, maxWindow: isMainWindow ? Captions.recentLimit : 160) { candidate in
            let bounds = (candidate as NSString).boundingRect(
                with: NSSize(width: max(1, width), height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: [.font: font])
            return bounds.height <= ceil(lineHeight * CGFloat(lines)) + 0.5
        }
        return Text(verbatim: recent).font(Font(font)).lineLimit(lines)
            .foregroundStyle(translated ? style.ink.color : Color.white.opacity(0.82))
    }
}
#endif
