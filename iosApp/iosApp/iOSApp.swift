import SwiftUI
import SharedApp
import Darwin
import VideoToolbox
import CoreMedia
import CoreVideo
import AVFoundation
import QuartzCore
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

final class QrScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScanned: ((String) -> Void)?
    var onManualFallback: (() -> Void)?
    var onError: ((String) -> Void)?

    private let captureSession = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.oscar.endulink.qrscanner.session")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var videoDevice: AVCaptureDevice?
    private var isConfigured = false
    private var hasFinished = false
    private var isTorchOn = false

    private let dimOverlayView = UIView()
    private let dimMaskLayer = CAShapeLayer()
    private let reticleLayer = CAShapeLayer()
    private let torchButton = UIButton(type: .system)

    override func viewDidLoad() {
        super.viewDidLoad()
        overrideUserInterfaceStyle = .dark
        view.backgroundColor = .black

        let layer = AVCaptureVideoPreviewLayer(session: captureSession)
        layer.videoGravity = .resizeAspectFill
        view.layer.insertSublayer(layer, at: 0)
        previewLayer = layer

        dimOverlayView.isUserInteractionEnabled = false
        dimOverlayView.backgroundColor = UIColor.black.withAlphaComponent(0.58)
        dimMaskLayer.fillRule = .evenOdd
        dimOverlayView.layer.mask = dimMaskLayer
        view.addSubview(dimOverlayView)

        reticleLayer.fillColor = UIColor.clear.cgColor
        reticleLayer.strokeColor = UIColor(red: 1.0, green: 0.42, blue: 0.0, alpha: 1.0).cgColor
        reticleLayer.lineWidth = 3.0
        view.layer.addSublayer(reticleLayer)

        setupOverlayUI()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        hasFinished = false
        sessionQueue.async { [weak self] in
            self?.configureAndStartSession()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        setTorch(enabled: false)
        sessionQueue.async { [weak self] in
            if self?.captureSession.isRunning == true {
                self?.captureSession.stopRunning()
            }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let bounds = view.bounds
        previewLayer?.frame = bounds
        dimOverlayView.frame = bounds

        if let connection = previewLayer?.connection, connection.isVideoOrientationSupported {
            let interfaceOrientation = view.window?.windowScene?.interfaceOrientation ?? .portrait
            switch interfaceOrientation {
            case .landscapeLeft:
                connection.videoOrientation = .landscapeLeft
            case .landscapeRight:
                connection.videoOrientation = .landscapeRight
            case .portraitUpsideDown:
                connection.videoOrientation = .portraitUpsideDown
            default:
                connection.videoOrientation = .portrait
            }
        }

        let boxSize = min(max(min(bounds.width, bounds.height) * 0.64, 210), 290)
        let cutoutRect = CGRect(
            x: (bounds.width - boxSize) / 2.0,
            y: (bounds.height - boxSize) / 2.0 - 18.0,
            width: boxSize,
            height: boxSize
        )

        let fullPath = UIBezierPath(rect: bounds)
        let holePath = UIBezierPath(roundedRect: cutoutRect, cornerRadius: 18.0)
        fullPath.append(holePath)
        dimMaskLayer.path = fullPath.cgPath
        reticleLayer.path = holePath.cgPath
    }

    private func makePillConfiguration(
        title: String,
        foregroundColor: UIColor,
        backgroundColor: UIColor,
        borderColor: UIColor,
        cornerRadius: CGFloat,
        fontSize: CGFloat,
        insets: NSDirectionalEdgeInsets
    ) -> UIButton.Configuration {
        var config = UIButton.Configuration.plain()
        config.title = title
        config.baseForegroundColor = foregroundColor
        config.contentInsets = insets
        config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var outgoing = incoming
            outgoing.font = UIFont.boldSystemFont(ofSize: fontSize)
            return outgoing
        }
        var bg = UIBackgroundConfiguration.clear()
        bg.backgroundColor = backgroundColor
        bg.cornerRadius = cornerRadius
        bg.strokeColor = borderColor
        bg.strokeWidth = 1.0
        config.background = bg
        return config
    }

    private func setupOverlayUI() {
        let closeButton = UIButton(
            configuration: makePillConfiguration(
                title: "Cerrar",
                foregroundColor: .white,
                backgroundColor: UIColor.black.withAlphaComponent(0.6),
                borderColor: UIColor.white.withAlphaComponent(0.2),
                cornerRadius: 16,
                fontSize: 13,
                insets: NSDirectionalEdgeInsets(top: 7, leading: 14, bottom: 7, trailing: 14)
            )
        )
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)

        let titleLabel = UILabel()
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = "Escanear QR Moto"
        titleLabel.textColor = .white
        titleLabel.font = UIFont.boldSystemFont(ofSize: 16)
        titleLabel.textAlignment = .center

        torchButton.configuration = makePillConfiguration(
            title: "Linterna",
            foregroundColor: .white,
            backgroundColor: UIColor.black.withAlphaComponent(0.6),
            borderColor: UIColor.white.withAlphaComponent(0.2),
            cornerRadius: 16,
            fontSize: 12,
            insets: NSDirectionalEdgeInsets(top: 7, leading: 12, bottom: 7, trailing: 12)
        )
        torchButton.translatesAutoresizingMaskIntoConstraints = false
        torchButton.isHidden = true
        torchButton.addTarget(self, action: #selector(torchTapped), for: .touchUpInside)

        let hintLabel = UILabel()
        hintLabel.translatesAutoresizingMaskIntoConstraints = false
        hintLabel.text = "Apunta la cámara al código QR de la pantalla TFT de tu moto"
        hintLabel.textColor = .white
        hintLabel.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        hintLabel.textAlignment = .center
        hintLabel.numberOfLines = 0

        let manualButton = UIButton(
            configuration: makePillConfiguration(
                title: "Introducir o pegar QR manualmente",
                foregroundColor: UIColor(red: 1.0, green: 0.42, blue: 0.0, alpha: 1.0),
                backgroundColor: UIColor.black.withAlphaComponent(0.72),
                borderColor: UIColor.white.withAlphaComponent(0.25),
                cornerRadius: 12,
                fontSize: 13,
                insets: NSDirectionalEdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16)
            )
        )
        manualButton.translatesAutoresizingMaskIntoConstraints = false
        manualButton.addTarget(self, action: #selector(manualTapped), for: .touchUpInside)

        view.addSubview(closeButton)
        view.addSubview(titleLabel)
        view.addSubview(torchButton)
        view.addSubview(hintLabel)
        view.addSubview(manualButton)

        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            closeButton.topAnchor.constraint(equalTo: guide.topAnchor, constant: 12),
            closeButton.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 16),

            torchButton.topAnchor.constraint(equalTo: guide.topAnchor, constant: 12),
            torchButton.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -16),

            titleLabel.centerYAnchor.constraint(equalTo: closeButton.centerYAnchor),
            titleLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),

            manualButton.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -24),
            manualButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),

            hintLabel.bottomAnchor.constraint(equalTo: manualButton.topAnchor, constant: -14),
            hintLabel.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 24),
            hintLabel.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -24),
        ])
    }

    private func configureAndStartSession() {
        guard !hasFinished else { return }

        if !isConfigured {
            captureSession.beginConfiguration()
            if captureSession.canSetSessionPreset(.high) {
                captureSession.sessionPreset = .high
            }

            guard let device = AVCaptureDevice.default(for: .video) else {
                captureSession.commitConfiguration()
                reportErrorAndDismiss("No se encontró cámara disponible en este dispositivo.")
                return
            }
            videoDevice = device

            do {
                let input = try AVCaptureDeviceInput(device: device)
                guard captureSession.canAddInput(input) else {
                    captureSession.commitConfiguration()
                    reportErrorAndDismiss("No se pudo conectar la entrada de vídeo.")
                    return
                }
                captureSession.addInput(input)

                if device.isFocusModeSupported(.continuousAutoFocus) {
                    try? device.lockForConfiguration()
                    device.focusMode = .continuousAutoFocus
                    if device.isAutoFocusRangeRestrictionSupported {
                        device.autoFocusRangeRestriction = .near
                    }
                    device.unlockForConfiguration()
                }
            } catch {
                captureSession.commitConfiguration()
                reportErrorAndDismiss("Error abriendo la cámara: \(error.localizedDescription)")
                return
            }

            let metadataOutput = AVCaptureMetadataOutput()
            guard captureSession.canAddOutput(metadataOutput) else {
                captureSession.commitConfiguration()
                reportErrorAndDismiss("No se pudo inicializar el detector de códigos QR.")
                return
            }
            captureSession.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            guard metadataOutput.availableMetadataObjectTypes.contains(.qr) else {
                captureSession.commitConfiguration()
                reportErrorAndDismiss("Este dispositivo no soporta detección de códigos QR.")
                return
            }
            metadataOutput.metadataObjectTypes = [.qr]

            captureSession.commitConfiguration()
            isConfigured = true

            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.torchButton.isHidden = !(self.videoDevice?.hasTorch ?? false)
            }
        }

        if !captureSession.isRunning {
            captureSession.startRunning()
        }
    }

    private func reportErrorAndDismiss(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, !self.hasFinished else { return }
            self.hasFinished = true
            let handler = self.onError
            self.dismiss(animated: true) {
                handler?(message)
            }
        }
    }

    private func setTorch(enabled: Bool) {
        guard isTorchOn != enabled else { return }
        guard let device = videoDevice, device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = enabled ? .on : .off
            device.unlockForConfiguration()
            isTorchOn = enabled
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                let accent = UIColor(red: 1.0, green: 0.42, blue: 0.0, alpha: 1.0)
                self.torchButton.configuration = self.makePillConfiguration(
                    title: enabled ? "Linterna ON" : "Linterna",
                    foregroundColor: .white,
                    backgroundColor: enabled ? accent : UIColor.black.withAlphaComponent(0.6),
                    borderColor: enabled ? accent : UIColor.white.withAlphaComponent(0.2),
                    cornerRadius: 16,
                    fontSize: 12,
                    insets: NSDirectionalEdgeInsets(top: 7, leading: 12, bottom: 7, trailing: 12)
                )
            }
        } catch {}
    }

    @objc private func closeTapped() {
        hasFinished = true
        setTorch(enabled: false)
        dismiss(animated: true)
    }

    @objc private func manualTapped() {
        hasFinished = true
        setTorch(enabled: false)
        let handler = onManualFallback
        dismiss(animated: true) {
            handler?()
        }
    }

    @objc private func torchTapped() {
        setTorch(enabled: !isTorchOn)
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !hasFinished else { return }
        for obj in metadataObjects {
            guard let readable = obj as? AVMetadataMachineReadableCodeObject,
                  readable.type == .qr,
                  let value = readable.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !value.isEmpty else {
                continue
            }
            hasFinished = true
            setTorch(enabled: false)
            sessionQueue.async { [weak self] in
                if self?.captureSession.isRunning == true {
                    self?.captureSession.stopRunning()
                }
            }
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            let handler = onScanned
            dismiss(animated: true) {
                handler?(value)
            }
            break
        }
    }
}

