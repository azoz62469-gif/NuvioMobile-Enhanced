import ComposeApp
import CoreGraphics
import Foundation
import Libavcodec
import Libavformat
import Libavutil
import Libswscale
import UIKit

// MARK: - Seek-bar preview frames (FFmpeg)

final class FFmpegSeekPreviewGenerator: NSObject, NuvioSeekPreviewGenerator {
    private let url: String
    private let headers: [String: String]
    private let abortFlag = SeekPreviewAbortFlag()
    private let lock = NSLock()

    private var formatContext: UnsafeMutablePointer<AVFormatContext>?
    private var codecContext: UnsafeMutablePointer<AVCodecContext>?
    private var videoStreamIndex: Int32 = -1
    private var didAttemptOpen = false
    private var openErrorMessage: String?
    private var lastErrorMessage: String?

    private static let maxVideoPacketsPerFrame = 120
    private static let packetsBeforeIgnoringKeyFlag = 8
    private static let ioTimeoutMicroseconds = "10000000"
    private static let swsBilinear: Int32 = 2
    private static let swsColorspaceItu709: Int32 = 1
    private static let swsColorspaceItu601: Int32 = 5
    private static let noPtsValue = Int64.min

    init(url: String, headers: [String: String]) {
        self.url = url
        self.headers = headers
        super.init()
    }

    deinit {
        releaseResources()
    }

