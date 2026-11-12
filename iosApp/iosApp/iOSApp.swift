import SwiftUI
import SharedApp
import Darwin
import VideoToolbox
import CoreMedia
import CoreVideo
import AVFoundation
import UIKit

/// Mantiene despiertos el proceso, la interfaz Wi-Fi (`en0`), los sockets TCP y el bucle H.264 de 15 FPS
/// cuando el usuario bloquea la pantalla del iPhone (1:1 con `MotIOS/App/BackgroundKeepAlive.swift`).
final class BackgroundKeepAlive {
    static let shared = BackgroundKeepAlive()

    private var player: AVAudioPlayer?
    private var bgTask: UIBackgroundTaskIdentifier = .invalid
    private var watchdogTimer: DispatchSourceTimer?
    private(set) var isActive: Bool = false

    private init() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleDidEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleWillEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
    }

    func start() {
        DispatchQueue.main.async {
            guard !self.isActive else {
                self.ensurePlaying()
                return
            }
            self.isActive = true
            self.activateAudioSessionAndPlay()
            self.startWatchdog()
        }
    }

    func stop() {
        DispatchQueue.main.async {
            guard self.isActive else { return }
            self.isActive = false
            self.watchdogTimer?.cancel()
            self.watchdogTimer = nil
            self.player?.stop()
            self.player = nil
            self.endTransitionTask()
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        }
    }

    private func activateAudioSessionAndPlay() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)

            if player == nil {
                let wav = Self.makeSilentWAVData(durationSeconds: 2)
                let created = try AVAudioPlayer(data: wav)
                created.numberOfLoops = -1
                created.volume = 0.01
                created.prepareToPlay()
                player = created
            }
            player?.play()
        } catch {}
    }

    private func ensurePlaying() {
        guard isActive else { return }
        if player?.isPlaying != true {
            activateAudioSessionAndPlay()
        }
    }

    private func startWatchdog() {
        watchdogTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now() + 4.0, repeating: 4.0)
        timer.setEventHandler { [weak self] in
            self?.ensurePlaying()
        }
        watchdogTimer = timer
        timer.resume()
    }

    @objc private func handleDidEnterBackground() {
        guard isActive else { return }
        beginTransitionTask()
        ensurePlaying()
    }

    @objc private func handleWillEnterForeground() {
        endTransitionTask()
        if isActive {
            ensurePlaying()
        }
    }

    @objc private func handleAudioInterruption(_ notification: Notification) {
        guard isActive else { return }
        guard let info = notification.userInfo,
              let typeVal = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeVal) else {
            return
        }
        if type == .ended {
            activateAudioSessionAndPlay()
        }
    }

    private func beginTransitionTask() {
        endTransitionTask()
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "motillOS-LockStreamBridge") { [weak self] in
            self?.endTransitionTask()
        }
    }

    private func endTransitionTask() {
        if bgTask != .invalid {
            UIApplication.shared.endBackgroundTask(bgTask)
            bgTask = .invalid
        }
    }

    private static func makeSilentWAVData(durationSeconds: Int) -> Data {
        let sampleRate: UInt32 = 8000
        let numChannels: UInt16 = 1
        let bitsPerSample: UInt16 = 16
        let bytesPerSample = Int(bitsPerSample / 8)
        let dataSize = Int(sampleRate) * durationSeconds * Int(numChannels) * bytesPerSample

        var out = Data(capacity: 44 + dataSize)
        out.append(contentsOf: [0x52, 0x49, 0x46, 0x46]) // "RIFF"
        out.append(contentsOf: withUnsafeBytes(of: UInt32(36 + dataSize).littleEndian, Array.init))
        out.append(contentsOf: [0x57, 0x41, 0x56, 0x45]) // "WAVE"
        out.append(contentsOf: [0x66, 0x6D, 0x74, 0x20]) // "fmt "
        out.append(contentsOf: withUnsafeBytes(of: UInt32(16).littleEndian, Array.init))
        out.append(contentsOf: withUnsafeBytes(of: UInt16(1).littleEndian, Array.init))
        out.append(contentsOf: withUnsafeBytes(of: numChannels.littleEndian, Array.init))
        out.append(contentsOf: withUnsafeBytes(of: sampleRate.littleEndian, Array.init))
        out.append(contentsOf: withUnsafeBytes(of: (sampleRate * UInt32(numChannels) * UInt32(bytesPerSample)).littleEndian, Array.init))
        out.append(contentsOf: withUnsafeBytes(of: (numChannels * UInt16(bytesPerSample)).littleEndian, Array.init))
        out.append(contentsOf: withUnsafeBytes(of: bitsPerSample.littleEndian, Array.init))
        out.append(contentsOf: [0x64, 0x61, 0x74, 0x61]) // "data"
        out.append(contentsOf: withUnsafeBytes(of: UInt32(dataSize).littleEndian, Array.init))
        out.append(Data(count: dataSize))
        return out
    }
}

