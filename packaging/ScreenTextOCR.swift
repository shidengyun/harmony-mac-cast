import Foundation
import Vision
import ImageIO
import Darwin

// Foundation normalizes /private/tmp back to /tmp; older Intel Leptonica needs the physical path.
func physicalPath(_ path: String) -> String {
    guard let resolved = path.withCString({ realpath($0, nil) }) else { return path }
    defer { free(resolved) }
    return String(cString: resolved)
}

func recognizeWithTesseract(_ image: String) throws -> String? {
    let adjacent = URL(fileURLWithPath: physicalPath(CommandLine.arguments[0])).deletingLastPathComponent()
        .appendingPathComponent("ocr/tesseract")
    let environment = ProcessInfo.processInfo.environment
    let configured = environment["CAST_TESSERACT_PATH"].map { [$0] } ?? []
    let searchPath = (environment["PATH"] ?? "").split(separator: ":")
        .map { URL(fileURLWithPath: String($0)).appendingPathComponent("tesseract").path }
    let candidates = configured + [adjacent.path] + searchPath
    guard let binary = candidates.first(where: { path in
        guard FileManager.default.isExecutableFile(atPath: path) else { return false }
        if path == adjacent.path,
           let data = try? Data(contentsOf: adjacent.deletingLastPathComponent().appendingPathComponent("metadata.json")),
           let info = try? JSONSerialization.jsonObject(with: data) as? [String: String],
           let version = info["minimum_macos"] {
            let required = version.split(separator: ".").compactMap { Int($0) }
            let os = ProcessInfo.processInfo.operatingSystemVersion
            if [os.majorVersion, os.minorVersion, os.patchVersion].lexicographicallyPrecedes(required) {
                return false
            }
        }
        return true
    }) else {
        return nil
    }
    let process = Process()
    process.executableURL = URL(fileURLWithPath: physicalPath(binary))
    let imagePath = physicalPath(image)
    var arguments = [imagePath, "stdout", "-l", "chi_sim+chi_tra+eng"]
    if binary == adjacent.path {
        arguments += ["--tessdata-dir", physicalPath(adjacent.deletingLastPathComponent().appendingPathComponent("tessdata").path)]
    } else if let tessdata = environment["CAST_TESSDATA_PATH"] {
        arguments += ["--tessdata-dir", physicalPath(tessdata)]
    }
    process.arguments = arguments
    let output = Pipe(), errors = Pipe()
    process.standardOutput = output
    process.standardError = errors
    try process.run()
    let data = output.fileHandleForReading.readDataToEndOfFile()
    process.waitUntilExit()
    if process.terminationStatus != 0 {
        let detail = String(data: errors.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        throw NSError(domain: "ScreenTextOCR", code: 2,
                      userInfo: [NSLocalizedDescriptionKey: detail.trimmingCharacters(in: .whitespacesAndNewlines)])
    }
    return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
}

// Local image recognition. The Java viewer supplies a private temporary snapshot.
do {
    guard CommandLine.arguments.count == 2 else {
        throw NSError(domain: "ScreenTextOCR", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "需要指定画面文件"])
    }
    if let text = try recognizeWithTesseract(CommandLine.arguments[1]) {
        let json = try JSONSerialization.data(withJSONObject: ["text": text], options: [.sortedKeys])
        FileHandle.standardOutput.write(json)
        exit(0)
    }
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = true
    let supported = try request.supportedRecognitionLanguages()
    request.recognitionLanguages = ["zh-Hans", "zh-Hant", "en-US"].filter { supported.contains($0) }
    let handler = VNImageRequestHandler(url: URL(fileURLWithPath: CommandLine.arguments[1]), options: [:])
    try handler.perform([request])
    let observations = (request.results ?? []).sorted {
        // Quantized rows give a stable reading order without a non-transitive comparator.
        let rowA = Int((1 - $0.boundingBox.midY) * 100)
        let rowB = Int((1 - $1.boundingBox.midY) * 100)
        return rowA == rowB ? $0.boundingBox.minX < $1.boundingBox.minX : rowA < rowB
    }
    let text = observations.compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
    let json = try JSONSerialization.data(withJSONObject: ["text": text], options: [.sortedKeys])
    FileHandle.standardOutput.write(json)
} catch {
    FileHandle.standardError.write(Data("画面文字识别失败：\(error.localizedDescription)\n".utf8))
    exit(1)
}
