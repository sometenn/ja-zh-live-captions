#if canImport(UIKit)
import AVKit
import OSLog
import SwiftUI
import UIKit

struct PhoneCaptionAppearance: Codable, Equatable {
    var family = ""
    var sourceSize = 30.0
    var translationSize = 52.0
    var maxLines = 2
    var ink = "白色"
    var background = "墨绿"

    var translationColor: UIColor {
        switch ink {
        case "薄荷": UIColor(red: 0.65, green: 0.97, blue: 0.83, alpha: 1)
        case "暖黄": UIColor(red: 1, green: 0.87, blue: 0.56, alpha: 1)
        default: .white
        }
    }

    func font(size: CGFloat, translated: Bool) -> UIFont {
        if let name = UIFont.fontNames(forFamilyName: family).first,
           let font = UIFont(name: name, size: size) { return font }
        return .systemFont(ofSize: size, weight: translated ? .semibold : .medium)
    }
}

final class CaptionSurface: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }
    var display: AVSampleBufferDisplayLayer { layer as! AVSampleBufferDisplayLayer }
}

struct SurfaceView: UIViewRepresentable {
    let surface: CaptionSurface
    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .clear
        attachSurface(to: container)
        return container
    }
    func updateUIView(_ uiView: UIView, context: Context) {
        attachSurface(to: uiView)
    }
    private func attachSurface(to container: UIView) {
        guard surface.superview !== container else { return }
        surface.translatesAutoresizingMaskIntoConstraints = false
        surface.removeFromSuperview()
        container.addSubview(surface)
        NSLayoutConstraint.activate([
            surface.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            surface.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            surface.topAnchor.constraint(equalTo: container.topAnchor),
            surface.bottomAnchor.constraint(equalTo: container.bottomAnchor)
        ])
    }
}

