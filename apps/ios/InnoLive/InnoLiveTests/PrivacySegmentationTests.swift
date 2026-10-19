#if DEBUG
import CoreML
import CoreImage
import XCTest
import AVFoundation
@testable import InnoLive

final class PrivacySegmentationTests: XCTestCase {
    func testQuarterResolutionBlurPreservesFullResolutionStrength() throws {
        let url = try performanceFixture()
        let face = try XCTUnwrap(CIImage(contentsOf: url))
        let model = try PrivacyModel()
        let context = CIContext()
        for size in [CGSize(width: 1920, height: 1080), CGSize(width: 1080, height: 1920), CGSize(width: 1280, height: 720)] {
            let input = face.transformed(by: CGAffineTransform(scaleX: size.width / face.extent.width, y: size.height / face.extent.height))
            let reference = try XCTUnwrap(context.createCGImage(model.broadcastStyleBlur(input, reduction: 2), from: input.extent))
            let optimized = try XCTUnwrap(context.createCGImage(model.broadcastStyleBlur(input, reduction: 4), from: input.extent))
            let pixels = Int(size.width * size.height)
            var before = [UInt8](repeating: 0, count: pixels * 4)
            var after = before
            context.render(CIImage(cgImage: reference), toBitmap: &before, rowBytes: Int(size.width) * 4,
                bounds: input.extent, format: .RGBA8, colorSpace: CGColorSpaceCreateDeviceRGB())
            context.render(CIImage(cgImage: optimized), toBitmap: &after, rowBytes: Int(size.width) * 4,
                bounds: input.extent, format: .RGBA8, colorSpace: CGColorSpaceCreateDeviceRGB())
            var total = 0
            var maximum = 0
            for pixel in 0..<pixels {
                for channel in 0..<3 {
                    let delta = abs(Int(before[pixel * 4 + channel]) - Int(after[pixel * 4 + channel]))
                    total += delta
                    maximum = max(maximum, delta)
                }
            }
            let mean = Double(total) / Double(pixels * 3)
            print("PRIVACY_BLUR_PARITY size=\(size) meanRGB8=\(mean) maximumRGB8=\(maximum)")
            XCTAssertLessThanOrEqual(mean, 2)
            XCTAssertLessThanOrEqual(maximum, 12)
            XCTAssertEqual(PrivacyModel.broadcastBlurSigma(for: input.extent), size.height == 720 ? 48 : 72)
        }
    }

