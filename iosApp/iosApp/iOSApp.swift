import SwiftUI
import SharedApp
import VideoToolbox
import CoreMedia
import CoreVideo

final class VideoToolboxBridge: NSObject, IosH264HardwareBridge {
    private var session: VTCompressionSession?
    private let lock = NSLock()

    func startSession(width: Int32, height: Int32, fps: Int32, bitrateBps: Int32) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        stopSessionInternal()

        var newSession: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: width,
            height: height,
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: nil,
            refcon: nil,
            compressionSessionOut: &newSession
        )
        guard status == noErr, let sess = newSession else {
            return false
        }

        VTSessionSetProperty(sess, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(sess, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Baseline_AutoLevel)
        VTSessionSetProperty(sess, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(sess, key: kVTCompressionPropertyKey_AverageBitRate, value: NSNumber(value: bitrateBps))
        VTSessionSetProperty(sess, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: NSNumber(value: fps))
        VTSessionSetProperty(sess, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: NSNumber(value: max(fps * 2, 15)))
        VTCompressionSessionPrepareToEncodeFrames(sess)

        self.session = sess
        return true
    }

    func encodeNv12Ptr(nv12Ptr: Int64, width: Int32, height: Int32, ptsMs: Int64, forceKeyFrame: Bool) -> String? {
        lock.lock()
        defer { lock.unlock() }
        guard let sess = session else { return nil }
        guard let srcBase = UnsafeRawPointer(bitPattern: Int(nv12Ptr))?.assumingMemoryBound(to: UInt8.self) else {
            return nil
        }

        let w = Int(width)
        let h = Int(height)
        guard w > 0, h > 0 else { return nil }

        var pixelBufferOut: CVPixelBuffer?
        let pbStatus = CVPixelBufferCreate(
            kCFAllocatorDefault,
            w,
            h,
            kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            nil,
            &pixelBufferOut
        )
        guard pbStatus == kCVReturnSuccess, let pixelBuffer = pixelBufferOut else {
            return nil
        }

        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        if let yDst = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0)?.assumingMemoryBound(to: UInt8.self) {
            let yStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
            for row in 0..<h {
                memcpy(yDst.advanced(by: row * yStride), srcBase.advanced(by: row * w), w)
            }
        }
        if let uvDst = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1)?.assumingMemoryBound(to: UInt8.self) {
            let uvStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1)
            let uvSrc = srcBase.advanced(by: w * h)
            let uvH = h / 2
            for row in 0..<uvH {
                memcpy(uvDst.advanced(by: row * uvStride), uvSrc.advanced(by: row * w), w)
            }
        }
        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])

        var frameProps: CFDictionary? = nil
        if forceKeyFrame {
            let dict: [CFString: Any] = [kVTEncodeFrameOptionKey_ForceKeyFrame: true]
            frameProps = dict as CFDictionary
        }

        let pts = CMTimeMake(value: ptsMs, timescale: 1000)
        var annexBResult: Data? = nil

        let encodeStatus = VTCompressionSessionEncodeFrame(
            sess,
            imageBuffer: pixelBuffer,
            presentationTimeStamp: pts,
            duration: CMTime.invalid,
            frameProperties: frameProps,
            infoFlagsOut: nil
        ) { cbStatus, _, sampleBuffer in
            guard cbStatus == noErr, let sbuf = sampleBuffer, CMSampleBufferDataIsReady(sbuf) else { return }
            annexBResult = Self.convertToAnnexB(sampleBuffer: sbuf)
        }

        if encodeStatus == noErr {
            VTCompressionSessionCompleteFrames(sess, untilPresentationTimeStamp: pts)
        }
        return annexBResult?.base64EncodedString()
    }

    func stopSession() {
        lock.lock()
        defer { lock.unlock() }
        stopSessionInternal()
    }

    private func stopSessionInternal() {
        if let sess = session {
            VTCompressionSessionInvalidate(sess)
            session = nil
        }
    }

    private static func convertToAnnexB(sampleBuffer: CMSampleBuffer) -> Data? {
        let startCode: [UInt8] = [0x00, 0x00, 0x00, 0x01]
        var out = Data()

        var isKeyFrame = true
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[CFString: Any]],
           let first = attachments.first,
           let notSync = first[kCMSampleAttachmentKey_NotSync] as? Bool,
           notSync {
            isKeyFrame = false
        }

        if isKeyFrame, let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer) {
            var spsPtr: UnsafePointer<UInt8>?
            var spsSize: Int = 0
            var ppsPtr: UnsafePointer<UInt8>?
            var ppsSize: Int = 0

            if CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                formatDesc,
                parameterSetIndex: 0,
                parameterSetPointerOut: &spsPtr,
                parameterSetSizeOut: &spsSize,
                parameterSetCountOut: nil,
                nalUnitHeaderLengthOut: nil
            ) == noErr, let sps = spsPtr, spsSize > 0 {
                out.append(contentsOf: startCode)
                out.append(sps, count: spsSize)
            }

            if CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                formatDesc,
                parameterSetIndex: 1,
                parameterSetPointerOut: &ppsPtr,
                parameterSetSizeOut: &ppsSize,
                parameterSetCountOut: nil,
                nalUnitHeaderLengthOut: nil
            ) == noErr, let pps = ppsPtr, ppsSize > 0 {
                out.append(contentsOf: startCode)
                out.append(pps, count: ppsSize)
            }
        }

        guard let dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return nil }
        var totalLength: Int = 0
        var dataPointer: UnsafeMutablePointer<Int8>?
        let status = CMBlockBufferGetDataPointer(
            dataBuffer,
            atOffset: 0,
            lengthAtOffsetOut: nil,
            totalLengthOut: &totalLength,
            dataPointerOut: &dataPointer
        )
        guard status == kCMBlockBufferNoErr, let basePtr = dataPointer, totalLength > 4 else { return nil }

        let rawPtr = UnsafeRawPointer(basePtr).assumingMemoryBound(to: UInt8.self)
        var offset = 0
        while offset + 4 <= totalLength {
            let b0 = Int(rawPtr[offset])
            let b1 = Int(rawPtr[offset + 1])
            let b2 = Int(rawPtr[offset + 2])
            let b3 = Int(rawPtr[offset + 3])
            let nalLength = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
            offset += 4
            guard nalLength > 0, offset + nalLength <= totalLength else { break }
            out.append(contentsOf: startCode)
            out.append(rawPtr.advanced(by: offset), count: nalLength)
            offset += nalLength
        }

        return out.isEmpty ? nil : out
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct iOSApp: App {
    init() {
        IosH264BridgeHolder.shared.bridge = VideoToolboxBridge()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}