    func frameJpeg(positionMs: Int64, maxWidth: Int32) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        lastErrorMessage = nil
        guard !abortFlag.isSet else {
            lastErrorMessage = "cancelled"
            return nil
        }
        guard openIfNeeded(), let formatContext, let codecContext else {
            lastErrorMessage = openErrorMessage ?? "stream could not be opened"
            return nil
        }
        guard let frame = decodeKeyframe(
            formatContext: formatContext,
            codecContext: codecContext,
            positionMs: max(0, positionMs)
        ) else { return nil }
        var framePointer: UnsafeMutablePointer<AVFrame>? = frame
        defer { av_frame_free(&framePointer) }
        guard let data = jpegData(from: frame, maxWidth: max(16, Int(maxWidth))) else {
            lastErrorMessage = lastErrorMessage ?? "frame conversion failed"
            return nil
        }
        return data
    }

    func lastError() -> String? {
        lock.lock()
        defer { lock.unlock() }
        return lastErrorMessage
    }

    func cancel() {
        abortFlag.set()
    }

    func close() {
        abortFlag.set()
        lock.lock()
        defer { lock.unlock() }
        releaseResources()
    }

    // MARK: Opening

    private func openIfNeeded() -> Bool {
        if formatContext != nil, codecContext != nil { return true }
        if didAttemptOpen { return false }
        didAttemptOpen = true

        var openResult: Int32 = 0
        var openedContext = openInput(format: nil, result: &openResult)
        if openedContext == nil, !abortFlag.isSet, let hls = av_find_input_format("hls") {
            openedContext = openInput(format: hls, result: &openResult)
        }
        guard let context = openedContext else {
            openErrorMessage = "open failed: \(Self.describe(openResult))"
            return false
        }
        var contextPointer: UnsafeMutablePointer<AVFormatContext>? = context

        var streamIndex = av_find_best_stream(context, AVMEDIA_TYPE_VIDEO, -1, -1, nil, 0)
        if streamIndex < 0 || !hasDecodableParameters(context, streamIndex) {
            _ = avformat_find_stream_info(context, nil)
            streamIndex = av_find_best_stream(context, AVMEDIA_TYPE_VIDEO, -1, -1, nil, 0)
        }
        guard streamIndex >= 0, let stream = context.pointee.streams[Int(streamIndex)],
              let parameters = stream.pointee.codecpar,
              let decoder = avcodec_find_decoder(parameters.pointee.codec_id),
              let decoderContext = avcodec_alloc_context3(decoder) else {
            openErrorMessage = "no decodable video stream"
            contextPointer = context
            avformat_close_input(&contextPointer)
            return false
        }

        for index in 0..<Int(context.pointee.nb_streams) where index != Int(streamIndex) {
            context.pointee.streams[index]?.pointee.discard = AVDISCARD_ALL
        }

        var decoderPointer: UnsafeMutablePointer<AVCodecContext>? = decoderContext
        guard avcodec_parameters_to_context(decoderContext, parameters) >= 0 else {
            openErrorMessage = "decoder parameters rejected"
            avcodec_free_context(&decoderPointer)
            contextPointer = context
            avformat_close_input(&contextPointer)
            return false
        }
        decoderContext.pointee.thread_count = 2
        decoderContext.pointee.thread_type = FF_THREAD_SLICE
        let codecOpenResult = avcodec_open2(decoderContext, decoder, nil)
        guard codecOpenResult >= 0 else {
            openErrorMessage = "decoder could not be opened: \(Self.describe(codecOpenResult))"
            avcodec_free_context(&decoderPointer)
            contextPointer = context
            avformat_close_input(&contextPointer)
            return false
        }

        formatContext = context
        codecContext = decoderContext
        videoStreamIndex = streamIndex
        return true
    }

    private func openInput(
        format: UnsafePointer<AVInputFormat>?,
        result: inout Int32
    ) -> UnsafeMutablePointer<AVFormatContext>? {
        guard let context = avformat_alloc_context() else {
            result = -12
            return nil
        }
        context.pointee.interrupt_callback.callback = { opaque in
            guard let opaque else { return 0 }
            return Unmanaged<SeekPreviewAbortFlag>.fromOpaque(opaque).takeUnretainedValue().isSet ? 1 : 0
        }
        context.pointee.interrupt_callback.opaque = Unmanaged.passUnretained(abortFlag).toOpaque()

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

        var contextPointer: UnsafeMutablePointer<AVFormatContext>? = context
        result = avformat_open_input(&contextPointer, inputPath(), format, &options)
        return result >= 0 ? contextPointer : nil
    }

    private func inputPath() -> String {
        if let parsed = URL(string: url), parsed.isFileURL {
            return parsed.path
        }
        return url
    }

    private func hasDecodableParameters(
        _ context: UnsafeMutablePointer<AVFormatContext>,
        _ streamIndex: Int32
    ) -> Bool {
        guard let parameters = context.pointee.streams[Int(streamIndex)]?.pointee.codecpar else { return false }
        return parameters.pointee.codec_id != AV_CODEC_ID_NONE && parameters.pointee.width > 0
    }

    // MARK: Decoding

    private func decodeKeyframe(
        formatContext: UnsafeMutablePointer<AVFormatContext>,
        codecContext: UnsafeMutablePointer<AVCodecContext>,
        positionMs: Int64
    ) -> UnsafeMutablePointer<AVFrame>? {
        guard let stream = formatContext.pointee.streams[Int(videoStreamIndex)] else { return nil }

        let timeBase = stream.pointee.time_base
        var target = av_rescale_q(positionMs, AVRational(num: 1, den: 1000), timeBase)
        if stream.pointee.start_time != Self.noPtsValue {
            target += stream.pointee.start_time
        }
        if av_seek_frame(formatContext, videoStreamIndex, target, AVSEEK_FLAG_BACKWARD) < 0 {
            var globalTarget = positionMs * 1000
            if formatContext.pointee.start_time != Self.noPtsValue {
                globalTarget += formatContext.pointee.start_time
            }
            let globalResult = av_seek_frame(formatContext, -1, globalTarget, AVSEEK_FLAG_BACKWARD)
            guard globalResult >= 0 else {
                lastErrorMessage = "seek failed: \(Self.describe(globalResult))"
                return nil
            }
        }
        avcodec_flush_buffers(codecContext)

        guard let packet = av_packet_alloc() else { return nil }
        var packetPointer: UnsafeMutablePointer<AVPacket>? = packet
        defer { av_packet_free(&packetPointer) }
        guard let frame = av_frame_alloc() else { return nil }
        var framePointer: UnsafeMutablePointer<AVFrame>? = frame

        var videoPackets = 0
        while videoPackets < Self.maxVideoPacketsPerFrame, !abortFlag.isSet {
            let readResult = av_read_frame(formatContext, packet)
            if readResult < 0 {
                lastErrorMessage = "read failed: \(Self.describe(readResult))"
                break
            }
            defer { av_packet_unref(packet) }
            guard packet.pointee.stream_index == videoStreamIndex else { continue }
            videoPackets += 1
            let isKeyframe = packet.pointee.flags & AV_PKT_FLAG_KEY != 0
            if !isKeyframe, videoPackets < Self.packetsBeforeIgnoringKeyFlag { continue }

            _ = avcodec_send_packet(codecContext, packet)
            if avcodec_receive_frame(codecContext, frame) >= 0 {
                return frame
            }
            _ = avcodec_send_packet(codecContext, nil)
            let drainResult = avcodec_receive_frame(codecContext, frame)
            if drainResult >= 0 {
                return frame
            }
            lastErrorMessage = "decoder produced no frame: \(Self.describe(drainResult))"
            break
        }
        if abortFlag.isSet {
            lastErrorMessage = "cancelled"
        } else if videoPackets >= Self.maxVideoPacketsPerFrame {
            lastErrorMessage = "no keyframe within \(videoPackets) packets"
        }
        av_frame_free(&framePointer)
        return nil
    }

    private static func describe(_ code: Int32) -> String {
        var buffer = [CChar](repeating: 0, count: 128)
        _ = av_strerror(code, &buffer, buffer.count)
        return "\(String(cString: buffer)) (\(code))"
    }

    // MARK: Conversion

    private func jpegData(from frame: UnsafeMutablePointer<AVFrame>, maxWidth: Int) -> Data? {
        let sourceWidth = frame.pointee.width
        let sourceHeight = frame.pointee.height
        guard sourceWidth > 0, sourceHeight > 0 else { return nil }

        var displayWidth = Double(sourceWidth)
        let sampleAspect = frame.pointee.sample_aspect_ratio
        if sampleAspect.num > 0, sampleAspect.den > 0 {
            displayWidth *= Double(sampleAspect.num) / Double(sampleAspect.den)
        }
        let scale = min(1.0, Double(maxWidth) / displayWidth)
        let targetWidth = max(2, Int32((displayWidth * scale).rounded()) & ~1)
        let targetHeight = max(2, Int32((Double(sourceHeight) * scale).rounded()) & ~1)

        guard let scaler = sws_getContext(
            sourceWidth,
            sourceHeight,
            AVPixelFormat(rawValue: frame.pointee.format),
            targetWidth,
            targetHeight,
            AV_PIX_FMT_BGRA,
            Self.swsBilinear,
            nil,
            nil,
            nil
        ) else {
            lastErrorMessage = "unsupported pixel format \(frame.pointee.format)"
            return nil
        }
        defer { sws_freeContext(scaler) }

        let colorspace = sourceHeight >= 720 ? Self.swsColorspaceItu709 : Self.swsColorspaceItu601
        if let coefficients = sws_getCoefficients(colorspace) {
            let fullRange: Int32 = frame.pointee.color_range == AVCOL_RANGE_JPEG ? 1 : 0
            _ = sws_setColorspaceDetails(scaler, coefficients, fullRange, coefficients, 1, 0, 1 << 16, 1 << 16)
        }

        guard let output = av_frame_alloc() else { return nil }
        var outputPointer: UnsafeMutablePointer<AVFrame>? = output
        defer { av_frame_free(&outputPointer) }
        output.pointee.width = targetWidth
        output.pointee.height = targetHeight
        output.pointee.format = AV_PIX_FMT_BGRA.rawValue
        guard av_frame_get_buffer(output, 0) >= 0 else { return nil }
        let scaleResult = sws_scale_frame(scaler, output, frame)
        guard scaleResult >= 0, let pixels = output.pointee.data.0 else {
            lastErrorMessage = "scaling failed: \(Self.describe(scaleResult))"
            return nil
        }

        let bitmapInfo = CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue
        guard let bitmapContext = CGContext(
            data: pixels,
            width: Int(targetWidth),
            height: Int(targetHeight),
            bitsPerComponent: 8,
            bytesPerRow: Int(output.pointee.linesize.0),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: bitmapInfo
        ), let image = bitmapContext.makeImage() else { return nil }
        return UIImage(cgImage: image).jpegData(compressionQuality: 0.8)
    }

    // MARK: Teardown

    private func releaseResources() {
        if codecContext != nil {
            avcodec_free_context(&codecContext)
        }
        if formatContext != nil {
            avformat_close_input(&formatContext)
        }
        videoStreamIndex = -1
    }
}

