import SwiftUI
import UniformTypeIdentifiers

private struct TranscriptDocument: FileDocument {
    static let readableContentTypes: [UTType] = [.plainText]
    let text: String

    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents,
              let text = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileReadInapplicableStringEncoding)
        }
        self.text = text
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

struct SaveTranscriptButton: View {
    @ObservedObject var session: Session
    @State private var document: TranscriptDocument?
    @State private var filename = ""
    @State private var exporting = false
    @State private var errorMessage: String?

    var body: some View {
        Button("保存记录", systemImage: "square.and.arrow.down") {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.dateFormat = "yyyyMMdd-HHmmss"
            filename = "字幕记录-" + formatter.string(from: Date()) + ".txt"
            document = TranscriptDocument(text: session.transcriptText())
            exporting = true
        }
        .disabled(!session.hasTranscript)
        .fileExporter(isPresented: $exporting, document: document, contentTypes: [.plainText],
                      defaultFilename: filename) { result in
            document = nil
            if case .failure(let error) = result { errorMessage = error.localizedDescription }
        } onCancellation: {
            document = nil
        }
        .alert("保存记录失败", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("好") { errorMessage = nil }
        } message: { Text(errorMessage ?? "") }
    }
}
