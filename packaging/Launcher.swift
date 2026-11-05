import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var viewer: Process?
    private var log: FileHandle?
    private var logURL: URL?

    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            guard let resources = Bundle.main.resourceURL else {
                throw NSError(domain: "HarmonyCast", code: 1)
            }
            let files = FileManager.default
            let support = try files.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                        appropriateFor: nil, create: true)
                .appendingPathComponent("HarmonyCast", isDirectory: true)
            try files.createDirectory(at: support, withIntermediateDirectories: true)
            let address = support.appendingPathComponent("wireless-device.txt")
            let seed = resources.appendingPathComponent("app/wireless-device.txt")
            if !files.fileExists(atPath: address.path), files.fileExists(atPath: seed.path) {
                try files.copyItem(at: seed, to: address)
            }
            let logURL = support.appendingPathComponent("launcher.log")
            self.logURL = logURL
            files.createFile(atPath: logURL.path, contents: nil)
            log = try FileHandle(forWritingTo: logURL)
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/bin/bash")
            let connectionMode = CommandLine.arguments.contains("--usb") ? "--usb" : "--auto"
            process.arguments = [resources.appendingPathComponent("app/启动鸿蒙投屏.command").path, connectionMode]
            var environment = ProcessInfo.processInfo.environment
            environment["CAST_GUI"] = "1"
            environment["CAST_STATE_DIR"] = support.path
            environment["CAST_HDC_PATH"] = resources.appendingPathComponent("tools/hdc").path
            environment["CAST_OCR_PATH"] = resources.appendingPathComponent("tools/ScreenTextOCR").path
            environment["CAST_JAVA_PATH"] = resources.appendingPathComponent("runtime/bin/java").path
            environment["CAST_APP_ICON"] = resources.appendingPathComponent("AppIcon.icns").path
            process.environment = environment
            process.currentDirectoryURL = support
            process.standardOutput = log
            process.standardError = log
            process.standardInput = FileHandle.nullDevice
            process.terminationHandler = { [weak self] task in
                DispatchQueue.main.async {
                    try? self?.log?.close()
                    if task.terminationStatus != 0 {
                        let detail = self?.lastLogTail() ?? ""
                        let message = detail.isEmpty
                            ? "投屏未能启动。请确认手机已连接，并允许 USB 调试，然后重新打开 App。"
                            : "投屏未能启动。\n\n\(detail)"
                        self?.showError(message)
                    }
                    NSApplication.shared.terminate(nil)
                }
            }
            viewer = process
            try process.run()
        } catch {
            showError("App 未能启动，请重新打开。\n\(error.localizedDescription)")
            NSApplication.shared.terminate(nil)
        }
    }

    private func showError(_ message: String) {
        NSApplication.shared.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "鸿蒙投屏"
        alert.informativeText = message
        alert.alertStyle = .warning
        alert.addButton(withTitle: "好")
        alert.runModal()
    }

    private func lastLogTail(maxLines: Int = 10) -> String {
        guard let logURL,
              let data = try? Data(contentsOf: logURL),
              let text = String(data: data, encoding: .utf8) else {
            return ""
        }
        let lines = text.split(separator: "\n", omittingEmptySubsequences: true)
        return lines.suffix(maxLines).joined(separator: "\n")
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if let viewer, viewer.isRunning {
            NSRunningApplication(processIdentifier: viewer.processIdentifier)?.activate(options: [.activateIgnoringOtherApps])
        }
        return false
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if let viewer, viewer.isRunning { viewer.terminate() }
        return .terminateNow
    }
}

let application = NSApplication.shared
let delegate = AppDelegate()
application.delegate = delegate
application.setActivationPolicy(.accessory)
application.run()
