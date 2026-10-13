//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import UIKit

/// Presents a 1:1 square avatar cropper over a full-screen black backdrop. The
/// user pans/drags the image and pinches to zoom within a square mask; the chosen
/// region is rendered to a square `UIImage` via `CGImage.cropping(to:)`. Mirrors
/// Android's `ThreadProfileScreen` cropper (`fixAspectRatio = 1:1`).
///
/// All geometry lives in the `GeometryReader`'s coordinate space — the same space
/// the image is laid out in — so what the square shows is exactly what `crop()`
/// saves. The display pipeline is: fit the whole image into the container
/// (`.scaledToFit`), multiply by `scale` about the center, pan by `offset`;
/// `crop()` runs that pipeline backwards. Zooming in must therefore *shrink* the
/// image-space crop side (`side / (fit · scale)`), and the pan is clamped so the
/// image always covers the square — a drag can never select black backdrop.
///
/// The image is first redrawn orientation-`.up` (and capped at a 2048px working
/// copy): `UIImage.cgImage` is the raw encoded buffer and ignores EXIF
/// orientation, while the displayed `Image` applies it — cropping `cgImage`
/// directly grabs a sideways slice of camera photos.
///
/// `onCrop` receives a square, upright `UIImage`; `onCancel` dismisses without a
/// selection. The caller is responsible for JPEG-compressing the result (e.g.
/// `AppViewModel.encodeImage(_, maxDimension: 512, quality: 0.85)`).
struct ImageCropView: View {
    let image: UIImage
    let onCrop: (UIImage) -> Void
    let onCancel: () -> Void

    /// Orientation-`.up`, scale-1 working copy of `image`, built once on appear.
    @State private var upright: UIImage?
    /// The container size the display math ran in, captured for `crop()`.
    @State private var containerSize: CGSize = .zero
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    @State private var seeded = false