/// Callback C para `VTCompressionSessionCreate` fuera de la clase para evitar referencias a `Self`.
private func videoToolboxOutputCallback(
    outputCallbackRefCon: UnsafeMutableRawPointer?,
    sourceFrameRefCon: UnsafeMutableRawPointer?,
    status: OSStatus,
    infoFlags: VTEncodeInfoFlags,
    sampleBuffer: CMSampleBuffer?
) {
    guard status == noErr,
          let refcon = outputCallbackRefCon,
          let sampleBuffer = sampleBuffer,
          CMSampleBufferDataIsReady(sampleBuffer) else {
        return
    }
    if infoFlags.contains(.frameDropped) { return }
    let bridge = Unmanaged<VideoToolboxBridge>.fromOpaque(refcon).takeUnretainedValue()
    bridge.storeEncodedSample(sampleBuffer)
}

/// Puente hardware VideoToolbox H.264 1:1 con `MotIOS/Video/H264Encoder.swift`.
final class VideoToolboxBridge: NSObject, IosH264HardwareBridge {
    private static let startCode: [UInt8] = [0x00, 0x00, 0x00, 0x01]

    private var session: VTCompressionSession?
    private var pixelBufferPool: CVPixelBufferPool?
    private let lock = NSLock()

    private var cachedWidth: Int32 = 592
    private var cachedHeight: Int32 = 816
    private var cachedFps: Int32 = 15
    private var cachedBitrate: Int32 = 2_500_000
    private var lastEncodedAnnexB: Data?

    func startSession(width: Int32, height: Int32, fps: Int32, bitrateBps: Int32) -> Bool {
        BackgroundKeepAlive.shared.start()
        lock.lock()
        defer { lock.unlock() }
        cachedWidth = width
        cachedHeight = height
        cachedFps = max(fps, 5)
        cachedBitrate = max(bitrateBps, 500_000)
        return createSessionLocked()
    }

    private func createSessionLocked() -> Bool {
        stopSessionInternal()

        let w = Int(cachedWidth)
        let h = Int(cachedHeight)
        let pixelAttrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: Int(kCVPixelFormatType_32BGRA),
            kCVPixelBufferWidthKey: w,
            kCVPixelBufferHeightKey: h,
            kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary
        ]

