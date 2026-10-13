//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import AVFoundation
import UIKit
import SwiftUI
import Combine

/// iOS video-call camera, the counterpart of Android's `CameraManager`.
///
/// Back camera by default (matches Android). `capture()` produces a JPEG `Data`
/// (longest edge ≤ 1024 px, quality 0.8), mirroring the selfie for the front
/// camera. The result is handed straight to `LLMService.sendMessage(images:)`.
///
/// Frames come from an `AVCaptureVideoDataOutput` tapped off the running
/// preview stream, the way Android takes its turn image from the bound camera:
/// a still capture (`AVCapturePhotoOutput`) plays the system shutter sound on
/// every turn, and pays full-sensor resolution for an image that is downscaled
/// to 1024 px anyway. A pending `capture()` simply takes the next frame the
/// delegate receives — while no turn is waiting, frames are dropped untouched.
///
/// `AVCaptureSession` work runs on a private serial queue; the only UI-facing
/// state (`isReady`) is published on the main actor. `ObservableObject` without
/// `@MainActor` keeps the sample-buffer delegate callable from the session queue.
///
/// Two things this must get right that are easy to miss:
///  - the capture session must **not** touch the app's `AVAudioSession`
///    (`automaticallyConfiguresApplicationAudioSession = false`), or starting video
///    reconfigures the session out from under STT/TTS and breaks the turn loop;
///  - `stop()` must detach the device input, not just stop the session, or the
///    camera-in-use indicator stays lit after the video call ends.
final class CameraController: NSObject, ObservableObject {

    /// Drives the preview layer's connection while session setup happens async.
    @Published var isReady = false

