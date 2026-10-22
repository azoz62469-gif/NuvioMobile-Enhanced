import AVFoundation
import Foundation
import Libavcodec
import Libavformat
import Libavutil
import Speech

/**
 * Decodes a bounded prefix of the selected audio stream and asks Apple's speech recognizer for
 * timestamped words. The result is only evidence for subtitle alignment; playback and the
 * already-attached subtitle continue if permission, recognition, or decoding is unavailable.
 */
final class FFmpegAudioSpeechRecognizer {
    private let url: String
    private let headers: [String: String]
    private let locale: String
    private let onDeviceOnly: Bool
    private let maxAnalysisMs: Int64
    private let sampleStartMs: Int64
    private let abortFlag = AudioWordRecognitionAbortFlag()

    private static let sampleRate = 16_000
    private static let maxWallSeconds = 120.0
    private static let ioTimeoutMicroseconds = "15000000"

    init(url: String, headers: [String: String], locale: String, onDeviceOnly: Bool, analysisMinutes: Int, sampleStartMs: Int64) {
        self.url = url
        self.headers = headers
        self.locale = locale
        self.onDeviceOnly = onDeviceOnly
        self.maxAnalysisMs = Int64(max(1, min(12, analysisMinutes))) * 60 * 1_000
        self.sampleStartMs = max(0, sampleStartMs)
    }

    func cancel() {
        abortFlag.set()
    }