private final class SeekPreviewAbortFlag {
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

final class FFmpegSeekPreviewGeneratorCreator: NSObject, NuvioSeekPreviewGeneratorCreator {
    func createGenerator(url: String, headers: [String: String]) -> (any NuvioSeekPreviewGenerator)? {
        FFmpegSeekPreviewGenerator(url: url, headers: headers)
    }
}

// MARK: - Embedded subtitle timing references

/**
 * Demuxes subtitle packet timestamps without decoding pictures or text. Auto Sync only compares
 * cue timing, so this stays independent from mpv's active subtitle selection and rendering.
 */
final class FFmpegEmbeddedSubtitleReferenceLoader {
    private let url: String
    private let headers: [String: String]
    private let abortFlag = EmbeddedSubtitleAbortFlag()
    private var openFailureReason: String?

    private static let ioTimeoutMicroseconds = "5000000"
    private static let loadTimeoutSeconds = 5.0
    private static let maxCuesPerTrack = 4_000
    private static let eofErrorCode: Int32 = -541_478_725

    init(url: String, headers: [String: String]) {
        self.url = url
        self.headers = headers
    }

    func cancel() {
        abortFlag.set(reason: "cancelled")
    }

    func load() -> String {
        let flag = abortFlag
        let timeout = DispatchWorkItem { flag.set(reason: "timeout") }
        DispatchQueue.global(qos: .utility).asyncAfter(
            deadline: .now() + Self.loadTimeoutSeconds,
            execute: timeout
        )
        defer { timeout.cancel() }

        guard let context = openInput() else {
            return diagnosticPayload(reason: openFailureReason ?? "open_failed")
        }
        var contextPointer: UnsafeMutablePointer<AVFormatContext>? = context
        defer { avformat_close_input(&contextPointer) }

        var subtitleStreamCount = 0
        var subtitleStreams: [(index: Int32, key: String)] = []
        for index in 0..<Int32(context.pointee.nb_streams) {
            guard let stream = context.pointee.streams[Int(index)] else { continue }
            guard let parameters = stream.pointee.codecpar,
                  parameters.pointee.codec_type == AVMEDIA_TYPE_SUBTITLE else {
                stream.pointee.discard = AVDISCARD_ALL
                continue
            }
            subtitleStreamCount += 1
            let name = codecName(parameters.pointee.codec_id).lowercased()
            let isBitmap = ["pgs", "dvd", "dvb", "xsub", "teletext"].contains {
                name.contains($0)
            }
            // Forced tracks only carry signs and songs, so they cannot validate a full
            // dialogue subtitle against the video timeline.
            let isForced = (stream.pointee.disposition & 64) != 0
            if isBitmap || isForced {
                stream.pointee.discard = AVDISCARD_ALL
                continue
            }
            subtitleStreams.append((index: index, key: "embedded:\(index)"))
        }
        guard !subtitleStreams.isEmpty else {
            return diagnosticPayload(
                reason: subtitleStreamCount == 0
                    ? "no_subtitle_streams"
                    : "no_dialogue_text_streams",
                subtitleStreamCount: subtitleStreamCount,
                candidateStreamCount: subtitleStreams.count
            )
        }
        guard let packet = av_packet_alloc() else {
            return diagnosticPayload(
                reason: "packet_alloc_failed",
                subtitleStreamCount: subtitleStreamCount,
                candidateStreamCount: subtitleStreams.count
            )
        }
        var packetPointer: UnsafeMutablePointer<AVPacket>? = packet
        defer { av_packet_free(&packetPointer) }

        var timings: [Int32: [(Int64, Int64)]] = [:]
        var streamLookup = Dictionary(
            uniqueKeysWithValues: subtitleStreams.map { ($0.index, $0.key) }
        )
        var sampledStreams = Set<Int32>()
        var missingDurationSampling = false
        var cueLimitSampling = false
        var reachedEOF = false
        var packetsRead: Int64 = 0
        while !abortFlag.isSet {
            let readResult = av_read_frame(context, packet)
            if readResult < 0 {
                reachedEOF = readResult == Self.eofErrorCode
                break
            }
            packetsRead += 1

            let streamIndex = packet.pointee.stream_index
            if streamLookup[streamIndex] != nil,
               let stream = context.pointee.streams[Int(streamIndex)] {
                let timestamp = packet.pointee.pts != Int64.min
                    ? packet.pointee.pts
                    : packet.pointee.dts
                if timestamp != Int64.min {
                    let timeBase = stream.pointee.time_base
                    let milliseconds = AVRational(num: 1, den: 1_000)
                    let startMs = av_rescale_q(timestamp, timeBase, milliseconds)
                    let hasDuration = packet.pointee.duration > 0
                    let durationMs = hasDuration
                        ? av_rescale_q(packet.pointee.duration, timeBase, milliseconds)
                        : 1
                    if startMs >= 0 {
                        if !hasDuration {
                            sampledStreams.insert(streamIndex)
                            missingDurationSampling = true
                        }
                        let count = timings[streamIndex]?.count ?? 0
                        if count >= Self.maxCuesPerTrack {
                            sampledStreams.insert(streamIndex)
                            cueLimitSampling = true
                        } else {
                            timings[streamIndex, default: []].append(
                                (startMs, max(startMs + 1, startMs + durationMs))
                            )
                        }
                    }
                }
            }
            av_packet_unref(packet)
            if sampledStreams.count == subtitleStreams.count { break }
        }

        let tracks: [[String: Any]] = subtitleStreams.compactMap { stream in
            let cues = Self.normalized(timings[stream.index] ?? [])
            guard !cues.isEmpty else { return nil }
            let sampled = !reachedEOF || abortFlag.isSet || sampledStreams.contains(stream.index)
            return [
                "key": stream.key,
                "label": "Embedded \(stream.index)",
                "sampled": sampled,
                "cues": cues.map { ["startMs": $0.0, "endMs": $0.1] }
            ]
        }
        streamLookup.removeAll()
        let reason: String
        if abortFlag.isSet {
            reason = abortFlag.reason ?? "aborted"
        } else if cueLimitSampling {
            reason = "sampled_cue_limit"
        } else if missingDurationSampling {
            reason = "sampled_missing_duration"
        } else if !reachedEOF {
            reason = "read_error"
        } else if tracks.isEmpty {
            reason = "no_timed_subtitle_packets"
        } else if tracks.contains(where: { ($0["sampled"] as? Bool) == true }) {
            reason = "sampled"
        } else {
            reason = "ok"
        }
        return encode(
            tracks: tracks,
            reason: reason,
            subtitleStreamCount: subtitleStreamCount,
            candidateStreamCount: subtitleStreams.count,
            packetsRead: packetsRead,
            reachedEOF: reachedEOF
        )
    }