    let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "camera.session")
    private let videoOutput = AVCaptureVideoDataOutput()
    private var position: AVCaptureDevice.Position = .back

    /// Rasterizes the one frame per turn. `CIContext` is expensive to create,
    /// so it is made once and touched only on `sessionQueue`.
    private lazy var ciContext = CIContext()

    /// Touched only on `sessionQueue` (set in `beginCapture`, cleared in `resume`),
    /// so the delegate and the watchdog can never double-resume it.
    private var continuation: CheckedContinuation<Data?, Never>?

    func start() {
        sessionQueue.async { [weak self] in self?.configure() }
    }

    /// Stops the session **and** releases the camera device. Called when the video
    /// overlay closes and when the call ends.
    func stop() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            // The data output keeps its sample-buffer delegate, so leaving it set
            // here would chain controller → session → output → controller past
            // the call. `configure()` re-attaches it on the next `start()`.
            self.videoOutput.setSampleBufferDelegate(nil, queue: nil)
            if self.session.isRunning { self.session.stopRunning() }
            self.session.beginConfiguration()
            self.session.inputs.forEach { self.session.removeInput($0) }
            self.session.commitConfiguration()
            // Anything still awaiting a frame will never get one now.
            self.resume(with: nil)
            Task { @MainActor in self.isReady = false }
        }
    }

    func toggleCamera() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.position = (self.position == .back) ? .front : .back
            self.configure()
        }
    }

    /// Returns the next frame off the preview stream as JPEG `Data` — `nil` on
    /// any failure (the caller proceeds with no image rather than blocking the
    /// turn) — and always returns: a stalled capture would otherwise wedge the
    /// turn loop with `isProcessing == true`. No still is taken, so there is no
    /// shutter: the frame flowing to the preview is sent as-is.
    func capture() async -> Data? {
        await withCheckedContinuation { (cont: CheckedContinuation<Data?, Never>) in
            beginCapture(cont)
        }
    }

    /// Split out of `capture()` so the session-queue hop happens in a synchronous
    /// context: capturing `self` in a `@Sendable` closure from an `async` function
    /// is a concurrency-checking warning, and this controller is not `Sendable`.
    private func beginCapture(_ cont: CheckedContinuation<Data?, Never>) {
        sessionQueue.async { [weak self] in
            guard let self else { cont.resume(returning: nil); return }
            // An overlapping capture would strand the previous continuation.
            self.resume(with: nil)
            self.continuation = cont

            guard self.session.isRunning,
                  let connection = self.videoOutput.connection(with: .video),
                  connection.isActive, connection.isEnabled else {
                self.resume(with: nil)
                return
            }

            // The next frame the delegate receives fulfils the continuation. If
            // none does (session interrupted, device lost), the watchdog must —
            // never let the awaiting turn hang.
            self.sessionQueue.asyncAfter(deadline: .now() + Self.captureTimeout) { [weak self] in
                self?.resume(with: nil)
            }
        }
    }

    /// Resume-once. Always called on `sessionQueue`.
    private func resume(with data: Data?) {
        guard let continuation else { return }
        self.continuation = nil
        continuation.resume(returning: data)
    }

    private static let captureTimeout: TimeInterval = 3.0

    // MARK: - Session configuration

    private func configure() {
        // Must be set before the session starts, otherwise AVFoundation takes over
        // the app's audio session and clobbers the call's `.playAndRecord` setup.
        session.automaticallyConfiguresApplicationAudioSession = false

        session.beginConfiguration()
        // A video preset, not `.photo`: the data output delivers 1080p frames that
        // downscale to the 1024-px cap directly, instead of sensor-sized buffers
        // that would only be shrunk again — and 1080p is what Android's preview
        // streams to its turn capture too.
        session.sessionPreset = .hd1920x1080

        session.inputs.forEach { session.removeInput($0) }

        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera],
            mediaType: .video,
            position: position
        )
        guard let device = discovery.devices.first,
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            session.commitConfiguration()
            Task { @MainActor in self.isReady = false }
            return
        }
        session.addInput(input)

        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        ]
        if session.outputs.isEmpty, session.canAddOutput(videoOutput) {
            session.addOutput(videoOutput)
        }
        videoOutput.setSampleBufferDelegate(self, queue: sessionQueue)
        if let conn = videoOutput.connection(with: .video) {
            // 90° is the `videoRotationAngle` equivalent of the deprecated
            // `.videoOrientation = .portrait`; on a data output it rotates the
            // delivered buffers, so the pixel buffers arrive portrait.
            if conn.isVideoRotationAngleSupported(90) {
                conn.videoRotationAngle = 90
            }
            // Mirroring stays in software (`process`): the preview layer mirrors
            // the selfie on its own, and letting the connection mirror too would
            // hand the model an un-mirrored frame behind a mirrored preview.
            if conn.isVideoMirroringSupported {
                conn.automaticallyAdjustsVideoMirroring = false
                conn.isVideoMirrored = false
            }
        }

        session.commitConfiguration()
        if !session.isRunning { session.startRunning() }

        Task { @MainActor in self.isReady = true }
    }

    /// Mirrors Android's `CameraManager.capture`: downscale so the longest edge
    /// ≤ 1024 px and JPEG-encode at 0.8, flipping horizontally for the selfie cam.
    private static func process(image: UIImage, mirror: Bool) -> Data? {
        let maxDim: CGFloat = 1024
        let scale = min(maxDim / image.size.width, maxDim / image.size.height, 1)
        let target = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        let rendered = renderer.image { ctx in
            let cg = ctx.cgContext
            if mirror {
                cg.translateBy(x: target.width, y: 0)
                cg.scaleBy(x: -1, y: 1)
            }
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        return rendered.jpegData(compressionQuality: 0.8)
    }

    /// Pixel buffer → `UIImage` on `sessionQueue`; the shared downscale/mirror/JPEG
    /// pass does the rest. The buffers arrive already rotated to portrait by the
    /// connection, so no orientation fix-up is needed here.
    private func process(buffer sampleBuffer: CMSampleBuffer, mirror: Bool) -> Data? {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        guard let cgImage = ciContext.createCGImage(ciImage, from: ciImage.extent) else { return nil }
        return CameraController.process(image: UIImage(cgImage: cgImage), mirror: mirror)
    }
}

extension CameraController: AVCaptureVideoDataOutputSampleBufferDelegate {
    /// Called on `sessionQueue` for every frame. Work happens only when a
    /// `capture()` is pending; with `alwaysDiscardsLateVideoFrames` left on, the
    /// one conversion per turn merely skips a few preview frames behind it.
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard continuation != nil else { return }
        let data = process(buffer: sampleBuffer, mirror: (position == .front))
        resume(with: data)
    }
}

/// SwiftUI preview surface for the camera session.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.videoPreviewLayer.session = session
        v.videoPreviewLayer.videoGravity = .resizeAspectFill
        v.backgroundColor = .black
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}