@MainActor
final class Overlay: NSObject, ObservableObject, AVPictureInPictureSampleBufferPlaybackDelegate,
                     AVPictureInPictureControllerDelegate {
    @Published var appearance: PhoneCaptionAppearance = {
        guard let data = UserDefaults.standard.data(forKey: "ios.caption.appearance"),
              let value = try? JSONDecoder().decode(PhoneCaptionAppearance.self, from: data) else {
            return PhoneCaptionAppearance()
        }
        return value
    }() {
        didSet {
            if let data = try? JSONEncoder().encode(appearance) {
                UserDefaults.standard.set(data, forKey: "ios.caption.appearance")
            }
            if let lastFrame { draw(lastFrame) }
        }
    }

    private enum Phase {
        case idle, startingCall, waitingForStandard, startingStandard, showingCall, showingStandard, stopping
    }

    let surface = CaptionSurface()
    @Published private var phase = Phase.idle
    var active: Bool { phase == .showingCall || phase == .showingStandard }
    var starting: Bool { phase == .startingCall || phase == .waitingForStandard || phase == .startingStandard }
    var stopping: Bool { phase == .stopping }
    @Published var notice = ""
    private(set) var lastFrame: OverlayFrame?
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "CallCaptions", category: "OverlayPiP")
    private let clock = AVSampleBufferRenderSynchronizer()
    private lazy var receiver = clock.sampleBufferReceiver(adding: surface.display.sampleBufferRenderer)
    private var controller: AVPictureInPictureController?
    private var standardContentSource: AVPictureInPictureController.ContentSource?
    private var activatedAudio = false
    private var callImageView: UIImageView?
    private var pendingStart: Task<Void, Never>?

    override init() {
        super.init()
        surface.display.videoGravity = .resizeAspect
        _ = receiver
        clock.delaysRateChangeUntilHasSufficientMediaData = false
        clock.setRate(1, time: .zero)
        configureController()
    }

    private func configureController() {
        guard AVPictureInPictureController.isPictureInPictureSupported() else {
            notice = "这台设备未提供画中画。"
            return
        }
        let source = AVPictureInPictureController.ContentSource(sampleBufferDisplayLayer: surface.display,
                                                                playbackDelegate: self)
        let pip = AVPictureInPictureController(contentSource: source)
        pip.delegate = self
        pip.requiresLinearPlayback = true
        pip.canStartPictureInPictureAutomaticallyFromInline = false
        standardContentSource = source
        controller = pip
    }

    func prepare() {
        guard controller != nil else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: .mixWithOthers)
            try session.setActive(true)
            activatedAudio = true
            notice = ""
        } catch { notice = "画中画音频会话未就绪。\(error.localizedDescription)" }
    }

    func start() {
        guard phase == .idle, let pip = controller else { return }
        let anchorView = surface.superview ?? surface
        guard anchorView.window != nil else {
            notice = "请回到日语实时字幕首页，再开启悬浮字幕。"
            return
        }
        notice = ""
        phase = .startingCall
        let viewController = AVPictureInPictureVideoCallViewController()
        viewController.preferredContentSize = CGSize(width: 768, height: 224)
        let backgroundColor = UIColor(red: 0.045, green: 0.075, blue: 0.11, alpha: 1)
        viewController.view.backgroundColor = backgroundColor
        let imageView = UIImageView()
        imageView.backgroundColor = backgroundColor
        imageView.contentMode = .scaleAspectFit
        imageView.translatesAutoresizingMaskIntoConstraints = false
        viewController.view.addSubview(imageView)
        NSLayoutConstraint.activate([
            imageView.leadingAnchor.constraint(equalTo: viewController.view.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: viewController.view.trailingAnchor),
            imageView.topAnchor.constraint(equalTo: viewController.view.topAnchor),
            imageView.bottomAnchor.constraint(equalTo: viewController.view.bottomAnchor)
        ])
        callImageView = imageView
        if let frame = lastFrame {
            draw(frame)
        }
        let source = AVPictureInPictureController.ContentSource(
            activeVideoCallSourceView: anchorView,
            contentViewController: viewController
        )
        pip.contentSource = nil
        pip.requiresLinearPlayback = false
        pip.canStartPictureInPictureAutomaticallyFromInline = true
        pip.contentSource = source
        logger.info("Starting call PiP before standard fallback")
        armStartTimeout(for: pip)
        pip.startPictureInPicture()
    }

    // Use the same controller and content-source transition as the two taps in 0.3.2.
    private func startStandardAfterCallFailure(_ pip: AVPictureInPictureController) {
        phase = .waitingForStandard
        callImageView = nil
        pip.canStartPictureInPictureAutomaticallyFromInline = false
        pip.contentSource = nil
        pip.requiresLinearPlayback = true
        pip.contentSource = standardContentSource
        pendingStart?.cancel()
        pendingStart = Task { @MainActor [weak self] in
            // Let AVKit complete the failed presentation before starting another.
            try? await Task.sleep(for: .milliseconds(500))
            for _ in 0..<30 {
                guard !Task.isCancelled, let self, self.phase == .waitingForStandard,
                      self.controller === pip else { return }
                if pip.isPictureInPicturePossible {
                    self.phase = .startingStandard
                    self.logger.info("Starting standard PiP after call PiP attempt")
                    self.armStartTimeout(for: pip)
                    pip.startPictureInPicture()
                    return
                }
                try? await Task.sleep(for: .milliseconds(100))
            }
            guard !Task.isCancelled, let self, self.controller === pip else { return }
            self.presentationEnded()
            self.notice = "系统尚未允许画中画，请稍后再点一次。"
        }
    }

    private func armStartTimeout(for pip: AVPictureInPictureController) {
        pendingStart?.cancel()
        pendingStart = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled, let self, self.controller === pip else { return }
            self.requestPresentationStop()
            self.notice = "悬浮窗启动超时，请稍后再试。"
        }
    }

    private func presentationEnded() {
        pendingStart?.cancel()
        pendingStart = nil
        phase = .idle
        // AVKit may still query the display layer after didStop. Keep its controller
        // and content source alive. The next user-initiated start selects a new source.
    }

    private func requestPresentationStop() {
        guard let pip = controller else { return }
        switch phase {
        case .idle, .stopping:
            return
        case .waitingForStandard:
            // The first start failed and the second has not been requested yet.
            presentationEnded()
        case .startingCall, .startingStandard, .showingCall, .showingStandard:
            pendingStart?.cancel()
            phase = .stopping
            pip.canStartPictureInPictureAutomaticallyFromInline = false
            pip.stopPictureInPicture()
            // A cancelled start can stay inactive without a didStop callback.
            pendingStart = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(6))
                guard !Task.isCancelled, let self, self.phase == .stopping,
                      !pip.isPictureInPictureActive else { return }
                self.presentationEnded()
            }
        }
    }

    func stop() {
        requestPresentationStop()
        if activatedAudio {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            activatedAudio = false
        }
    }

    func draw(_ input: OverlayFrame) {
        lastFrame = input
        let frame: OverlayFrame
        if input == CaptionPresentation.idleFrame(mode: .bilingual) {
            frame = OverlayFrame(banner: nil, style: .bilingual(top: "今日はどんな一日でしたか？", bottom: "你今天过得怎么样？"))
        } else if input == CaptionPresentation.idleFrame(mode: .chineseOnly) {
            frame = OverlayFrame(banner: nil, style: .chineseOnly(text: "你今天过得怎么样？", isPlaceholder: false))
        } else {
            frame = input
        }
        let width = 768, height = 224
        var buffer: CVPixelBuffer?
        let attrs = [kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any]] as CFDictionary
        guard CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32BGRA, attrs, &buffer) == kCVReturnSuccess,
              let buffer else { return }
        CVPixelBufferLockBaseAddress(buffer, [])
        let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: width, height: height,
                                bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                                space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue)
        guard let context else { CVPixelBufferUnlockBaseAddress(buffer, []); return }
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        UIGraphicsPushContext(context)
        (appearance.background == "纯黑" ? UIColor.black : UIColor(red: 0.045, green: 0.13, blue: 0.14, alpha: 1)).setFill()
        UIRectFill(CGRect(x: 0, y: 0, width: width, height: height))

        let hasBanner = frame.banner != nil
        if let banner = frame.banner {
            drawBanner(banner, in: CGRect(x: 20, y: 8, width: 728, height: 34))
        }

        switch frame.style {
        case .bilingual(let top, let bottom):
            let y: CGFloat = hasBanner ? 48 : 12
            let height = (216 - y - 6)
            let ratio = appearance.sourceSize / (appearance.sourceSize + appearance.translationSize)
            let sourceHeight = floor(height * ratio)
            drawCaption(top, in: CGRect(x: 24, y: y, width: 720, height: sourceHeight),
                        font: appearance.font(size: appearance.sourceSize, translated: false),
                        color: UIColor(white: 0.82, alpha: 1), verticallyCentered: true)
            drawCaption(bottom, in: CGRect(x: 24, y: y + sourceHeight + 6, width: 720, height: height - sourceHeight),
                        font: appearance.font(size: appearance.translationSize, translated: true),
                        color: appearance.translationColor, verticallyCentered: true)

        case .chineseOnly(let text, let isPlaceholder):
            let y: CGFloat = hasBanner ? 50 : 14
            drawCaption(text, in: CGRect(x: 24, y: y, width: 720, height: 212 - y),
                        font: appearance.font(size: appearance.translationSize * 1.4, translated: true),
                        color: isPlaceholder ? UIColor(white: 0.72, alpha: 1) : appearance.translationColor,
                        verticallyCentered: true)

        case .probe(let status, let meter):
            drawCaption(status, in: CGRect(x: 24, y: 24, width: 720, height: 76),
                        font: .systemFont(ofSize: 32, weight: .medium),
                        color: UIColor(white: 0.85, alpha: 1),
                        verticallyCentered: false)
            drawDirect(meter, in: CGRect(x: 24, y: 112, width: 720, height: 84),
                       font: .systemFont(ofSize: 44, weight: .medium),
                       color: .white)
        }

        UIGraphicsPopContext()
        let callImage = callImageView != nil ? context.makeImage() : nil
        CVPixelBufferUnlockBaseAddress(buffer, [])
        if let callImage {
            callImageView?.image = UIImage(cgImage: callImage)
        }

        var description: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: buffer,
                                                            formatDescriptionOut: &description) == noErr,
              let description else { return }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: clock.currentTime(),
                                        decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: buffer, formatDescription: description,
                                                       sampleTiming: &timing, sampleBufferOut: &sample) == noErr,
              let sample else { return }
        // The pixel buffer is immutable once its ready sample has been created.
        let ready = CMReadySampleBuffer<CMSampleBuffer.DynamicContent>(unsafeBuffer: sample)
        switch receiver.enqueueImmediately(ready) {
        case .cancelledDueToFlushRequiredToResume, .cancelledDueToError:
            receiver.flush()
            _ = receiver.enqueueImmediately(ready)
        default: break
        }
    }

    private func drawBanner(_ banner: OverlayBanner, in rect: CGRect) {
        let fillColor: UIColor
        let textColor: UIColor
        switch banner.tone {
        case .warning:
            fillColor = UIColor(red: 0.72, green: 0.36, blue: 0.06, alpha: 0.96)
            textColor = .white
        case .info:
            fillColor = UIColor(red: 0.14, green: 0.21, blue: 0.30, alpha: 0.96)
            textColor = UIColor(white: 0.92, alpha: 1)
        }
        fillColor.setFill()
        UIBezierPath(roundedRect: rect, cornerRadius: 8).fill()

        let style = NSMutableParagraphStyle()
        style.lineBreakMode = .byTruncatingTail
        let font = UIFont.systemFont(ofSize: 20, weight: .semibold)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: textColor, .paragraphStyle: style]
        let textHeight = ceil(font.lineHeight)
        let textRect = CGRect(
            x: rect.minX + 12,
            y: rect.minY + max(0, (rect.height - textHeight) / 2),
            width: rect.width - 24,
            height: min(rect.height, textHeight)
        )
        (banner.text as NSString).draw(with: textRect,
                                       options: [.usesLineFragmentOrigin, .usesFontLeading, .truncatesLastVisibleLine],
                                       attributes: attrs,
                                       context: nil)
    }

    private func drawCaption(_ text: String, in rect: CGRect, font: UIFont, color: UIColor, verticallyCentered: Bool) {
        guard !text.isEmpty else { return }
        let style = NSMutableParagraphStyle()
        style.lineBreakMode = .byWordWrapping
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color, .paragraphStyle: style]
        let options: NSStringDrawingOptions = [.usesLineFragmentOrigin, .usesFontLeading]
        let measure: (String) -> CGFloat = { candidate in
            (candidate as NSString).boundingRect(with: CGSize(width: rect.width, height: 2000),
                                                 options: options, attributes: attrs, context: nil).height
        }
        let availableHeight = min(rect.height, ceil(font.lineHeight * CGFloat(appearance.maxLines)))
        let fitted = CaptionTextLayout.fitRecentPhrase(text) { candidate in
            measure(candidate) <= availableHeight + 0.5
        }
        var drawRect = rect
        if verticallyCentered {
            let measuredHeight = min(rect.height, ceil(measure(fitted)))
            drawRect = CGRect(
                x: rect.minX,
                y: rect.minY + max(0, (rect.height - measuredHeight) / 2),
                width: rect.width,
                height: measuredHeight
            )
        }
        (fitted as NSString).draw(with: drawRect, options: options, attributes: attrs, context: nil)
    }

    private func drawDirect(_ text: String, in rect: CGRect, font: UIFont, color: UIColor) {
        guard !text.isEmpty else { return }
        let style = NSMutableParagraphStyle()
        style.lineBreakMode = .byClipping
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color, .paragraphStyle: style]
        (text as NSString).draw(with: rect, options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attrs, context: nil)
    }

    private func handleDidStart(controllerID: ObjectIdentifier) {
        guard let current = controller, ObjectIdentifier(current) == controllerID else { return }
        switch phase {
        case .startingCall: phase = .showingCall
        case .startingStandard: phase = .showingStandard
        case .stopping, .idle:
            current.stopPictureInPicture()
            return
        default: return
        }
        pendingStart?.cancel()
        pendingStart = nil
        notice = ""
        logger.info("PiP did start (standard: \(self.phase == .showingStandard, privacy: .public))")
    }

    private func handleDidStop(controllerID: ObjectIdentifier) {
        guard let current = controller, ObjectIdentifier(current) == controllerID, active || stopping else { return }
        presentationEnded()
    }

    private func handleFailedToStart(controllerID: ObjectIdentifier, errorDomain: String, errorCode: Int, message: String) {
        guard let current = controller, ObjectIdentifier(current) == controllerID else { return }
        logger.info("PiP start failed (domain: \(errorDomain, privacy: .public), code: \(errorCode, privacy: .public))")
        switch phase {
        case .startingCall:
            startStandardAfterCallFailure(current)
        case .stopping:
            presentationEnded()
        case .startingStandard:
            presentationEnded()
            notice = "悬浮窗启动失败。\(message)"
        default: break
        }
    }

    nonisolated func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        let id = ObjectIdentifier(controller)
        Task { @MainActor in handleDidStart(controllerID: id) }
    }
    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        let id = ObjectIdentifier(controller)
        Task { @MainActor in handleDidStop(controllerID: id) }
    }
    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController,
                                                failedToStartPictureInPictureWithError error: Error) {
        let id = ObjectIdentifier(controller)
        let nsError = error as NSError
        let domain = nsError.domain
        let code = nsError.code
        let message = error.localizedDescription
        Task { @MainActor in
            handleFailedToStart(controllerID: id, errorDomain: domain, errorCode: code, message: message)
        }
    }
    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, setPlaying playing: Bool) {}
    nonisolated func pictureInPictureControllerTimeRangeForPlayback(_ controller: AVPictureInPictureController) -> CMTimeRange {
        CMTimeRange(start: .zero, duration: .positiveInfinity)
    }
    nonisolated func pictureInPictureControllerIsPlaybackPaused(_ controller: AVPictureInPictureController) -> Bool { false }
    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController,
                                                didTransitionToRenderSize newRenderSize: CMVideoDimensions) {}
    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, skipByInterval skipInterval: CMTime,
                                                completion: @escaping () -> Void) { completion() }
}
#elseif !MAC_CAPTIONS
import Combine
import Foundation