final class IosQrScannerBridgeImpl: NSObject, IosQrScannerBridge {
    func startQrScan(callback: IosQrScannerCallback) {
        DispatchQueue.main.async {
            guard let presenter = Self.topViewController() else {
                callback.onError(message: "No se pudo abrir el visor de cámara.")
                callback.onManualFallback()
                return
            }
            if presenter is QrScannerViewController {
                return
            }
            guard AVCaptureDevice.default(for: .video) != nil else {
                callback.onError(message: "Cámara no disponible en este dispositivo. Puedes introducir el QR manualmente.")
                callback.onManualFallback()
                return
            }

            let status = AVCaptureDevice.authorizationStatus(for: .video)
            switch status {
            case .authorized:
                Self.presentScanner(from: presenter, callback: callback)
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    DispatchQueue.main.async {
                        let currentPresenter = Self.topViewController() ?? presenter
                        if granted {
                            Self.presentScanner(from: currentPresenter, callback: callback)
                        } else {
                            Self.presentPermissionAlert(from: currentPresenter, callback: callback)
                        }
                    }
                }
            case .denied, .restricted:
                Self.presentPermissionAlert(from: presenter, callback: callback)
            @unknown default:
                callback.onError(message: "Permiso de cámara no disponible.")
                callback.onManualFallback()
            }
        }
    }

    func openSettings() {
        DispatchQueue.main.async {
            guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
    }

    private static func presentScanner(from presenter: UIViewController, callback: IosQrScannerCallback) {
        let vc = QrScannerViewController()
        vc.modalPresentationStyle = .fullScreen
        vc.onScanned = { rawText in
            DispatchQueue.main.async {
                callback.onQrScanned(rawText: rawText)
            }
        }
        vc.onManualFallback = {
            DispatchQueue.main.async {
                callback.onManualFallback()
            }
        }
        vc.onError = { message in
            DispatchQueue.main.async {
                callback.onError(message: message)
                callback.onManualFallback()
            }
        }
        presenter.present(vc, animated: true)
    }

    private static func presentPermissionAlert(from presenter: UIViewController, callback: IosQrScannerCallback) {
        let alert = UIAlertController(
            title: "Permiso de cámara requerido",
            message: "Activa el permiso de cámara para escanear el QR de la pantalla de tu moto o introduce el código manualmente.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Abrir Ajustes", style: .default) { _ in
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
        })
        alert.addAction(UIAlertAction(title: "Pegar manualmente", style: .default) { _ in
            callback.onManualFallback()
        })
        alert.addAction(UIAlertAction(title: "Cancelar", style: .cancel))
        presenter.present(alert, animated: true)
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
        let keyWindow = scenes
            .flatMap { $0.windows }
            .first { $0.isKeyWindow }
            ?? scenes.first?.windows.first
        var top = keyWindow?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
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
        IosQrScannerBridgeHolder.shared.bridge = IosQrScannerBridgeImpl()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
                .onOpenURL { url in
                    Self.handleIncomingGpxURL(url)
                }
        }
    }

    private static func handleIncomingGpxURL(_ url: URL) {
        guard url.isFileURL else { return }

        DispatchQueue.global(qos: .userInitiated).async {
            let maxBytes = 25 * 1024 * 1024
            let accessed = url.startAccessingSecurityScopedResource()
            defer {
                if accessed {
                    url.stopAccessingSecurityScopedResource()
                }
            }

            var coordinatorError: NSError?
            var loadedData: Data?
            var readError: Error?
            var resolvedName = url.lastPathComponent
            var isTooLarge = false

            NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinatorError) { readableURL in
                let nameCandidate = readableURL.lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
                if !nameCandidate.isEmpty {
                    resolvedName = nameCandidate
                }
                if let values = try? readableURL.resourceValues(forKeys: [.fileSizeKey]),
                   let size = values.fileSize,
                   size > maxBytes {
                    isTooLarge = true
                    return
                }
                do {
                    let data = try Data(contentsOf: readableURL)
                    if data.count > maxBytes {
                        isTooLarge = true
                    } else {
                        loadedData = data
                    }
                } catch {
                    readError = error
                }
            }

            if isTooLarge {
                DispatchQueue.main.async {
                    MainViewControllerKt.notifyExternalGpxError(message: "El archivo supera el límite de 25 MB para GPX.")
                }
                return
            }

            guard let data = loadedData else {
                let reason = (coordinatorError ?? readError)?.localizedDescription ?? "error desconocido"
                DispatchQueue.main.async {
                    MainViewControllerKt.notifyExternalGpxError(message: "No se pudo leer el archivo GPX: \(reason)")
                }
                return
            }

            // Limpiar copia temporal en Documents/Inbox generada al abrir desde WhatsApp/Mail
            let parentDir = url.deletingLastPathComponent()
            let grandParentDir = parentDir.deletingLastPathComponent()
            if parentDir.lastPathComponent == "Inbox" && grandParentDir.lastPathComponent == "Documents" {
                try? FileManager.default.removeItem(at: url)
            }

            var decoded = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) ?? ""
            if decoded.hasPrefix("\u{FEFF}") {
                decoded = String(decoded.dropFirst())
            }

            let trimmed = decoded.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else {
                DispatchQueue.main.async {
                    MainViewControllerKt.notifyExternalGpxError(message: "El archivo GPX está vacío.")
                }
                return
            }

            let hasGpxExtension = resolvedName.lowercased().hasSuffix(".gpx")
            let looksLikeGpx = decoded.range(of: "<gpx", options: .caseInsensitive) != nil
                || decoded.range(of: "<trkpt", options: .caseInsensitive) != nil
                || decoded.range(of: "<rtept", options: .caseInsensitive) != nil

            guard hasGpxExtension || looksLikeGpx else {
                DispatchQueue.main.async {
                    MainViewControllerKt.notifyExternalGpxError(message: "El archivo seleccionado no es un GPX válido.")
                }
                return
            }

            let cleanName = resolvedName.trimmingCharacters(in: .whitespacesAndNewlines)
            let safeName = cleanName.isEmpty
                ? "Ruta_Importada.gpx"
                : (cleanName.lowercased().hasSuffix(".gpx") ? cleanName : "\(cleanName).gpx")

            DispatchQueue.main.async {
                MainViewControllerKt.importExternalGpx(fileName: safeName, content: decoded)
            }
        }
    }
}
