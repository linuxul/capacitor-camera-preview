import Foundation
import Capacitor
import AVFoundation
import UIKit

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitor.ionicframework.com/docs/plugins/ios
 */
@objc(CameraPreview)
public class CameraPreview: CAPPlugin, CAPBridgedPlugin {

    public let identifier = "CameraPreviewPlugin"
    public let jsName = "CameraPreview"
    public let pluginMethods: [CAPPluginMethod] = [
        .promise("start", CameraPreview.start),
        .promise("stop", CameraPreview.stop),
        .promise("capture", CameraPreview.capture),
        .promise("captureSample", CameraPreview.captureSample),
        .promise("flip", CameraPreview.flip),
        .promise("getSupportedFlashModes", CameraPreview.getSupportedFlashModes),
        .promise("setFlashMode", CameraPreview.setFlashMode),
        .promise("startRecordVideo", CameraPreview.startRecordVideo),
        .promise("stopRecordVideo", CameraPreview.stopRecordVideo),
        .async("isCameraStarted", CameraPreview.isCameraStarted)
    ]

    var previewView: UIView!
    var cameraPosition = String()
    let cameraController = CameraController()
    // swiftlint:disable identifier_name
    var x: CGFloat?
    var y: CGFloat?
    // swiftlint:enable identifier_name
    var width: CGFloat?
    var height: CGFloat?
    var paddingBottom: CGFloat?
    var rotateWhenOrientationChanged: Bool?
    var toBack: Bool?
    var storeToFile: Bool?
    var enableZoom: Bool?
    var highResolutionOutput: Bool = false
    var disableAudio: Bool = false

    // Helper to get the current interface orientation via connectedScenes (iOS 15+)
    private func getInterfaceOrientation() -> UIInterfaceOrientation {
        return UIApplication.shared.connectedScenes
            .first(where: { $0 is UIWindowScene })
            .flatMap({ $0 as? UIWindowScene })?.interfaceOrientation ?? .unknown
    }

    @objc func rotated() {
        guard let previewView = self.previewView,
              let x = self.x,
              let y = self.y,
              let width = self.width,
              let height = self.height else {
            return
        }

        let adjustedHeight = self.paddingBottom != nil ? height - self.paddingBottom! : height
        let orientation = getInterfaceOrientation()

        if orientation.isLandscape {
            previewView.frame = CGRect(x: y, y: x, width: max(adjustedHeight, width), height: min(adjustedHeight, width))
            self.cameraController.previewLayer?.frame = previewView.frame
        }

        if orientation.isPortrait {
            previewView.frame = CGRect(x: x, y: y, width: min(adjustedHeight, width), height: max(adjustedHeight, width))
            self.cameraController.previewLayer?.frame = previewView.frame
        }

        cameraController.updateVideoOrientation()
    }

    // start, stop, capture, captureSample and the video methods stay synchronous. start and stop start and stop the
    // capture session, and the bridge queue runs them in the order of the calls; they hand the session work to the
    // main queue in that order. The capture methods answer from CameraController's completion handlers, which run on
    // AVFoundation's delegate queues, where the image is encoded and written.