        var newSession: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: cachedWidth,
            height: cachedHeight,
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil,
            imageBufferAttributes: pixelAttrs as CFDictionary,
            compressedDataAllocator: nil,
            outputCallback: videoToolboxOutputCallback,
            refcon: Unmanaged.passUnretained(self).toOpaque(),
            compressionSessionOut: &newSession
        )
        guard status == noErr, let vtSession = newSession else {
            return false
        }

        let blocks = ((w + 15) / 16) * ((h + 15) / 16)
        let profile = blocks <= 3600 ? kVTProfileLevel_H264_Baseline_3_1 : kVTProfileLevel_H264_Baseline_AutoLevel

        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_ProfileLevel, value: profile)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_AverageBitRate, value: Int(cachedBitrate) as CFNumber)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: Int(cachedFps) as CFNumber)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: Int(cachedFps * 2) as CFNumber)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: 2.0 as CFNumber)

        let prepStatus = VTCompressionSessionPrepareToEncodeFrames(vtSession)
        if prepStatus != noErr {
            VTCompressionSessionInvalidate(vtSession)
            return false
        }

        self.session = vtSession
        self.pixelBufferPool = VTCompressionSessionGetPixelBufferPool(vtSession)
        return true
    }

    func encodeRgbaPtr(rgbaPtr: Int64, width: Int32, height: Int32, ptsMs: Int64, forceKeyFrame: Bool) -> String? {
        lock.lock()
        defer { lock.unlock() }

        if session == nil || width != cachedWidth || height != cachedHeight {
            cachedWidth = width
            cachedHeight = height
            _ = createSessionLocked()
        }
        guard let vtSession = session else { return nil }
        guard let srcBase = UnsafeRawPointer(bitPattern: Int(rgbaPtr))?.assumingMemoryBound(to: UInt8.self) else {
            return nil
        }

        let w = Int(width)
        let h = Int(height)
        guard w > 0, h > 0 else { return nil }

        guard let pixelBuffer = allocatePixelBuffer(width: w, height: h) else {
            return nil
        }

        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        if let dstRaw = CVPixelBufferGetBaseAddress(pixelBuffer) {
            let dstBase = dstRaw.assumingMemoryBound(to: UInt8.self)
            let dstStride = CVPixelBufferGetBytesPerRow(pixelBuffer)
            let srcStride = w * 4
            for row in 0..<h {
                let srcRow = srcBase.advanced(by: row * srcStride)
                let dstRow = dstBase.advanced(by: row * dstStride)
                for col in 0..<w {
                    let px = col * 4
                    dstRow[px]     = srcRow[px + 2] // B
                    dstRow[px + 1] = srcRow[px + 1] // G
                    dstRow[px + 2] = srcRow[px]     // R
                    dstRow[px + 3] = 255            // A
                }
            }
        }
        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])

        var frameProps: CFDictionary? = nil
        if forceKeyFrame {
            let dict: [CFString: Any] = [kVTEncodeFrameOptionKey_ForceKeyFrame: true]
            frameProps = dict as CFDictionary
        }

        let pts = CMTimeMake(value: ptsMs, timescale: 1000)
        let duration = CMTimeMake(value: 1, timescale: cachedFps)
        lastEncodedAnnexB = nil

        var flagsOut = VTEncodeInfoFlags()
        let encodeStatus = VTCompressionSessionEncodeFrame(
            vtSession,
            imageBuffer: pixelBuffer,
            presentationTimeStamp: pts,
            duration: duration,
            frameProperties: frameProps,
            sourceFrameRefcon: nil,
            infoFlagsOut: &flagsOut
        )

        if encodeStatus != noErr {
            // Auto-recuperación si iOS invalida la sesión al bloquear/desbloquear pantalla
            _ = createSessionLocked()
            return nil
        }

        VTCompressionSessionCompleteFrames(vtSession, untilPresentationTimeStamp: pts)
        return lastEncodedAnnexB?.base64EncodedString()
    }

    private func allocatePixelBuffer(width: Int, height: Int) -> CVPixelBuffer? {
        var pb: CVPixelBuffer?
        if let pool = pixelBufferPool {
            let status = CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &pb)
            if status == kCVReturnSuccess, let pb = pb {
                return pb
            }
        }
        let attrs: [CFString: Any] = [
            kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary
        ]
        CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32BGRA,
            attrs as CFDictionary,
            &pb
        )
        return pb
    }

    func stopSession() {
        lock.lock()
        defer { lock.unlock() }
        stopSessionInternal()
    }

    private func stopSessionInternal() {
        if let vtSession = session {
            VTCompressionSessionCompleteFrames(vtSession, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(vtSession)
            session = nil
        }
        pixelBufferPool = nil
        lastEncodedAnnexB = nil
    }

    fileprivate func storeEncodedSample(_ sampleBuffer: CMSampleBuffer) {
        lastEncodedAnnexB = VideoToolboxBridge.convertSampleBufferToAnnexB(sampleBuffer)
    }

    private static func isKeyframe(_ sampleBuffer: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[CFString: Any]],
              let first = attachments.first else {
            return true
        }
        if let notSync = first[kCMSampleAttachmentKey_NotSync] as? Bool {
            return !notSync
        }
        return true
    }

    private static func convertSampleBufferToAnnexB(_ sampleBuffer: CMSampleBuffer) -> Data? {
        guard let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer),
              let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else {
            return nil
        }

        var nalHeaderLen: Int32 = 4
        var spsPtr: UnsafePointer<UInt8>?
        var spsSize: Int = 0
        var ppsPtr: UnsafePointer<UInt8>?
        var ppsSize: Int = 0

        let spsOk = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            formatDesc,
            parameterSetIndex: 0,
            parameterSetPointerOut: &spsPtr,
            parameterSetSizeOut: &spsSize,
            parameterSetCountOut: nil,
            nalUnitHeaderLengthOut: &nalHeaderLen
        )
        let ppsOk = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            formatDesc,
            parameterSetIndex: 1,
            parameterSetPointerOut: &ppsPtr,
            parameterSetSizeOut: &ppsSize,
            parameterSetCountOut: nil,
            nalUnitHeaderLengthOut: nil
        )

        let totalLength = CMBlockBufferGetDataLength(blockBuffer)
        guard totalLength > 0 else { return nil }

        var rawAVCC = Data(count: totalLength)
        let copyStatus = rawAVCC.withUnsafeMutableBytes { rawBuf -> OSStatus in
            guard let dst = rawBuf.baseAddress else { return -1 }
            return CMBlockBufferCopyDataBytes(blockBuffer, atOffset: 0, dataLength: totalLength, destination: dst)
        }
        guard copyStatus == kCMBlockBufferNoErr else { return nil }

        let keyframe = isKeyframe(sampleBuffer)
        var out = Data(capacity: totalLength + spsSize + ppsSize + 32)

        if keyframe, spsOk == noErr, ppsOk == noErr, let sps = spsPtr, let pps = ppsPtr {
            out.append(contentsOf: startCode)
            out.append(sps, count: spsSize)
            out.append(contentsOf: startCode)
            out.append(pps, count: ppsSize)
        }

        let lenSize = max(1, min(4, Int(nalHeaderLen)))
        var offset = 0
        while offset + lenSize <= totalLength {
            var naluLength = 0
            for i in 0..<lenSize {
                naluLength = (naluLength << 8) | Int(rawAVCC[offset + i])
            }
            offset += lenSize
            guard naluLength > 0, offset + naluLength <= totalLength else { break }

            let nalType = rawAVCC[offset] & 0x1F
            // Evitar AUD(9) delante de SPS y evitar duplicar SPS(7)/PPS(8) en keyframes
            if nalType != 9 && !(keyframe && (nalType == 7 || nalType == 8)) {
                out.append(contentsOf: startCode)
                out.append(rawAVCC.subdata(in: offset..<(offset + naluLength)))
            }
            offset += naluLength
        }

        return out.isEmpty ? nil : out
    }
}