// Host-platform test stub exposing the same observable overlay API without UIKit/AVKit.
@MainActor
final class Overlay: ObservableObject {
    @Published private(set) var possible = true
    @Published private(set) var active = false
    @Published private(set) var callActive = false
    @Published private(set) var callStarting = false
    @Published var notice = ""
    private(set) var prepared = false
    private(set) var frames: [OverlayFrame] = []
    private var standardActive = false
    var lastFrame: OverlayFrame? { frames.last }

    func prepare() {
        prepared = true
        notice = ""
    }

    func start() {
        callActive = false
        callStarting = false
        if possible {
            standardActive = true
            active = true
            notice = ""
        } else {
            standardActive = false
            active = false
            notice = "系统尚未允许画中画，请保持应用在前台再试一次。"
        }
    }

    func startCall() {
        standardActive = false
        if possible {
            callStarting = false
            callActive = true
            active = true
            notice = ""
        } else {
            callStarting = true
            callActive = false
            active = false
            notice = "通话悬浮窗已待命，请切回视频通话自动开启；若其他应用（如 Discord）正占用画中画，请先关闭对方画中画。"
        }
    }

    func stopCall() {
        let hadCall = callStarting || callActive
        callStarting = false
        callActive = false
        active = standardActive
        if hadCall {
            notice = ""
        }
    }

    func stop() {
        standardActive = false
        callStarting = false
        callActive = false
        active = false
        prepared = false
    }

    func draw(_ frame: OverlayFrame) {
        frames.append(frame)
    }
}
#endif
