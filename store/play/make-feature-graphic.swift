//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

// Builds the 1024x500 Google Play feature graphic from the app icon and two
// phone screenshots.
//
//   swiftc -O make-feature-graphic.swift -o /tmp/make-feature
//   /tmp/make-feature <icon.png> <chat.png> <call.png> <out.png>
//
// Layout keeps all text and both devices inside the middle 80% of the canvas,
// so nothing important is lost where Play crops the graphic.

import Foundation
import CoreGraphics
import CoreText
import ImageIO
import UniformTypeIdentifiers

let args = CommandLine.arguments
guard args.count == 5 else { fatalError("usage: <icon> <chat> <call> <out>") }
let iconPath = args[1], chatPath = args[2], callPath = args[3], outPath = args[4]

let W = 1024.0, H = 500.0

func loadImage(_ path: String) -> CGImage {
    guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
          let img = CGImageSourceCreateImageAtIndex(src, 0, nil)
    else { fatalError("cannot read \(path)") }
    return img
}

// Roboto ships with Android Studio (Apache 2.0) and matches the app's UI type.
let robotoFlex = "/Applications/Android Studio.app/Contents/plugins/design-tools/resources/layoutlib/data/fonts/RobotoFlex-Regular.ttf"
let robotoRegular = "/Applications/Android Studio.app/Contents/plugins/design-tools/resources/layoutlib/data/fonts/Roboto-Regular.ttf"

func registerFont(_ path: String) -> String? {
    let url = URL(fileURLWithPath: path) as CFURL
    guard FileManager.default.fileExists(atPath: path) else { return nil }
    CTFontManagerRegisterFontsForURL(url, .process, nil)
    guard let ds = CTFontManagerCreateFontDescriptorsFromURL(url) as? [CTFontDescriptor],
          let first = ds.first,
          let name = CTFontDescriptorCopyAttribute(first, kCTFontNameAttribute) as? String
    else { return nil }
    return name
}

let flexName = registerFont(robotoFlex)
let regularName = registerFont(robotoRegular)

/// Roboto at `size`; `weight` uses the variable font's `wght` axis when available.
func font(_ size: CGFloat, weight: CGFloat) -> CTFont {
    if weight > 500, let flexName {
        let base = CTFontCreateWithName(flexName as CFString, size, nil)
        let axes = (CTFontCopyVariationAxes(base) as? [[String: Any]]) ?? []
        for axis in axes {
            guard let id = axis[kCTFontVariationAxisIdentifierKey as String] as? Int,
                  let name = axis[kCTFontVariationAxisNameKey as String] as? String,
                  name.lowercased().contains("weight") else { continue }
            let desc = CTFontDescriptorCreateWithAttributes([
                kCTFontVariationAttribute: [id: weight]
            ] as CFDictionary)
            return CTFontCreateCopyWithAttributes(base, size, nil, desc)
        }
        return base
    }
    if let regularName { return CTFontCreateWithName(regularName as CFString, size, nil) }
    return CTFontCreateWithName((weight > 500 ? "HelveticaNeue-Bold" : "HelveticaNeue") as CFString, size, nil)
}

func draw(_ text: String, at point: CGPoint, font f: CTFont, color: CGColor, in ctx: CGContext) {
    let attributed = CFAttributedStringCreate(nil, text as CFString, [
        kCTFontAttributeName: f,
        kCTForegroundColorAttributeName: color,
        kCTKernAttributeName: -0.5,
    ] as CFDictionary)!
    let line = CTLineCreateWithAttributedString(attributed)
    ctx.textPosition = point
    CTLineDraw(line, ctx)
}

func roundedPath(_ rect: CGRect, _ radius: CGFloat) -> CGPath {
    CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)
}

let cs = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: Int(W), height: Int(H), bitsPerComponent: 8,
                    bytesPerRow: 0, space: cs,
                    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
ctx.interpolationQuality = .high
ctx.setAllowsAntialiasing(true)

