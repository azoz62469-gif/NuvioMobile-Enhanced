import AVFoundation
import ComposeApp
import SwiftUI
import Translation
import UniformTypeIdentifiers
import Vision

private struct StudioCue: Codable, Identifiable, Equatable {
    var start: Int64
    var end: Int64
    var text: String
    var id: Int64 { start }
}

@MainActor
private final class SubtitleStudioModel: ObservableObject {
    @Published var cues: [StudioCue] = []
    @Published var original: [StudioCue] = []
    @Published var status = ""
    @Published var busy = false
    @Published var exportURL: URL?
    @Published var search = ""
    @Published var format = "srt"
    @Published var ocrMinutes = 5
    @Published var ocrStartMinutes = 0
    @Published var ocrLanguage = "en-US"
    var work: Task<Void, Never>?

    func importFile(_ url: URL, video: Bool) {
        work?.cancel()
        work = Task {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            busy = true
            defer { busy = false }
            do {
                if video {
                    let start = ocrStartMinutes * 60
                    let seconds = ocrMinutes * 60
                    let language = ocrLanguage
                    let worker = Task.detached(priority: .utility) {
                        try await Self.readVideo(url, start: start, seconds: seconds, language: language)
                    }
                    let result = try await withTaskCancellationHandler {
                        try await worker.value
                    } onCancel: {
                        worker.cancel()
                    }
                    try Task.checkCancellation()
                    cues = result
                } else {
                    let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard size <= 8 * 1024 * 1024 else { throw StudioError.tooLarge }
                    let data = try Data(contentsOf: url)
                    guard let body = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .utf16) else {
                        throw StudioError.encoding
                    }
                    let json = SubtitleStudioCodec.shared.decode(body: body)
                    cues = try JSONDecoder().decode([StudioCue].self, from: Data(json.utf8))
                }
                original = cues
                status = cues.isEmpty ? "لم يتم العثور على نص قابل للقراءة" : "تم استيراد \(cues.count) سطر"
            } catch is CancellationError {
                status = "تم إلغاء العملية"
            } catch {
                status = "تعذر الاستيراد: \(error.localizedDescription)"
            }
        }
    }

    func export() {
        do {
            let json = String(decoding: try JSONEncoder().encode(cues), as: UTF8.self)
            let body = SubtitleStudioCodec.shared.encode(cuesJson: json, format: format)
            guard !body.isEmpty else { throw StudioError.noText }
            let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("SubtitleStudio", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let url = directory.appendingPathComponent("Subtitle-\(UUID().uuidString).\(format)")
            try body.write(to: url, atomically: true, encoding: .utf8)
            exportURL = url
            status = "تم حفظ الملف"
        } catch { status = "تعذر التصدير: \(error.localizedDescription)" }
    }

    func enhance() {
        do {
            let json = String(decoding: try JSONEncoder().encode(cues), as: UTF8.self)
            let srt = SubtitleStudioCodec.shared.encode(cuesJson: json, format: "srt")
            let enhanced = SubtitleStudioCodec.shared.enhance(body: srt)
            cues = try JSONDecoder().decode([StudioCue].self,
                from: Data(SubtitleStudioCodec.shared.decode(body: enhanced).utf8))
            status = "تم تطبيق تحسينات النص المحددة"
        } catch { status = "تعذر معالجة النص" }
    }

    nonisolated private static func readVideo(_ url: URL, start: Int, seconds: Int, language: String) async throws -> [StudioCue] {
        let asset = AVURLAsset(url: url)
        let duration = try await asset.load(.duration).seconds
        guard duration.isFinite, duration > Double(start) else { throw StudioError.noText }
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 1600, height: 900)
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        defer { generator.cancelAllCGImageGeneration() }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        let supported = try request.supportedRecognitionLanguages()
        guard supported.contains(language) else { throw StudioError.language }
        request.recognitionLanguages = [language]
        request.regionOfInterest = CGRect(x: 0, y: 0, width: 1, height: 0.35)
        var result: [StudioCue] = []
        let stop = min(Int(duration), start + seconds)
        for second in start..<stop {
            try Task.checkCancellation()
            let image = try generator.copyCGImage(at: CMTime(seconds: Double(second), preferredTimescale: 600), actualTime: nil)
            try VNImageRequestHandler(cgImage: image).perform([request])
            let text = (request.results ?? []).sorted {
                $0.boundingBox.midY > $1.boundingBox.midY
            }.compactMap { observation -> String? in
                guard let best = observation.topCandidates(1).first, best.confidence >= 0.5 else { return nil }
                return best.string
            }.joined(separator: "\n")
            if let last = result.last, last.text == text, last.end == Int64(second) * 1000 {
                result[result.count - 1].end = Int64(second + 1) * 1000
            } else if !text.isEmpty {
                result.append(StudioCue(start: Int64(second) * 1000, end: Int64(second + 1) * 1000, text: text))
            }
        }
        return result
    }
}

