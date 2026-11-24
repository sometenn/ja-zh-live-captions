#if os(iOS)
import AppIntents

struct StartCaptionsIntent: TargetContentProvidingIntent {
    static let title: LocalizedStringResource = "开启实时字幕"
    static let supportedModes: IntentModes = .foreground(.immediate)
    static var allowedExecutionTargets: IntentExecutionTargets { .main }
    static var authenticationPolicy: IntentAuthenticationPolicy { .requiresAuthentication }
}
#endif