// --- background: soft diagonal wash in the app's lavender, plus pastel blobs
// picked from the character's hair (blue / pink / yellow).
let bg = CGGradient(colorsSpace: cs, colors: [
    CGColor(red: 0.988, green: 0.984, blue: 1.0, alpha: 1),
    CGColor(red: 0.878, green: 0.906, blue: 0.973, alpha: 1),
] as CFArray, locations: [0, 1])!
ctx.drawLinearGradient(bg, start: CGPoint(x: 0, y: H), end: CGPoint(x: W, y: 0), options: [])

func blob(_ center: CGPoint, radius: CGFloat, color: (CGFloat, CGFloat, CGFloat), alpha: CGFloat) {
    let g = CGGradient(colorsSpace: cs, colors: [
        CGColor(red: color.0, green: color.1, blue: color.2, alpha: alpha),
        CGColor(red: color.0, green: color.1, blue: color.2, alpha: 0),
    ] as CFArray, locations: [0, 1])!
    ctx.drawRadialGradient(g, startCenter: center, startRadius: 0,
                           endCenter: center, endRadius: radius, options: [])
}
blob(CGPoint(x: 120, y: 90), radius: 340, color: (1.0, 0.78, 0.87), alpha: 0.55)   // pink
blob(CGPoint(x: 660, y: 470), radius: 380, color: (0.66, 0.83, 0.98), alpha: 0.50) // blue
blob(CGPoint(x: 300, y: 470), radius: 260, color: (1.0, 0.95, 0.75), alpha: 0.45)  // yellow

// --- left column: icon, wordmark, tagline
let icon = loadImage(iconPath)
let iconRect = CGRect(x: 96, y: 318, width: 104, height: 104)
ctx.saveGState()
ctx.setShadow(offset: CGSize(width: 0, height: -8), blur: 20,
              color: CGColor(red: 0.24, green: 0.26, blue: 0.38, alpha: 0.28))
ctx.beginPath()
ctx.addPath(roundedPath(iconRect, 24))
ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
ctx.fillPath()
ctx.restoreGState()
ctx.saveGState()
ctx.addPath(roundedPath(iconRect, 24))
ctx.clip()
ctx.draw(icon, in: iconRect)
ctx.restoreGState()

let ink = CGColor(red: 0.165, green: 0.180, blue: 0.255, alpha: 1)
let inkSoft = CGColor(red: 0.325, green: 0.353, blue: 0.451, alpha: 1)

draw("Roles Witch", at: CGPoint(x: 96, y: 232), font: font(66, weight: 700), color: ink, in: ctx)
draw("Voice-first AI assistant that talks to", at: CGPoint(x: 98, y: 176),
     font: font(27, weight: 400), color: inkSoft, in: ctx)
draw("your own OpenAI-compatible server", at: CGPoint(x: 98, y: 138),
     font: font(27, weight: 400), color: inkSoft, in: ctx)

// --- right side: two phones, back one tilted away, front one overlapping it
func phone(_ image: CGImage, center: CGPoint, height: CGFloat, rotation: CGFloat) {
    let aspect = CGFloat(image.width) / CGFloat(image.height)
    let width = height * aspect
    let bezel: CGFloat = 5
    let body = CGRect(x: -width / 2 - bezel, y: -height / 2 - bezel,
                      width: width + bezel * 2, height: height + bezel * 2)
    let screen = CGRect(x: -width / 2, y: -height / 2, width: width, height: height)

    ctx.saveGState()
    ctx.translateBy(x: center.x, y: center.y)
    ctx.rotate(by: rotation * .pi / 180)

    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -10), blur: 26,
                  color: CGColor(red: 0.20, green: 0.23, blue: 0.35, alpha: 0.35))
    ctx.beginPath()
    ctx.addPath(roundedPath(body, 26))
    ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    ctx.fillPath()
    ctx.restoreGState()

    ctx.saveGState()
    ctx.addPath(roundedPath(screen, 21))
    ctx.clip()
    ctx.draw(image, in: screen)
    ctx.restoreGState()

    ctx.restoreGState()
}

phone(loadImage(callPath), center: CGPoint(x: 860, y: 258), height: 372, rotation: -7)
phone(loadImage(chatPath), center: CGPoint(x: 690, y: 246), height: 410, rotation: 5)

guard let out = ctx.makeImage() else { fatalError("no image") }
let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: outPath) as CFURL,
                                           UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, out, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("write failed") }
print("wrote \(outPath)")