    var body: some View {
        GeometryReader { geo in
            let container = geo.size
            let side = min(container.width, container.height)
            let source = upright ?? image
            // `.scaledToFit()` ratio: the whole image is visible at scale 1.
            let fit = source.size.width > 0 && source.size.height > 0
                ? min(container.width / source.size.width, container.height / source.size.height)
                : 1
            let baseWidth = source.size.width * fit
            let baseHeight = source.size.height * fit
            // Zoom-out floor: the smallest scale at which the image still covers
            // the square (1 for square-ish images, larger when the fitted image
            // is letterboxed), so the mask never shows backdrop.
            let minScale = max(1, side / min(baseWidth, baseHeight))
            let maxOffsets = Self.maxOffsets(
                baseWidth: baseWidth, baseHeight: baseHeight,
                squareSide: side, scale: scale)

            ZStack {
                Color.black.ignoresSafeArea()

                Image(uiImage: source)
                    .resizable()
                    .frame(width: baseWidth * scale, height: baseHeight * scale)
                    .offset(offset)
                    .frame(width: container.width, height: container.height)
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture()
                            .onChanged { value in
                                offset = Self.clamped(
                                    CGSize(
                                        width: lastOffset.width + value.translation.width,
                                        height: lastOffset.height + value.translation.height),
                                    maxOffsets: maxOffsets)
                            }
                            .onEnded { _ in lastOffset = offset }
                    )
                    .gesture(
                        MagnificationGesture()
                            .onChanged { value in
                                scale = max(minScale, min(lastScale * value, 6))
                                // Growing zoom only widens the coverage, but zooming
                                // back toward `minScale` can invalidate the pan.
                                offset = Self.clamped(
                                    lastOffset,
                                    maxOffsets: Self.maxOffsets(
                                        baseWidth: baseWidth, baseHeight: baseHeight,
                                        squareSide: side, scale: scale))
                            }
                            .onEnded { _ in
                                lastScale = scale
                                lastOffset = offset
                            }
                    )

                // Square mask: dim everything outside the central square.
                Rectangle()
                    .fill(Color.black.opacity(0.55))
                    .mask(
                        Rectangle()
                            .fill(Color.black.opacity(0))
                            .overlay(
                                Rectangle()
                                    .frame(width: side, height: side)
                                    .blendMode(.destinationOut)
                            )
                            .compositingGroup()
                    )
                    .allowsHitTesting(false)

                Rectangle()
                    .strokeBorder(Color.white.opacity(0.9), lineWidth: 1)
                    .frame(width: side, height: side)
                    .allowsHitTesting(false)
            }
            .onAppear {
                guard !seeded else { return }
                seeded = true
                containerSize = container
                upright = Self.normalizedUpright(image)
                // Start just covering the square (matters only for letterboxed
                // aspects, where the fitted image is smaller than the mask).
                scale = minScale
                lastScale = scale
            }
            .onChange(of: geo.size) { _, newSize in
                containerSize = newSize
            }
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(role: .close) { onCancel() }
                    .foregroundStyle(.white)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(role: .confirm) { crop() }
                    .foregroundStyle(.white)
            }
        }
        .toolbarBackground(.black, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }

    /// Projects the visible square region of the (scaled/offset) image back into
    /// image space and returns it as a square `UIImage`.
    private func crop() {
        let container = containerSize
        let source = upright ?? Self.normalizedUpright(image)
        guard let cg = source.cgImage, container.width > 0, container.height > 0 else {
            // Defensive fallback: return the upright image as-is.
            onCrop(source)
            return
        }
        let side = min(container.width, container.height)

        // Same pipeline as the body: fit, then zoom. `pointsPerPixel` is how many
        // displayed points one image pixel occupies after both.
        let fit = source.size.width > 0 && source.size.height > 0
            ? min(container.width / source.size.width, container.height / source.size.height)
            : 1
        let pointsPerPixel = fit * scale

        // Square side in image pixels: zooming in covers fewer of them.
        let cropSide = side / pointsPerPixel

        // The image's center sits at container-center + offset (the square's
        // center is the container center); convert the gap into image pixels.
        let centerX = CGFloat(cg.width) / 2 - offset.width / pointsPerPixel
        let centerY = CGFloat(cg.height) / 2 - offset.height / pointsPerPixel

        var rect = CGRect(
            x: centerX - cropSide / 2,
            y: centerY - cropSide / 2,
            width: cropSide,
            height: cropSide
        )
        // Clamp into the image bounds (belt and braces — the offset clamp already
        // keeps the square inside the image).
        rect.origin.x = max(0, min(rect.origin.x, CGFloat(cg.width) - rect.width))
        rect.origin.y = max(0, min(rect.origin.y, CGFloat(cg.height) - rect.height))

        guard let cropped = cg.cropping(to: rect) else {
            onCrop(source)
            return
        }
        // The source is orientation-`.up`, so the crop needs no orientation wrap.
        onCrop(UIImage(cgImage: cropped))
    }

    /// Farthest the image center may stray from the container center while the
    /// scaled image still covers the square on each axis.
    private static func maxOffsets(baseWidth: CGFloat, baseHeight: CGFloat,
                                   squareSide: CGFloat, scale: CGFloat) -> CGSize {
        CGSize(
            width: max(0, (baseWidth * scale - squareSide) / 2),
            height: max(0, (baseHeight * scale - squareSide) / 2))
    }

    private static func clamped(_ offset: CGSize, maxOffsets: CGSize) -> CGSize {
        CGSize(
            width: min(maxOffsets.width, max(-maxOffsets.width, offset.width)),
            height: min(maxOffsets.height, max(-maxOffsets.height, offset.height)))
    }

    /// Redraws `image` into an orientation-`.up`, scale-1 bitmap capped at
    /// `maxDimension` on the long side. `UIImage.cgImage` is the raw encoded
    /// buffer — it ignores EXIF orientation while the displayed `Image` applies
    /// it — so cropping it directly slices camera photos sideways; after this,
    /// `cgImage`'s axes match what the user sees. Downscaling a 12MP library
    /// shot to the working copy bounds the decode; the avatar is saved at 512px,
    /// so nothing visible is lost.
    private static func normalizedUpright(_ image: UIImage, maxDimension: CGFloat = 2048) -> UIImage {
        let longSide = max(image.size.width, image.size.height)
        let needsRedraw = image.imageOrientation != .up
            || image.scale != 1
            || (longSide > maxDimension && longSide > 0)
        guard needsRedraw else { return image }

        let ratio = longSide > 0 ? min(1, maxDimension / longSide) : 1
        let drawSize = CGSize(width: image.size.width * ratio, height: image.size.height * ratio)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: drawSize, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: drawSize))
        }
    }
}