private enum StudioError: LocalizedError {
    case tooLarge, encoding, noText, language
    var errorDescription: String? {
        switch self {
        case .tooLarge: return "ملف الترجمة أكبر من ٨ ميغابايت"
        case .encoding: return "ترميز النص غير مدعوم؛ استخدم UTF-8"
        case .noText: return "لا يوجد نص صالح"
        case .language: return "لغة قراءة الصور غير متوفرة على هذا الجهاز"
        }
    }
}

private struct SubtitleStudioView: View {
    @StateObject private var model = SubtitleStudioModel()
    @Environment(\.dismiss) private var dismiss
    @State private var importing = false
    @State private var importVideo = false
    @State private var showTranslation = false
    @State private var showHistory = false

    var body: some View {
        NavigationStack {
            List {
                AppPrivacyLockSettings()
                Section("الملف") {
                    Button { importVideo = false; importing = true } label: { Label("استيراد ترجمة", systemImage: "doc.badge.plus") }
                    Picker("التنسيق", selection: $model.format) {
                        Text("SRT").tag("srt"); Text("VTT").tag("vtt"); Text("ASS").tag("ass")
                    }.pickerStyle(.segmented)
                    Button { model.export() } label: { Label("حفظ وتصدير", systemImage: "square.and.arrow.up") }
                        .disabled(model.cues.isEmpty || model.busy)
                    if let url = model.exportURL { ShareLink("مشاركة الملف المحفوظ", item: url) }
                }
                Section("معالجة الترجمة") {
                    Button("تطبيق تحسينات النص") { model.enhance() }.disabled(model.cues.isEmpty || model.busy)
                    Button("ترجمة النص على الجهاز") { showTranslation = true }.disabled(model.cues.isEmpty || model.busy)
                    Button("استعادة النص المستورد") { model.cues = model.original }.disabled(model.busy)
                    Button("سجل المزامنة") { showHistory = true }
                }
                Section("استخراج الترجمة المحروقة من فيديو محلي") {
                    Stepper("البداية: \(model.ocrStartMinutes) دقيقة", value: $model.ocrStartMinutes, in: 0...480)
                    Stepper("المدة: \(model.ocrMinutes) دقيقة", value: $model.ocrMinutes, in: 1...120)
                    Picker("لغة النص", selection: $model.ocrLanguage) {
                        Text("الإنجليزية").tag("en-US"); Text("العربية").tag("ar-SA")
                        Text("الفرنسية").tag("fr-FR"); Text("اليابانية").tag("ja-JP")
                    }
                    Button { importVideo = true; importing = true } label: { Label("اختيار فيديو وفحصه", systemImage: "text.viewfinder") }
                        .disabled(model.busy)
                }
                if model.busy {
                    ProgressView("جارٍ العمل")
                    Button("إلغاء", role: .cancel) { model.work?.cancel() }
                }
                if !model.status.isEmpty { Text(model.status) }
                Section("الأسطر") {
                    ForEach(model.cues.indices.filter {
                        model.search.isEmpty || model.cues[$0].text.localizedCaseInsensitiveContains(model.search)
                    }, id: \.self) { index in
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(model.cues[index].start / 1000) – \(model.cues[index].end / 1000) ث")
                                .font(.caption).foregroundStyle(.secondary)
                            TextField("النص", text: $model.cues[index].text, axis: .vertical).disabled(model.busy)
                        }
                    }
                }
            }
            .searchable(text: $model.search, prompt: "البحث في الترجمة")
            .navigationTitle("استوديو الترجمة")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("تم") { model.work?.cancel(); dismiss() } } }
            .fileImporter(isPresented: $importing, allowedContentTypes: importVideo ? [.movie] : [.text, .data]) { result in
                switch result {
                case .success(let url): model.importFile(url, video: importVideo)
                case .failure(let error): model.status = error.localizedDescription
                }
            }
            .sheet(isPresented: $showTranslation) {
                if #available(iOS 18.0, *) { StudioTranslationView(model: model) }
                else { Text("الترجمة المحلية تتطلب iPadOS 18 أو أحدث").padding() }
            }
            .sheet(isPresented: $showHistory) { StudioHistoryView() }
        }
        .environment(\.layoutDirection, .rightToLeft)
        .onDisappear { model.work?.cancel() }
    }
}

