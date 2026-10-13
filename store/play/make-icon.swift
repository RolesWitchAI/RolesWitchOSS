//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// usage: makeicon <src.png> <out.png> <zoom> <size>
let args = CommandLine.arguments
let srcPath = args[1], outPath = args[2]
let zoom = Double(args[3])!, size = Int(args[4])!

guard let imageSource = CGImageSourceCreateWithURL(URL(fileURLWithPath: srcPath) as CFURL, nil),
      let src = CGImageSourceCreateImageAtIndex(imageSource, 0, nil)
else { fatalError("cannot read \(srcPath)") }

// report alpha stats of the source
do {
    let w = src.width, h = src.height
    var buf = [UInt8](repeating: 0, count: w * h * 4)
    let cs = CGColorSpace(name: CGColorSpace.sRGB)!
    buf.withUnsafeMutableBytes { raw in
        let ctx = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8,
                            bytesPerRow: w * 4, space: cs,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(src, in: CGRect(x: 0, y: 0, width: w, height: h))
    }
    var minA: UInt8 = 255
    for i in stride(from: 3, to: buf.count, by: 4) { minA = min(minA, buf[i]) }
    FileHandle.standardError.write("source \(w)x\(h) minAlpha=\(minA)\n".data(using: .utf8)!)
}

let cs = CGColorSpace(name: CGColorSpace.sRGB)!
// opaque RGB context (no alpha channel in output)
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
                    bytesPerRow: 0, space: cs,
                    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
ctx.interpolationQuality = .high
// backdrop matching the artwork's light background, so any translucent edge stays light
ctx.setFillColor(red: 0.965, green: 0.973, blue: 0.980, alpha: 1)
ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))

let side = Double(size) * zoom
let origin = (Double(size) - side) / 2
ctx.draw(src, in: CGRect(x: origin, y: origin, width: side, height: side))

guard let out = ctx.makeImage() else { fatalError("no image") }
let url = URL(fileURLWithPath: outPath) as CFURL
let dest = CGImageDestinationCreateWithURL(url, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, out, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("write failed") }
print("wrote \(outPath)")
