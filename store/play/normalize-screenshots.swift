//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

// Normalizes raw device screenshots into Play-Console-ready PNGs.
//
//   swiftc -O normalize-screenshots.swift -o /tmp/normalize
//   /tmp/normalize <out-dir> <screenshot.png> [...]
//
// Two fixes are applied:
//   * Alpha is dropped — Play wants "JPEG or 24-bit PNG (no alpha)"; Android's
//     screenshot files are 32-bit.
//   * Any frame whose long side is more than twice its short side is padded to
//     exactly 2:1 (Play rejects anything taller/wider than that). Padding
//     replicates the outermost row/column of pixels, which is invisible here
//     because the app's background is flat to the screen edge.
//
// Nothing is scaled or cropped, so the pixels of the UI stay untouched.

import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let args = CommandLine.arguments
guard args.count >= 3 else { fatalError("usage: <out-dir> <screenshot.png> [...]") }
let outDir = URL(fileURLWithPath: args[1], isDirectory: true)
try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

let cs = CGColorSpace(name: CGColorSpace.sRGB)!

for (index, path) in args.dropFirst(2).enumerated() {
    guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
    else { fatalError("cannot read \(path)") }

    let w = CGFloat(image.width), h = CGFloat(image.height)
    let targetW = max(w, ceil(h / 2))
    let targetH = max(h, ceil(w / 2))

    let ctx = CGContext(data: nil, width: Int(targetW), height: Int(targetH),
                        bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                        bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    ctx.interpolationQuality = .none
    ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: targetW, height: targetH))

    let x = ((targetW - w) / 2).rounded(.down)
    let y = ((targetH - h) / 2).rounded(.down)

    // Extend the edge pixels into the padding, so the bars match the UI.
    if x > 0 {
        let left = image.cropping(to: CGRect(x: 0, y: 0, width: 1, height: h))!
        let right = image.cropping(to: CGRect(x: w - 1, y: 0, width: 1, height: h))!
        ctx.draw(left, in: CGRect(x: 0, y: y, width: x + 1, height: h))
        ctx.draw(right, in: CGRect(x: x + w - 1, y: y, width: targetW - x - w + 1, height: h))
    }
    if y > 0 {
        // CoreGraphics origin is bottom-left; row 0 of the CGImage is the top.
        let top = image.cropping(to: CGRect(x: 0, y: 0, width: w, height: 1))!
        let bottom = image.cropping(to: CGRect(x: 0, y: h - 1, width: w, height: 1))!
        ctx.draw(top, in: CGRect(x: x, y: y + h - 1, width: w, height: targetH - y - h + 1))
        ctx.draw(bottom, in: CGRect(x: x, y: 0, width: w, height: y + 1))
    }

    ctx.draw(image, in: CGRect(x: x, y: y, width: w, height: h))

    guard let out = ctx.makeImage() else { fatalError("no image") }
    let name = String(format: "%02d-%@", index + 1,
                      URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent
                          .replacingOccurrences(of: "Screenshot_", with: "") + ".png")
    let url = outDir.appendingPathComponent(name)
    let dest = CGImageDestinationCreateWithURL(url as CFURL,
                                               UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, out, nil)
    guard CGImageDestinationFinalize(dest) else { fatalError("write failed: \(name)") }
    print("\(Int(w))x\(Int(h)) -> \(Int(targetW))x\(Int(targetH))  \(name)")
}