@available(iOS 18.0, *)
private struct StudioTranslationView: View {
    @ObservedObject var model: SubtitleStudioModel
    @Environment(\.dismiss) private var dismiss
    @State private var source = "en"
    @State private var target = "ar"
    @State private var configuration: TranslationSession.Configuration?
    @State private var busy = false
    @State private var status = ""
    @State private var cancelled = false
    private let languages = [("ar", "العربية"), ("en", "الإنجليزية"), ("fr", "الفرنسية"), ("es", "الإسبانية"), ("de", "الألمانية"), ("ja", "اليابانية"), ("ko", "الكورية"), ("zh", "الصينية")]

    var body: some View {
        NavigationStack {
            Form {
                Picker("من", selection: $source) { ForEach(languages, id: \.0) { Text($0.1).tag($0.0) } }.disabled(busy)
                Picker("إلى", selection: $target) { ForEach(languages, id: \.0) { Text($0.1).tag($0.0) } }.disabled(busy)
                Button("ترجمة") {
                    cancelled = false
                    busy = true
                    if configuration == nil { configuration = .init(source: Locale.Language(identifier: source), target: Locale.Language(identifier: target)) }
                    else { configuration = .init(source: Locale.Language(identifier: source), target: Locale.Language(identifier: target)); configuration?.invalidate() }
                }.disabled(busy || source == target)
                if busy { ProgressView() }
                Text(status)
            }
            .navigationTitle("ترجمة محلية")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("إغلاق") { cancelled = true; dismiss() } } }
            .translationTask(configuration) { session in
                let snapshot = model.cues
                var result = snapshot
                do {
                    try await session.prepareTranslation()
                    for start in stride(from: 0, to: snapshot.count, by: 30) {
                        try Task.checkCancellation()
                        if cancelled { throw CancellationError() }
                        let end = min(start + 30, snapshot.count)
                        let requests = (start..<end).map { TranslationSession.Request(sourceText: snapshot[$0].text, clientIdentifier: String($0)) }
                        let responses = try await session.translations(from: requests)
                        guard responses.count == requests.count else { throw StudioError.noText }
                        for response in responses {
                            guard let id = response.clientIdentifier, let index = Int(id), index >= start, index < end else { throw StudioError.noText }
                            result[index].text = response.targetText
                        }
                        status = "تمت ترجمة \(end) من \(snapshot.count)"
                    }
                    if !cancelled && model.cues == snapshot {
                        model.cues = result
                        model.enhance()
                        status = "اكتملت الترجمة"
                    }
                } catch is CancellationError { status = "تم الإلغاء؛ بقي النص السابق" }
                catch { status = "تعذرت الترجمة؛ بقي النص السابق. \(error.localizedDescription)" }
                busy = false
            }
        }.environment(\.layoutDirection, .rightToLeft)
        .interactiveDismissDisabled(busy)
        .onDisappear { cancelled = true }
    }
}

private struct StudioHistoryView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var entries: [[String: Any]] = []
    private var key: String { "subtitle_history_\(ProfileRepository.shared.activeProfileId)" }
    var body: some View {
        NavigationStack {
            List {
                if entries.isEmpty { Text("لا يوجد سجل محفوظ") }
                ForEach(entries.indices, id: \.self) { index in
                    VStack(alignment: .leading) {
                        Text(entries[index]["method"] as? String ?? "")
                        Text(entries[index]["date"] as? String ?? "").font(.caption)
                        Text("الإزاحة: \(entries[index]["offsetMs"] ?? 0) مللي ثانية")
                        Text("الوسائط: \(entries[index]["media"] ?? "")").font(.caption)
                    }
                }
                Button("مسح السجل", role: .destructive) { UserDefaults.standard.removeObject(forKey: key); entries = [] }
            }.navigationTitle("سجل المزامنة")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("تم") { dismiss() } } }
                .onAppear {
                    if let data = UserDefaults.standard.string(forKey: key)?.data(using: .utf8) {
                        entries = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] ?? []
                    }
                }
        }.environment(\.layoutDirection, .rightToLeft)
    }
}

enum SubtitleStudioRegistration {
    static func register() {
        SubtitleStudioLauncher.shared.register(presenter: NativeSubtitleStudioPresenter())
    }
}

private final class NativeSubtitleStudioPresenter: NSObject, SubtitleStudioPresenter {
    func show() {
        DispatchQueue.main.async {
            guard var host = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
                .flatMap({ $0.windows }).first(where: \.isKeyWindow)?.rootViewController else { return }
            while let next = host.presentedViewController { host = next }
            host.present(UIHostingController(rootView: SubtitleStudioView()), animated: true)
        }
    }
}