    func start(_ call: CAPPluginCall) {
        self.cameraPosition = call.getString("position") ?? "rear"
        self.highResolutionOutput = call.getBool("enableHighResolution") ?? false
        self.cameraController.highResolutionOutput = self.highResolutionOutput

        if call.getInt("width") != nil {
            self.width = CGFloat(call.getInt("width")!)
        } else {
            self.width = UIScreen.main.bounds.size.width
        }
        if call.getInt("height") != nil {
            self.height = CGFloat(call.getInt("height")!)
        } else {
            self.height = UIScreen.main.bounds.size.height
        }
        self.x = call.getInt("x") != nil ? CGFloat(call.getInt("x")!)/UIScreen.main.scale: 0
        self.y = call.getInt("y") != nil ? CGFloat(call.getInt("y")!)/UIScreen.main.scale: 0
        if call.getInt("paddingBottom") != nil {
            self.paddingBottom = CGFloat(call.getInt("paddingBottom")!)
        }

        self.rotateWhenOrientationChanged = call.getBool("rotateWhenOrientationChanged") ?? true
        self.toBack = call.getBool("toBack") ?? false
        self.storeToFile = call.getBool("storeToFile") ?? false
        self.enableZoom = call.getBool("enableZoom") ?? false
        self.disableAudio = call.getBool("disableAudio") ?? false

        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard let self = self else { return }
            if !granted {
                call.reject("Camera permission denied")
                return
            }

            DispatchQueue.main.async {
                if self.cameraController.captureSession?.isRunning ?? false {
                    call.reject("camera already started")
                    return
                } 
                self.cameraController.prepare(cameraPosition: self.cameraPosition, disableAudio: self.disableAudio) {error in
                    if let error = error {
                        print(error)
                        call.reject(error.localizedDescription)
                        return
                    }
                    guard let height = self.height, let width = self.width else {
                        call.reject("Invalid dimensions")
                        return
                    }

                    let adjustedHeight = self.paddingBottom != nil ? height - self.paddingBottom! : height
                    self.previewView = UIView(frame: CGRect(x: self.x ?? 0, y: self.y ?? 0, width: width, height: adjustedHeight))
                    self.webView?.isOpaque = false
                    self.webView?.backgroundColor = UIColor.clear
                    self.webView?.scrollView.backgroundColor = UIColor.clear
                    self.webView?.superview?.addSubview(self.previewView)
                    if let toBack = self.toBack, toBack {
                        self.webView?.superview?.bringSubviewToFront(self.webView!)
                    }
                    try? self.cameraController.displayPreview(on: self.previewView)

                    let frontView = (self.toBack ?? false) ? self.webView : self.previewView
                    self.cameraController.setupGestures(target: frontView ?? self.previewView, enableZoom: self.enableZoom ?? false)

                    if self.rotateWhenOrientationChanged == true {
                        NotificationCenter.default.addObserver(self, selector: #selector(CameraPreview.rotated), name: UIDevice.orientationDidChangeNotification, object: nil)
                    }

                    call.resolve()
                }
            }
        }
    }

    func flip(_ call: CAPPluginCall) throws {
        do {
            try self.cameraController.switchCameras()
        } catch {
            throw CAPPluginError("failed to flip camera")
        }
        call.resolve()
    }

