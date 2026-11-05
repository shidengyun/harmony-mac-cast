import AppKit

let folder = CommandLine.arguments[1]
try FileManager.default.createDirectory(atPath: folder, withIntermediateDirectories: true)
for size in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let pixels = size * scale
        let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels,
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
            colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
        let context = NSGraphicsContext.current!.cgContext
        context.scaleBy(x: CGFloat(pixels) / 1024, y: CGFloat(pixels) / 1024)
        NSColor(calibratedRed: 0.94, green: 0.96, blue: 0.99, alpha: 1).setFill()
        NSBezierPath(roundedRect: NSRect(x: 72, y: 72, width: 880, height: 880), xRadius: 195, yRadius: 195).fill()
        NSColor(calibratedRed: 0.26, green: 0.40, blue: 0.83, alpha: 1).setFill()
        NSBezierPath(roundedRect: NSRect(x: 130, y: 130, width: 764, height: 764), xRadius: 155, yRadius: 155).fill()
        NSColor.white.setStroke()
        let phone = NSBezierPath(roundedRect: NSRect(x: 288, y: 242, width: 316, height: 540), xRadius: 66, yRadius: 66)
        phone.lineWidth = 34
        phone.stroke()
        let home = NSBezierPath()
        home.move(to: NSPoint(x: 392, y: 304)); home.line(to: NSPoint(x: 500, y: 304))
        home.lineWidth = 25; home.lineCapStyle = .round; home.stroke()
        let connected = NSBezierPath()
        connected.lineWidth = 30; connected.lineCapStyle = .round
        connected.move(to: NSPoint(x: 668, y: 500)); connected.line(to: NSPoint(x: 702, y: 534))
        connected.line(to: NSPoint(x: 752, y: 595)); connected.stroke()
        NSGraphicsContext.restoreGraphicsState()
        let suffix = scale == 2 ? "@2x" : ""
        let url = URL(fileURLWithPath: folder).appendingPathComponent("icon_\(size)x\(size)\(suffix).png")
        try bitmap.representation(using: .png, properties: [:])!.write(to: url)
    }
}
