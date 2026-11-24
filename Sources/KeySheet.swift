import SwiftUI

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