    func load() -> String {
        guard authorize() else {
            return payload(reason: "speech_permission_denied")
        }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: locale)) else {
            return payload(reason: "recognizer_unavailable")
        }
        let supportsOnDevice: Bool
        if #available(iOS 13.0, *) {
            supportsOnDevice = recognizer.supportsOnDeviceRecognition
        } else {
            supportsOnDevice = false
        }
        guard recognizer.isAvailable || supportsOnDevice else {
            return payload(reason: "recognizer_not_available")
        }
        if onDeviceOnly && !supportsOnDevice {
            return payload(reason: "on_device_language_unavailable")
        }

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = false
        request.taskHint = .dictation
        request.addsPunctuation = false
        if supportsOnDevice {
            request.requiresOnDeviceRecognition = true
        }

        let resultLock = NSLock()
        let finishedSignal = DispatchSemaphore(value: 0)
        var recognizedWords: [RecognizedWord] = []
        var recognitionError: String?
        var signaled = false

        func finish(error: String? = nil) {
            resultLock.lock()
            defer { resultLock.unlock() }
            if let error, recognitionError == nil {
                recognitionError = error
            }
            if !signaled {
                signaled = true
                finishedSignal.signal()
            }
        }

        let task = recognizer.recognitionTask(with: request) { result, error in
            resultLock.lock()
            if let result {
                recognizedWords = result.bestTranscription.segments.compactMap { segment in
                    let text = segment.substring.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !text.isEmpty else { return nil }
                    return RecognizedWord(
                        startMs: max(0, Int64((segment.timestamp * 1_000.0).rounded())),
                        durationMs: max(1, Int64((segment.duration * 1_000.0).rounded())),
                        text: text
                    )
                }
            }
            let wasFinal = result?.isFinal == true
            resultLock.unlock()
            if let error {
                finish(error: error.localizedDescription)
            } else if wasFinal {
                finish()
            }
        }
        defer { task.cancel() }

        guard let context = openInput() else {
            return payload(reason: "open_failed")
        }
        var contextPointer: UnsafeMutablePointer<AVFormatContext>? = context
        defer { avformat_close_input(&contextPointer) }

        var streamIndex = av_find_best_stream(
            context,
            AVMEDIA_TYPE_AUDIO,
            -1,
            -1,
            nil,
            0
        )
        if streamIndex < 0 {
            _ = avformat_find_stream_info(context, nil)
            streamIndex = av_find_best_stream(
                context,
                AVMEDIA_TYPE_AUDIO,
                -1,
                -1,
                nil,
                0
            )
        }
        let requestedLanguage = locale.split(separator: "-").first.map(String.init) ?? "en"
        let aliases = ["en": "eng", "ar": "ara", "fr": "fra", "es": "spa", "de": "deu",
                       "it": "ita", "ja": "jpn", "ko": "kor", "zh": "zho", "hi": "hin", "tr": "tur", "pt": "por"]
        for index in 0..<Int(context.pointee.nb_streams) {
            guard let candidate = context.pointee.streams[index],
                  candidate.pointee.codecpar?.pointee.codec_type == AVMEDIA_TYPE_AUDIO,
                  let entry = av_dict_get(candidate.pointee.metadata, "language", nil, 0),
                  let value = entry.pointee.value else { continue }
            let language = String(cString: value).lowercased()
            if language == requestedLanguage || language == aliases[requestedLanguage] {
                streamIndex = Int32(index)
                break
            }
        }
        guard
            streamIndex >= 0,
            let stream = context.pointee.streams[Int(streamIndex)],
            let parameters = stream.pointee.codecpar,
            let decoder = avcodec_find_decoder(parameters.pointee.codec_id),
            let decoderContext = avcodec_alloc_context3(decoder)
        else {
            request.endAudio()
            return payload(reason: "no_audio_stream")
        }
        var decoderPointer: UnsafeMutablePointer<AVCodecContext>? = decoderContext
        defer { avcodec_free_context(&decoderPointer) }
        guard avcodec_parameters_to_context(decoderContext, parameters) >= 0 else {
            request.endAudio()
            return payload(reason: "decoder_parameters_rejected")
        }
        guard avcodec_open2(decoderContext, decoder, nil) >= 0 else {
            request.endAudio()
            return payload(reason: "decoder_open_failed")
        }
        if sampleStartMs > 0 {
            let seekTime = av_rescale_q(sampleStartMs, AVRational(num: 1, den: 1_000), stream.pointee.time_base)
            guard av_seek_frame(context, streamIndex, seekTime, AVSEEK_FLAG_BACKWARD) >= 0 else {
                request.endAudio()
                return payload(reason: "sample_seek_failed")
            }
            avcodec_flush_buffers(decoderContext)
        }

        for index in 0..<Int(context.pointee.nb_streams) where index != Int(streamIndex) {
            context.pointee.streams[index]?.pointee.discard = AVDISCARD_ALL
        }
        guard let packet = av_packet_alloc(), let frame = av_frame_alloc() else {
            request.endAudio()
            return payload(reason: "allocation_failed")
        }
        var packetPointer: UnsafeMutablePointer<AVPacket>? = packet
        var framePointer: UnsafeMutablePointer<AVFrame>? = frame
        defer {
            av_packet_free(&packetPointer)
            av_frame_free(&framePointer)
        }

        guard let audioFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Double(Self.sampleRate),
            channels: 1,
            interleaved: false
        ) else {
            request.endAudio()
            return payload(reason: "audio_format_failed")
        }

        let timeBase = stream.pointee.time_base
        let started = DispatchTime.now().uptimeNanoseconds
        var fallbackMediaMs: Int64 = sampleStartMs
        var audioOrigins: [(bufferMs: Int64, mediaMs: Int64)] = []
        var packetsRead: Int64 = 0
        var appendedSamples: Int64 = 0
        var reachedLimit = false

        while !abortFlag.isSet {
            let elapsed = Double(
                DispatchTime.now().uptimeNanoseconds - started
            ) / 1_000_000_000.0
            if elapsed >= Self.maxWallSeconds || fallbackMediaMs >= sampleStartMs + maxAnalysisMs {
                reachedLimit = true
                break
            }
            resultLock.lock()
            let stopped = signaled
            resultLock.unlock()
            if stopped && recognitionError != nil {
                break
            }

            let readResult = av_read_frame(context, packet)
            if readResult < 0 {
                break
            }
            packetsRead += 1
            guard packet.pointee.stream_index == streamIndex else {
                av_packet_unref(packet)
                continue
            }

            let sent = avcodec_send_packet(decoderContext, packet)
            av_packet_unref(packet)
            if sent < 0 {
                continue
            }
            while avcodec_receive_frame(decoderContext, frame) >= 0 {
                autoreleasepool {
                    let sampleRate = max(1, Int(frame.pointee.sample_rate))
                    let samples = max(0, Int(frame.pointee.nb_samples))
                    let durationMs = Int64(
                        (Double(samples) * 1_000.0 / Double(sampleRate)).rounded()
                    )
                    let timestamp = frame.pointee.best_effort_timestamp
                    let startMs = timestamp == Int64.min
                        ? fallbackMediaMs
                        : av_rescale_q(
                            timestamp,
                            timeBase,
                            AVRational(num: 1, den: 1_000)
                        )
                    fallbackMediaMs = max(fallbackMediaMs, startMs) + max(1, durationMs)
                    if startMs < sampleStartMs { return }

                    if
                        let mono = AudioWordFramePcm.monoSamples(frame: frame),
                        !mono.isEmpty,
                        !abortFlag.isSet
                    {
                        let resampled = resampleTo16k(mono, sourceRate: sampleRate)
                        if append(
                            samples: resampled,
                            format: audioFormat,
                            to: request
                        ) {
                            audioOrigins.append((appendedSamples * 1_000 / Int64(Self.sampleRate), startMs))
                            appendedSamples += Int64(resampled.count)
                        }
                    }
                }
                av_frame_unref(frame)
                if fallbackMediaMs >= sampleStartMs + maxAnalysisMs {
                    reachedLimit = true
                    break
                }
            }
            if reachedLimit {
                break
            }
        }

        request.endAudio()
        let waitResult = finishedSignal.wait(
            timeout: .now() + (abortFlag.isSet ? 1.0 : 45.0)
        )
        if waitResult == .timedOut {
            task.cancel()
        }

        resultLock.lock()
        let words = recognizedWords
        let error = recognitionError
        resultLock.unlock()
        if abortFlag.isSet {
            return payload(
                reason: "cancelled",
                analyzedMs: fallbackMediaMs,
                packetsRead: packetsRead
            )
        }
        if words.isEmpty {
            return payload(
                reason: error ?? (waitResult == .timedOut ? "recognition_timeout" : "no_words"),
                analyzedMs: fallbackMediaMs,
                packetsRead: packetsRead
            )
        }
        return payload(
            words: words.map { word in
                var low = 0
                var high = audioOrigins.count
                while low < high {
                    let mid = (low + high) / 2
                    if audioOrigins[mid].bufferMs <= word.startMs { low = mid + 1 } else { high = mid }
                }
                let origin = audioOrigins.isEmpty ? (bufferMs: Int64(0), mediaMs: sampleStartMs) : audioOrigins[max(0, low - 1)]
                return RecognizedWord(startMs: origin.mediaMs + word.startMs - origin.bufferMs,
                                      durationMs: word.durationMs, text: word.text)
            },
            reason: reachedLimit ? "bounded_sample" : "ok",
            analyzedMs: fallbackMediaMs,
            packetsRead: packetsRead
        )
    }

    private func authorize() -> Bool {
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized:
            return true
        case .notDetermined:
            let signal = DispatchSemaphore(value: 0)
            var granted = false
            SFSpeechRecognizer.requestAuthorization { status in
                granted = status == .authorized
                signal.signal()
            }
            return signal.wait(timeout: .now() + 20.0) == .success && granted
        default:
            return false
        }
    }

    private func append(
        samples: [Float],
        format: AVAudioFormat,
        to request: SFSpeechAudioBufferRecognitionRequest
    ) -> Bool {
        guard !samples.isEmpty else { return false }
        guard let buffer = AVAudioPCMBuffer(
            pcmFormat: format,
            frameCapacity: AVAudioFrameCount(samples.count)
        ) else {
            return false
        }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        guard let channel = buffer.floatChannelData?[0] else { return false }
        samples.withUnsafeBufferPointer { source in
            guard let base = source.baseAddress else { return }
            channel.update(from: base, count: samples.count)
        }
        request.append(buffer)
        return true
    }

    private func resampleTo16k(_ samples: [Float], sourceRate: Int) -> [Float] {
        if sourceRate == Self.sampleRate {
            return samples
        }
        let ratio = Double(Self.sampleRate) / Double(max(1, sourceRate))
        let count = max(1, Int((Double(samples.count) * ratio).rounded()))
        var output = [Float](repeating: 0, count: count)
        let step = Double(sourceRate) / Double(Self.sampleRate)
        for index in 0..<count {
            let position = Double(index) * step
            let first = min(samples.count - 1, max(0, Int(position)))
            let second = min(samples.count - 1, first + 1)
            let fraction = Float(position - Double(first))
            output[index] = samples[first] * (1.0 - fraction) + samples[second] * fraction
        }
        return output
    }

    private func openInput() -> UnsafeMutablePointer<AVFormatContext>? {
        guard let context = avformat_alloc_context() else { return nil }
        context.pointee.interrupt_callback.callback = { opaque in
            guard let opaque else { return 0 }
            return Unmanaged<AudioWordRecognitionAbortFlag>
                .fromOpaque(opaque)
                .takeUnretainedValue()
                .isSet ? 1 : 0
        }
        context.pointee.interrupt_callback.opaque =
            Unmanaged.passUnretained(abortFlag).toOpaque()

        var options: OpaquePointer?
        defer { av_dict_free(&options) }
        _ = av_dict_set(&options, "rw_timeout", Self.ioTimeoutMicroseconds, 0)
        _ = av_dict_set(&options, "extension_picky", "0", 0)
        _ = av_dict_set(&options, "allowed_extensions", "ALL", 0)
        _ = av_dict_set(&options, "allowed_segment_extensions", "ALL", 0)
        var headerLines = ""
        for (name, value) in headers {
            if name.caseInsensitiveCompare("User-Agent") == .orderedSame {
                _ = av_dict_set(&options, "user_agent", value, 0)
            } else {
                headerLines += "\(name): \(value)\r\n"
            }
        }
        if !headerLines.isEmpty {
            _ = av_dict_set(&options, "headers", headerLines, 0)
        }

        let path: String
        if let parsed = URL(string: url), parsed.isFileURL {
            path = parsed.path
        } else {
            path = url
        }
        var contextPointer: UnsafeMutablePointer<AVFormatContext>? = context
        guard avformat_open_input(&contextPointer, path, nil, &options) >= 0 else {
            return nil
        }
        guard let opened = contextPointer else { return nil }
        _ = avformat_find_stream_info(opened, nil)
        return opened
    }

    private func payload(
        words: [RecognizedWord] = [],
        reason: String,
        analyzedMs: Int64 = 0,
        packetsRead: Int64 = 0
    ) -> String {
        let segments = groupSegments(words)
        let objects: [[String: Any]] = segments.map { word in
            [
                "startMs": word.startMs,
                "endMs": word.startMs + word.durationMs,
                "text": word.text,
                "segment": word.segment,
            ]
        }
        let object: [String: Any] = [
            "words": objects,
            "reason": reason,
            "analyzedMs": analyzedMs,
            "wordCount": objects.count,
            "packetsRead": packetsRead,
        ]
        guard
            let data = try? JSONSerialization.data(withJSONObject: object),
            let json = String(data: data, encoding: .utf8)
        else {
            return "{\"words\":[],\"reason\":\"encode_failed\"}"
        }
        return json
    }

    private func groupSegments(_ words: [RecognizedWord]) -> [RecognizedWord] {
        var output: [RecognizedWord] = []
        output.reserveCapacity(words.count)
        var group = 0
        var previousEndMs: Int64 = -10_000
        for word in words.sorted(by: { $0.startMs < $1.startMs }) {
            if word.startMs - previousEndMs > 1_200 {
                group += 1
            }
            output.append(
                RecognizedWord(
                    startMs: word.startMs,
                    durationMs: word.durationMs,
                    text: word.text,
                    segment: group
                )
            )
            previousEndMs = max(previousEndMs, word.startMs + word.durationMs)
        }
        return output
    }

    private struct RecognizedWord {
        let startMs: Int64
        let durationMs: Int64
        let text: String
        var segment: Int = 0
    }
}

