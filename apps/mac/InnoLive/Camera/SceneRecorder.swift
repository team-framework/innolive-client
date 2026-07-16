//
//  SceneRecorder.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import AppKit
import AVFoundation
import CoreGraphics
import CoreVideo

final class SceneRecorder {
    private let queue = DispatchQueue(label: "com.framework.InnoLive.scene-recorder", qos: .userInitiated)

    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var pixelBufferAdaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var configuration: SceneRecordingConfiguration?
    private var latestDelayedFrame: CGImage?
    private var latestScreenFrames: [StudioSource.ID: CGImage] = [:]
    private var isFinishing = false

    func start(outputURL: URL, configuration: SceneRecordingConfiguration) throws {
        let dimensions = configuration.settings.resolution.dimensions
        let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mov)

        let videoInput = AVAssetWriterInput(
            mediaType: .video,
            outputSettings: [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: dimensions.width,
                AVVideoHeightKey: dimensions.height,
                AVVideoCompressionPropertiesKey: [
                    AVVideoAverageBitRateKey: configuration.settings.videoBitrateKbps * 1_000,
                    AVVideoExpectedSourceFrameRateKey: configuration.settings.frameRate,
                    AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel
                ]
            ]
        )
        videoInput.expectsMediaDataInRealTime = true

        let audioInput = AVAssetWriterInput(
            mediaType: .audio,
            outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVNumberOfChannelsKey: 2,
                AVSampleRateKey: 44_100,
                AVEncoderBitRateKey: 128_000
            ]
        )
        audioInput.expectsMediaDataInRealTime = true

        guard writer.canAdd(videoInput), writer.canAdd(audioInput) else {
            throw RecorderError.unavailable
        }

        writer.add(videoInput)
        writer.add(audioInput)

        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: videoInput,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: dimensions.width,
                kCVPixelBufferHeightKey as String: dimensions.height,
                kCVPixelBufferCGImageCompatibilityKey as String: true,
                kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
            ]
        )

        queue.sync {
            self.writer = writer
            self.videoInput = videoInput
            self.audioInput = audioInput
            self.pixelBufferAdaptor = adaptor
            self.configuration = configuration
            self.isFinishing = false
        }
    }

    func updateDelayedFrame(_ frame: CGImage) {
        queue.async {
            self.latestDelayedFrame = frame
        }
    }

    func updateScreenFrames(_ frames: [StudioSource.ID: CGImage]) {
        queue.async {
            self.latestScreenFrames = frames
        }
    }

    func appendVideo(liveFrame: CGImage, presentationTime: CMTime) {
        queue.async {
            guard !self.isFinishing,
                  let writer = self.writer,
                  let videoInput = self.videoInput,
                  let adaptor = self.pixelBufferAdaptor,
                  let configuration = self.configuration else {
                return
            }

            if writer.status == .unknown {
                guard writer.startWriting() else {
                    return
                }
                writer.startSession(atSourceTime: presentationTime)
            }

            guard writer.status == .writing,
                  videoInput.isReadyForMoreMediaData,
                  let pixelBufferPool = adaptor.pixelBufferPool else {
                return
            }

            var pixelBuffer: CVPixelBuffer?
            guard CVPixelBufferPoolCreatePixelBuffer(nil, pixelBufferPool, &pixelBuffer) == kCVReturnSuccess,
                  let pixelBuffer else {
                return
            }

            self.render(
                liveFrame: liveFrame,
                delayedFrame: self.latestDelayedFrame,
                screenFrames: self.latestScreenFrames,
                configuration: configuration,
                into: pixelBuffer
            )
            adaptor.append(pixelBuffer, withPresentationTime: presentationTime)
        }
    }

    func appendAudio(_ sampleBuffer: CMSampleBuffer) {
        queue.async {
            guard !self.isFinishing,
                  self.configuration?.includeMicrophoneAudio == true,
                  let writer = self.writer,
                  writer.status == .writing,
                  let audioInput = self.audioInput,
                  audioInput.isReadyForMoreMediaData else {
                return
            }

            audioInput.append(sampleBuffer)
        }
    }

    func stop(completion: @escaping (Error?) -> Void) {
        queue.async {
            guard let writer = self.writer,
                  let videoInput = self.videoInput else {
                DispatchQueue.main.async {
                    completion(nil)
                }
                return
            }

            self.isFinishing = true

            guard writer.status == .writing else {
                writer.cancelWriting()
                self.clear()
                DispatchQueue.main.async {
                    completion(RecorderError.noVideoFrames)
                }
                return
            }

            videoInput.markAsFinished()
            self.audioInput?.markAsFinished()

            writer.finishWriting { [weak self, weak writer] in
                let error = writer?.error
                self?.queue.async {
                    self?.clear()
                    DispatchQueue.main.async {
                        completion(error)
                    }
                }
            }
        }
    }

    private func clear() {
        writer = nil
        videoInput = nil
        audioInput = nil
        pixelBufferAdaptor = nil
        configuration = nil
        latestScreenFrames = [:]
        isFinishing = false
    }

    private func render(
        liveFrame: CGImage,
        delayedFrame: CGImage?,
        screenFrames: [StudioSource.ID: CGImage],
        configuration: SceneRecordingConfiguration,
        into pixelBuffer: CVPixelBuffer
    ) {
        let width = configuration.settings.resolution.dimensions.width
        let height = configuration.settings.resolution.dimensions.height

        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer {
            CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
        }

        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer),
              let context = CGContext(
                data: baseAddress,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
              ) else {
            return
        }

        let canvas = CGRect(x: 0, y: 0, width: width, height: height)
        context.setFillColor(NSColor.black.cgColor)
        context.fill(canvas)
        context.interpolationQuality = .high

        let orderedSources = configuration.sources
            .filter(\.isVisible)
            .sorted { lhs, rhs in
                if lhs.layout.zIndex == rhs.layout.zIndex {
                    return lhs.name < rhs.name
                }

                return lhs.layout.zIndex < rhs.layout.zIndex
            }

        for source in orderedSources {
            let rect = bottomLeftRect(for: source.layout, canvasWidth: width, canvasHeight: height)
            context.saveGState()
            context.setAlpha(CGFloat(source.layout.opacity))

            switch source.kind {
            case .camera:
                drawImage(liveFrame, in: rect, context: context)
            case .delayedResponse:
                if let delayedFrame {
                    drawImage(delayedFrame, in: rect, context: context)
                } else {
                    drawPlaceholder("응답 영상 대기", in: rect, context: context)
                }
            case .text:
                drawText(source.text.isEmpty ? source.name : source.text, in: rect, context: context)
            case .color:
                context.setFillColor(color(from: source.colorHex).cgColor)
                context.fill(rect)
            case .image:
                if let image = source.assetURL.flatMap(NSImage.init(contentsOf:))?.cgImage(forProposedRect: nil, context: nil, hints: nil) {
                    drawImage(image, in: rect, context: context)
                } else {
                    drawPlaceholder("이미지 소스", in: rect, context: context)
                }
            case .screen:
                if let screenFrame = screenFrames[source.id] {
                    drawImage(screenFrame, in: rect, context: context)
                } else {
                    drawPlaceholder("화면 캡처", in: rect, context: context)
                }
            case .media:
                if let image = source.assetURL.flatMap(NSImage.init(contentsOf:))?.cgImage(forProposedRect: nil, context: nil, hints: nil) {
                    drawImage(image, in: rect, context: context)
                } else {
                    drawPlaceholder("미디어 소스", in: rect, context: context)
                }
            case .music:
                drawPlaceholder("뮤직 플레이리스트", in: rect, context: context)
            }

            context.restoreGState()
        }
    }

    private func bottomLeftRect(for layout: SourceLayout, canvasWidth: Int, canvasHeight: Int) -> CGRect {
        let width = Double(canvasWidth)
        let height = Double(canvasHeight)
        return CGRect(
            x: layout.x * width,
            y: (1 - layout.y - layout.height) * height,
            width: layout.width * width,
            height: layout.height * height
        )
    }

    private func drawImage(_ image: CGImage, in rect: CGRect, context: CGContext) {
        let imageAspect = CGFloat(image.width) / CGFloat(image.height)
        let rectAspect = rect.width / rect.height
        let drawRect: CGRect

        if imageAspect > rectAspect {
            let drawWidth = rect.height * imageAspect
            drawRect = CGRect(
                x: rect.midX - drawWidth / 2,
                y: rect.minY,
                width: drawWidth,
                height: rect.height
            )
        } else {
            let drawHeight = rect.width / imageAspect
            drawRect = CGRect(
                x: rect.minX,
                y: rect.midY - drawHeight / 2,
                width: rect.width,
                height: drawHeight
            )
        }

        context.saveGState()
        context.clip(to: rect)
        context.draw(image, in: drawRect)
        context.restoreGState()
    }

    private func drawPlaceholder(_ title: String, in rect: CGRect, context: CGContext) {
        let path = CGPath(roundedRect: rect, cornerWidth: 12, cornerHeight: 12, transform: nil)
        context.setFillColor(NSColor.controlBackgroundColor.withAlphaComponent(0.82).cgColor)
        context.addPath(path)
        context.fillPath()
        context.setStrokeColor(NSColor.separatorColor.cgColor)
        context.setLineWidth(2)
        context.addPath(path)
        context.strokePath()
        drawText(title, in: rect, context: context)
    }

    private func drawText(_ text: String, in rect: CGRect, context: CGContext) {
        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.alignment = .center
        paragraphStyle.lineBreakMode = .byTruncatingTail

        let attributedText = NSAttributedString(
            string: text,
            attributes: [
                .font: NSFont.systemFont(ofSize: max(min(rect.height * 0.22, 44), 16), weight: .semibold),
                .foregroundColor: NSColor.white,
                .paragraphStyle: paragraphStyle
            ]
        )

        let textHeight = min(max(rect.height * 0.38, 28), rect.height)
        let textRect = CGRect(
            x: rect.minX + min(24, rect.width * 0.08),
            y: rect.midY - textHeight / 2,
            width: max(rect.width - min(48, rect.width * 0.16), 1),
            height: textHeight
        )

        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: false)
        attributedText.draw(in: textRect)
        NSGraphicsContext.restoreGraphicsState()
    }

    private func color(from hex: String) -> NSColor {
        let sanitized = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        guard sanitized.count == 6,
              let value = Int(sanitized, radix: 16) else {
            return .systemBlue
        }

        let red = CGFloat((value >> 16) & 0xff) / 255
        let green = CGFloat((value >> 8) & 0xff) / 255
        let blue = CGFloat(value & 0xff) / 255
        return NSColor(red: red, green: green, blue: blue, alpha: 1)
    }

    enum RecorderError: LocalizedError {
        case unavailable
        case noVideoFrames

        var errorDescription: String? {
            switch self {
            case .unavailable:
                "장면 녹화 출력을 만들 수 없습니다."
            case .noVideoFrames:
                "녹화할 영상 프레임이 없습니다."
            }
        }
    }
}