    func stop(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if self.cameraController.captureSession?.isRunning ?? false {
                self.cameraController.captureSession?.stopRunning()

                // Remove the orientation observer to prevent crashes
                if self.rotateWhenOrientationChanged == true {
                    NotificationCenter.default.removeObserver(self, name: UIDevice.orientationDidChangeNotification, object: nil)
                }

                if let previewView = self.previewView {
                    previewView.removeFromSuperview()
                    self.previewView = nil
                }
                self.webView?.isOpaque = true
                call.resolve()
            } else {
                call.reject("camera already stopped")
            }
        }
    }
    // Get user's cache directory path
    @objc func getTempFilePath() -> URL {
        let path = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let identifier = UUID()
        let randomIdentifier = identifier.uuidString.replacingOccurrences(of: "-", with: "")
        let finalIdentifier = String(randomIdentifier.prefix(8))
        let fileName = "cpcp_capture_" + finalIdentifier + ".jpg"

        return path.appendingPathComponent(fileName)
    }

    func capture(_ call: CAPPluginCall) {
        DispatchQueue.main.async {

            let quality: Int? = call.getInt("quality", 85)

            self.cameraController.captureImage { [weak self] (image, error) in
                guard let self = self else { return }
                
                if let error = error {
                    call.reject(error.localizedDescription)
                    return
                }

                guard let image = image else {
                    call.reject("Image capture failed: no data received")
                    return
                }
                let imageData: Data?
                if self.cameraController.currentCameraPosition == .front {
                    let flippedImage = image.withHorizontallyFlippedOrientation()
                    imageData = flippedImage.jpegData(compressionQuality: CGFloat(quality!/100))
                } else {
                    imageData = image.jpegData(compressionQuality: CGFloat(quality!/100))
                }

                if self.storeToFile == false {
                    let imageBase64 = imageData?.base64EncodedString()
                    call.resolve(["value": imageBase64!])
                } else {
                    do {
                        let fileUrl = self.getTempFilePath()
                        try imageData?.write(to: fileUrl)
                        call.resolve(["value": fileUrl.absoluteString])
                    } catch {
                        call.reject("error writing image to file")
                    }
                }
            }
        }
    }

    func captureSample(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let quality = call.getInt("quality", 85)

            self.cameraController.captureSample { image, error in
                guard let image = image else {
                    print("Image capture error: \(String(describing: error))")
                    call.reject("Image capture error: \(String(describing: error))")
                    return
                }

                let imageData: Data?
                let compression = CGFloat(quality) / 100.0

                if self.cameraPosition == "front" {
                    let flippedImage = image.withHorizontallyFlippedOrientation()
                    imageData = flippedImage.jpegData(compressionQuality: compression)
                } else {
                    imageData = image.jpegData(compressionQuality: compression)
                }

                if self.storeToFile == false {
                    let imageBase64 = imageData?.base64EncodedString()
                    call.resolve(["value": imageBase64!])
                } else {
                    do {
                        let fileUrl = self.getTempFilePath()
                        try imageData?.write(to: fileUrl)
                        call.resolve(["value": fileUrl.absoluteString])
                    } catch {
                        call.reject("Error writing image to file")
                    }
                }
            }
        }
    }

    func getSupportedFlashModes(_ call: CAPPluginCall) throws {
        let supportedFlashModes: [String]
        do {
            supportedFlashModes = try self.cameraController.getSupportedFlashModes()
        } catch {
            throw CAPPluginError("failed to get supported flash modes")
        }
        call.resolve(["result": supportedFlashModes])
    }

    func setFlashMode(_ call: CAPPluginCall) throws {
        guard let flashMode = call.getString("flashMode") else {
            throw CAPPluginError("flashMode parameter is required")
        }
        do {
            var flashModeAsEnum: AVCaptureDevice.FlashMode?
            switch flashMode {
            case "off":
                flashModeAsEnum = .off
            case "on":
                flashModeAsEnum = .on
            case "auto":
                flashModeAsEnum = .auto
            default: break
            }

            if let mode = flashModeAsEnum {
                try self.cameraController.setFlashMode(flashMode: mode)
            } else if flashMode == "torch" {
                try self.cameraController.setTorchMode()
            } else {
                call.reject("Flash Mode not supported")
                return
            }
            call.resolve()
        } catch {
            call.reject("failed to set flash mode")
        }
    }

    func startRecordVideo(_ call: CAPPluginCall) {
        DispatchQueue.main.async {

            let quality: Int? = call.getInt("quality", 85)

            self.cameraController.captureVideo { (image, error) in

                guard let image = image else {
                    print(error ?? "Image capture error")
                    guard let error = error else {
                        call.reject("Image capture error")
                        return
                    }
                    call.reject(error.localizedDescription)
                    return
                }

                // self.videoUrl = image

                call.resolve(["value": image.absoluteString])
            }
        }
    }

    func stopRecordVideo(_ call: CAPPluginCall) {

        self.cameraController.stopRecording { (_) in

        }
    }

    /// Whether the capture session runs. start and stop change the session on the main queue: the method reads it on
    /// the main actor.
    @MainActor
    func isCameraStarted(_ call: CAPPluginCall) async -> JSObject {
        return ["value": self.cameraController.captureSession?.isRunning ?? false]
    }
}