private struct AudioWordFramePcm {
    static func monoSamples(frame: UnsafeMutablePointer<AVFrame>) -> [Float]? {
        let samples = Int(frame.pointee.nb_samples)
        let channels = max(1, Int(frame.pointee.ch_layout.nb_channels))
        guard samples > 0, let planes = frame.pointee.extended_data else { return nil }
        let format = frame.pointee.format
        var output = [Float](repeating: 0, count: samples)

        switch format {
        case AV_SAMPLE_FMT_FLTP.rawValue:
            for channel in 0..<channels where planes[channel] != nil {
                planes[channel]!.withMemoryRebound(to: Float.self, capacity: samples) { values in
                    for index in 0..<samples { output[index] += values[index] }
                }
            }
            for index in output.indices { output[index] /= Float(channels) }
        case AV_SAMPLE_FMT_FLT.rawValue:
            guard let plane = planes[0] else { return nil }
            plane.withMemoryRebound(to: Float.self, capacity: samples * channels) { values in
                for index in 0..<samples {
                    var sum: Float = 0
                    for channel in 0..<channels {
                        sum += values[index * channels + channel]
                    }
                    output[index] = sum / Float(channels)
                }
            }
        case AV_SAMPLE_FMT_S16P.rawValue:
            for channel in 0..<channels where planes[channel] != nil {
                planes[channel]!.withMemoryRebound(to: Int16.self, capacity: samples) { values in
                    for index in 0..<samples {
                        output[index] += Float(values[index]) / 32_768.0
                    }
                }
            }
            for index in output.indices { output[index] /= Float(channels) }
        case AV_SAMPLE_FMT_S16.rawValue:
            guard let plane = planes[0] else { return nil }
            plane.withMemoryRebound(to: Int16.self, capacity: samples * channels) { values in
                for index in 0..<samples {
                    var sum: Float = 0
                    for channel in 0..<channels {
                        sum += Float(values[index * channels + channel]) / 32_768.0
                    }
                    output[index] = sum / Float(channels)
                }
            }
        case AV_SAMPLE_FMT_S32P.rawValue:
            for channel in 0..<channels where planes[channel] != nil {
                planes[channel]!.withMemoryRebound(to: Int32.self, capacity: samples) { values in
                    for index in 0..<samples {
                        output[index] += Float(values[index]) / 2_147_483_648.0
                    }
                }
            }
            for index in output.indices { output[index] /= Float(channels) }
        case AV_SAMPLE_FMT_S32.rawValue:
            guard let plane = planes[0] else { return nil }
            plane.withMemoryRebound(to: Int32.self, capacity: samples * channels) { values in
                for index in 0..<samples {
                    var sum: Float = 0
                    for channel in 0..<channels {
                        sum += Float(values[index * channels + channel]) / 2_147_483_648.0
                    }
                    output[index] = sum / Float(channels)
                }
            }
        case AV_SAMPLE_FMT_DBLP.rawValue:
            for channel in 0..<channels where planes[channel] != nil {
                planes[channel]!.withMemoryRebound(to: Double.self, capacity: samples) { values in
                    for index in 0..<samples { output[index] += Float(values[index]) }
                }
            }
            for index in output.indices { output[index] /= Float(channels) }
        case AV_SAMPLE_FMT_DBL.rawValue:
            guard let plane = planes[0] else { return nil }
            plane.withMemoryRebound(to: Double.self, capacity: samples * channels) { values in
                for index in 0..<samples {
                    var sum: Float = 0
                    for channel in 0..<channels {
                        sum += Float(values[index * channels + channel])
                    }
                    output[index] = sum / Float(channels)
                }
            }
        case AV_SAMPLE_FMT_U8P.rawValue:
            for channel in 0..<channels where planes[channel] != nil {
                let values = planes[channel]!
                for index in 0..<samples {
                    output[index] += (Float(values[index]) - 128.0) / 128.0
                }
            }
            for index in output.indices { output[index] /= Float(channels) }
        case AV_SAMPLE_FMT_U8.rawValue:
            guard let plane = planes[0] else { return nil }
            for index in 0..<samples {
                var sum: Float = 0
                for channel in 0..<channels {
                    sum += (Float(plane[index * channels + channel]) - 128.0) / 128.0
                }
                output[index] = sum / Float(channels)
            }
        default:
            return nil
        }
        return output
    }
}

private final class AudioWordRecognitionAbortFlag {
    private let lock = NSLock()
    private var value = false

    var isSet: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func set() {
        lock.lock()
        value = true
        lock.unlock()
    }
}