    private func openInput() -> UnsafeMutablePointer<AVFormatContext>? {
        guard let context = avformat_alloc_context() else {
            openFailureReason = "context_alloc_failed"
            return nil
        }
        context.pointee.interrupt_callback.callback = { opaque in
            guard let opaque else { return 0 }
            return Unmanaged<EmbeddedSubtitleAbortFlag>
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

        var contextPointer: UnsafeMutablePointer<AVFormatContext>? = context
        let result = avformat_open_input(&contextPointer, inputPath(), nil, &options)
        guard result >= 0, let opened = contextPointer else {
            openFailureReason = "open_failed"
            return nil
        }
        _ = avformat_find_stream_info(opened, nil)
        return opened
    }

    private func inputPath() -> String {
        if let parsed = URL(string: url), parsed.isFileURL {
            return parsed.path
        }
        return url
    }

    private func codecName(_ codecId: AVCodecID) -> String {
        guard let descriptor = avcodec_descriptor_get(codecId),
              let name = descriptor.pointee.name else {
            return ""
        }
        return String(cString: name)
    }

    private static func normalized(_ cues: [(Int64, Int64)]) -> [(Int64, Int64)] {
        let sorted = cues.sorted {
            $0.0 == $1.0 ? $0.1 < $1.1 : $0.0 < $1.0
        }
        var result: [(Int64, Int64)] = []
        for cue in sorted {
            if result.last?.0 == cue.0 && result.last?.1 == cue.1 { continue }
            if result.count >= maxCuesPerTrack { break }
            result.append(cue)
        }
        return result
    }

    private func encode(
        tracks: [[String: Any]],
        reason: String,
        subtitleStreamCount: Int,
        candidateStreamCount: Int,
        packetsRead: Int64,
        reachedEOF: Bool
    ) -> String {
        guard
            let data = try? JSONSerialization.data(withJSONObject: [
                "tracks": tracks,
                "reason": reason,
                "subtitleStreams": subtitleStreamCount,
                "candidateStreams": candidateStreamCount,
                "packetsRead": packetsRead,
                "reachedEof": reachedEOF,
            ]),
            let json = String(data: data, encoding: .utf8)
        else {
            return "{\"tracks\":[],\"reason\":\"encode_failed\",\"subtitleStreams\":\(subtitleStreamCount),\"candidateStreams\":\(candidateStreamCount),\"packetsRead\":\(packetsRead),\"reachedEof\":\(reachedEOF)}"
        }
        return json
    }

    private func diagnosticPayload(
        reason: String,
        subtitleStreamCount: Int = 0,
        candidateStreamCount: Int = 0,
        packetsRead: Int64 = 0,
        reachedEOF: Bool = false
    ) -> String {
        encode(
            tracks: [],
            reason: reason,
            subtitleStreamCount: subtitleStreamCount,
            candidateStreamCount: candidateStreamCount,
            packetsRead: packetsRead,
            reachedEOF: reachedEOF
        )
    }
}

private final class EmbeddedSubtitleAbortFlag {
    private let lock = NSLock()
    private var value = false
    private var abortReason: String?

    var isSet: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    var reason: String? {
        lock.lock()
        defer { lock.unlock() }
        return abortReason
    }

    func set(reason: String) {
        lock.lock()
        if !value {
            value = true
            abortReason = reason
        }
        lock.unlock()
    }
}