final class IosWifiBridgeImpl: NSObject, IosWifiBridge {
    func currentWifiIp() -> String? {
        var ifaddrPtr: UnsafeMutablePointer<ifaddrs>?
        guard Darwin.getifaddrs(&ifaddrPtr) == 0, let first = ifaddrPtr else {
            return nil
        }
        defer { Darwin.freeifaddrs(ifaddrPtr) }

        var fallbackIp: String? = nil
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let ifa = cursor?.pointee {
            defer { cursor = ifa.ifa_next }

            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let flags = Int32(ifa.ifa_flags)
            guard (flags & IFF_UP) != 0, (flags & IFF_LOOPBACK) == 0 else { continue }

            let name = String(cString: ifa.ifa_name)
            var sin = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            guard Darwin.inet_ntop(AF_INET, &sin.sin_addr, &buf, socklen_t(INET_ADDRSTRLEN)) != nil else { continue }
            let ip = String(cString: buf)
            guard !ip.hasPrefix("169.254.") else { continue }

            if name == "en0" {
                return ip
            }
            if fallbackIp == nil && (name.hasPrefix("en") || name == "bridge100") {
                fallbackIp = ip
            }
        }
        return fallbackIp
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
        IosWifiBridgeHolder.shared.bridge = IosWifiBridgeImpl()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}