    func testInterleavedOutputFormats() throws {
        let url = try performanceFixture()
        let face = try XCTUnwrap(CIImage(contentsOf: url))
        let image = face.transformed(by: CGAffineTransform(scaleX: 1080 / face.extent.width, y: 1080 / face.extent.height))
            .composited(over: CIImage(color: .gray)).cropped(to: CGRect(x: 0, y: 0, width: 1920, height: 1080))
        let formats: [OSType] = [kCVPixelFormatType_32BGRA, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
        let pools = formats.map { PrivacyOutputBufferPool(pixelFormat: $0) }
        let model = try PrivacyModel()
        var totals = [[Double](), [Double]()]
        for iteration in 0..<50 {
            for index in iteration.isMultiple(of: 2) ? [0, 1] : [1, 0] {
                try autoreleasepool {
                    let output = try pools[index].acquire(width: 1920, height: 1080)
                    let result = try model.process(image, into: output, restoring: .up)
                    XCTAssertGreaterThanOrEqual(result.0, 1)
                    if iteration >= 10 { totals[index].append(result.1.total) }
                }
            }
        }
        for index in 0..<2 {
            let sorted = totals[index].sorted()
            print("PRIVACY_FORMAT format=\(formats[index]) median=\(sorted[sorted.count/2]) p95=\(sorted[Int(Double(sorted.count)*0.95)]) thermal=\(ProcessInfo.processInfo.thermalState.rawValue)")
        }
    }

    private func performanceFixture() throws -> URL {
        guard Bundle.main.url(forResource: "PrivacyDetector", withExtension: "mlmodelc") != nil,
              let url = Bundle(for: Self.self).url(forResource: "privacy-performance-face", withExtension: "png") else {
            throw XCTSkip("Optional local Core ML model and public performance fixture are required")
        }
        return url
    }

    func testFullHDProcessingBenchmark() async throws {
        guard Bundle.main.url(forResource: "PrivacyDetector", withExtension: "mlmodelc") != nil else {
            throw XCTSkip("Local Core ML models are not installed")
        }
        let url = try XCTUnwrap(Bundle.main.url(forResource: "SimulatorFixture", withExtension: "mp4"))
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        let (frame, _) = try await generator.image(at: CMTime(seconds: 1, preferredTimescale: 600))
        let image = CIImage(cgImage: frame)
        let input = image.transformed(by: CGAffineTransform(scaleX: 1920 / image.extent.width, y: 1080 / image.extent.height))
        let model = try PrivacyModel()
        for i in 0..<25 {
            try autoreleasepool {
                let result = try model.process(input)
                if i >= 5 {
                    print("PRIVACY_BENCH index=\(i) objects=\(result.1) prepare=\(result.2.prepare) inference=\(result.2.inference) mask=\(result.2.mask) render=\(result.2.render) total=\(result.2.total)")
                }
            }
        }
    }

    func testFaceProcessingBenchmarkAndDirectOutputParity() throws {
        guard Bundle.main.url(forResource: "PrivacyDetector", withExtension: "mlmodelc") != nil else {
            throw XCTSkip("Local Core ML models are not installed")
        }
        guard let url = Bundle(for: Self.self).url(forResource: "privacy-performance-face", withExtension: "png"),
              let face = CIImage(contentsOf: url) else { throw XCTSkip("Optional public astronaut fixture is not installed") }
        let context = CIContext()
        let pool = PrivacyOutputBufferPool()
        for count in [1, 5] {
            let bounds = CGRect(x: 0, y: 0, width: 1920, height: 1080)
            var image = CIImage(color: .gray).cropped(to: bounds)
            let side: CGFloat = count == 1 ? 1080 : 540
            for index in 0..<count {
                let tile = face.transformed(by: CGAffineTransform(scaleX: side / face.extent.width, y: side / face.extent.height))
                    .transformed(by: CGAffineTransform(translationX: CGFloat(index % 3) * side, y: CGFloat(index / 3) * side))
                image = tile.composited(over: image)
            }
            image = image.cropped(to: bounds)
            let model = try PrivacyModel()
            for direct in [false, true] {
                model.resetTemporalState()
                for index in 0..<25 {
                    try autoreleasepool {
                        let timing: PrivacyTimings
                        let objects: Int
                        if direct {
                            let output = try pool.acquire(width: 1920, height: 1080)
                            let result = try model.process(image, into: output, restoring: .up)
                            (objects, timing) = (result.0, result.1)
                        } else {
                            let result = try model.process(image)
                            (objects, timing) = (result.1, result.2)
                        }
                        XCTAssertGreaterThanOrEqual(objects, count)
                        if index >= 5 {
                            print("PRIVACY_FACE_BENCH faces=\(count) direct=\(direct) prepare=\(timing.prepare) inference=\(timing.inference) mask=\(timing.mask) render=\(timing.render) total=\(timing.total)")
                        }
                    }
                }
            }
            for rotation in [0, 90, 180, 270] {
                let orientation = PrivacyUplinkProcessor.orientation(rotation: rotation)
                model.resetTemporalState()
                let reference = try model.process(image).0
                model.resetTemporalState()
                let output = try pool.acquire(width: rotation % 180 == 0 ? 1920 : 1080,
                                              height: rotation % 180 == 0 ? 1080 : 1920)
                _ = try model.process(image, into: output, restoring: orientation.reverse)
                let referenceImage = CIImage(cgImage: reference).oriented(orientation.reverse)
                let actualImage = CIImage(cvPixelBuffer: output)
                let difference = referenceImage.applyingFilter("CIDifferenceBlendMode", parameters: [kCIInputBackgroundImageKey: actualImage])
                    .applyingFilter("CIAreaMaximum", parameters: [kCIInputExtentKey: CIVector(cgRect: actualImage.extent)])
                var pixel = [UInt8](repeating: 0, count: 4)
                context.render(difference, toBitmap: &pixel, rowBytes: 4, bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
                               format: .RGBA8, colorSpace: CGColorSpaceCreateDeviceRGB())
                XCTAssertLessThanOrEqual(pixel.prefix(3).max()!, 2, "Direct render changed pixels at rotation \(rotation)")
            }
        }
    }

    func testFloat16ConversionPreservesValuesAndNonFiniteRejection() throws {
        let input: [Float16] = [0, -0, 1, -2, .leastNonzeroMagnitude, .greatestFiniteMagnitude, .infinity, -.infinity, .nan]
        let tensor = try MLMultiArray(shape: [NSNumber(value: input.count)], dataType: .float16)
        let pointer = tensor.dataPointer.assumingMemoryBound(to: Float16.self)
        for i in input.indices { pointer[i] = input[i] }
        let result = try PrivacySegmentation.floats(tensor, shape: [input.count])
        for i in input.indices {
            if input[i].isNaN { XCTAssertTrue(result[i].isNaN) }
            else { XCTAssertEqual(result[i], Float(input[i])) }
        }
        var prototypes = [Float](repeating: 0, count: 32 * 160 * 160)
        prototypes[100] = result.last!
        XCTAssertThrowsError(try PrivacySegmentation.instanceMasks(detections: [], prototypes: prototypes))
    }

    func testFiniteValidationCoversEveryPosition() {
        XCTAssertTrue(PrivacySegmentation.allFinite([]))
        XCTAssertTrue(PrivacySegmentation.allFinite([0, -0, .leastNonzeroMagnitude, .greatestFiniteMagnitude]))
        for value: Float in [.nan, .infinity, -.infinity] {
            for index in [0, 1, 819199] {
                var input = [Float](repeating: 0, count: 819200)
                input[index] = value
                XCTAssertFalse(PrivacySegmentation.allFinite(input))
            }
        }
    }

    func testOutputPoolDoesNotReuseRetainedFramesAndBoundsAllocation() throws {
        let pool = PrivacyOutputBufferPool()
        var frames: [CVPixelBuffer] = []
        for _ in 0..<6 { frames.append(try pool.acquire(width: 128, height: 72)) }
        XCTAssertEqual(Set(frames.map { Unmanaged.passUnretained($0).toOpaque() }).count, 6)
        XCTAssertThrowsError(try pool.acquire(width: 128, height: 72))
        frames.removeAll()
        XCTAssertNoThrow(try pool.acquire(width: 128, height: 72))
        let rotated = try pool.acquire(width: 72, height: 128)
        XCTAssertEqual(CVPixelBufferGetWidth(rotated), 72)
        XCTAssertEqual(CVPixelBufferGetHeight(rotated), 128)
    }

    func testClassAwareSuppressionKeepsPlateOverlappingFace() throws {
        let count = 3
        var raw = [Float](repeating: 0, count: 38 * count)
        for i in 0..<count {
            raw[i] = 320; raw[count + i] = 320
            raw[2 * count + i] = 80; raw[3 * count + i] = 80
        }
        raw[4 * count] = 0.9
        raw[4 * count + 1] = 0.8
        raw[5 * count + 2] = 0.7
        let found = try PrivacySegmentation.detections(raw, count: count)
        XCTAssertEqual(found.map(\.classID), [0, 1])
        XCTAssertEqual(found[0].box, CGRect(x: 280, y: 280, width: 80, height: 80))
    }

    func testInvalidOutputFailsInsteadOfShowingOriginal() {
        XCTAssertThrowsError(try PrivacySegmentation.detections([0], count: 1))
        var raw = [Float](repeating: 0, count: 38)
        raw[4] = 0.9; raw[0] = .nan; raw[2] = 80; raw[3] = 80
        XCTAssertThrowsError(try PrivacySegmentation.detections(raw, count: 1))
    }

    func testEmptyInstanceMaskProtectsDetectionBox() throws {
        let detection = PrivacySegmentation.Detection(box: CGRect(x: 40, y: 80, width: 16, height: 16),
                                                      score: 0.9, classID: 0,
                                                      coefficients: [Float](repeating: 0, count: 32))
        let mask = try PrivacySegmentation.unionMask(detections: [detection],
                                                     prototypes: [Float](repeating: 0, count: 32 * 160 * 160))
        XCTAssertEqual(mask.filter { $0 == 255 }.count, 16)
        XCTAssertEqual(mask[20 * 160 + 10], 255)
        XCTAssertEqual(mask[10 * 160 + 20], 0, "Mask coordinates must not be transposed")
    }

    func testLetterboxPreservesPortraitAndLandscapeGeometry() {
        let portrait = PrivacySegmentation.Letterbox(size: CGSize(width: 1080, height: 1920))
        XCTAssertEqual(portrait.resized, CGSize(width: 360, height: 640))
        XCTAssertEqual(portrait.left, 140)
        XCTAssertEqual(portrait.bottom, 0)
        let landscape = PrivacySegmentation.Letterbox(size: CGSize(width: 1920, height: 1080))
        XCTAssertEqual(landscape.resized, CGSize(width: 640, height: 360))
        XCTAssertEqual(landscape.left, 0)
        XCTAssertEqual(landscape.bottom, 140)
    }

    func testSoftEdgeDoesNotReducePreviouslyProtectedArea() throws {
        var bytes = [UInt8](repeating: 0, count: 160 * 160)
        for y in 40..<80 { for x in 40..<80 { bytes[y * 160 + x] = 255 } }
        let hard = try maskPixels(PrivacyMask.modelImage(bytes: bytes, feathered: false))
        let soft = try maskPixels(PrivacyMask.modelImage(bytes: bytes))
        for i in hard.indices {
            XCTAssertGreaterThanOrEqual(Int(soft[i]) + 1, Int(hard[i]))
        }
        XCTAssertTrue(soft.indices.contains { hard[$0] == 0 && soft[$0] > 0 && soft[$0] < 255 })
        XCTAssertEqual(soft[0], 0)
    }

    func testMaskTopLeftCoordinatesSurviveCoreImageConversion() throws {
        var bytes = [UInt8](repeating: 0, count: 160 * 160)
        for y in 20..<30 { for x in 10..<20 { bytes[y * 160 + x] = 255 } }
        let image = try PrivacyMask.modelImage(bytes: bytes)
        let context = CIContext(options: [.useSoftwareRenderer: true])
        // iOS 18 Core Image는 한 픽셀을 읽을 때도 행 바이트 수가 4의 배수여야 한다.
        var sample = [UInt8](repeating: 0, count: 4)
        context.render(image, toBitmap: &sample, rowBytes: 4,
                       bounds: CGRect(x: 15, y: 135, width: 1, height: 1), format: .L8,
                       colorSpace: CGColorSpaceCreateDeviceGray())
        XCTAssertEqual(sample[0], 255)
        context.render(image, toBitmap: &sample, rowBytes: 4,
                       bounds: CGRect(x: 15, y: 25, width: 1, height: 1), format: .L8,
                       colorSpace: CGColorSpaceCreateDeviceGray())
        XCTAssertEqual(sample[0], 0)
    }

    private func maskPixels(_ image: CIImage) throws -> [UInt8] {
        let context = CIContext(options: [.useSoftwareRenderer: true])
        var pixels = [UInt8](repeating: 0, count: 160 * 160)
        context.render(image, toBitmap: &pixels, rowBytes: 160,
                       bounds: CGRect(x: 0, y: 0, width: 160, height: 160), format: .L8,
                       colorSpace: CGColorSpaceCreateDeviceGray())
        return pixels
    }
}
#endif
