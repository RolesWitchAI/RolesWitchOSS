//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import ImageIO
import OSLog
import SharedLogic
import UIKit
import UniformTypeIdentifiers

private let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "RolesWitch", category: "MessageImages")

/// Attachment thumbnails for the chat list, decoded once, small, and off the main thread.
///
/// The bubble used to build `UIImage(data:)` for every attachment inside `body`, which SwiftUI then
/// scaled into a 120-point frame. Three costs hid in that line. A stored attachment is a megapixel
/// JPEG, so each read decoded a megapixel image to draw a thumbnail; `body` runs constantly —
/// every streamed token re-renders the list — so the decode repeated for every visible image
/// message, several times a second; and it all ran on the main actor, so the first frame of a
/// conversation stalled on it. A thread with a few photos in it was unusable long before any
/// model was loaded.
///
/// So: decode **at thumbnail size** through ImageIO, which never materialises the full bitmap;
/// keep the result in a cache keyed by message id; and run the miss path **detached**, because
/// even a millisecond-scale decode belongs off the main actor when the opening scroll realizes
/// several image rows back to back. `AvatarView` has had a cache of this shape for as long as
/// avatars have existed; the difference here is the downsampling, because an avatar is small to
/// begin with and a photo is not.
///
/// The full-size image is decoded only when the viewer opens — one deliberate decode on a tap,
/// rather than one per frame for something being drawn at 120 points.
enum MessageImages {

    /// Longest side of a cached thumbnail, in pixels.
    ///
    /// The grid draws at 120 points, so this covers a 3× screen exactly. Stored rather than derived
    /// from the trait collection: a cache keyed by message id cannot hold two sizes, and a phone
    /// does not change scale mid-conversation.
    private static let thumbnailPixels = 360

    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        // Roughly a screen of image messages either side of the visible range. Thumbnails are
        // ~500 KB each decoded, so this is single-digit megabytes at worst.
        cache.countLimit = 64
        return cache
    }()

    /// Thumbnails for every attachment of one message, in attachment order.
    ///
    /// Attachments are decoded one at a time — each `thumbnail` call already leaves the main
    /// thread, and serializing keeps the peak memory at one bitmap instead of one per attachment.
    /// Attachments whose bytes fail to decode are dropped, so the count is what ends up on screen.
    static func thumbnails(messageId: String, attachments: [KotlinByteArray]) async -> [(index: Int, image: UIImage)] {
        var decoded: [(index: Int, image: UIImage)] = []
        decoded.reserveCapacity(attachments.count)
        for (index, bytes) in attachments.enumerated() {
            if let image = await thumbnail(key: "\(messageId)-\(index)", data: { bytes.toData() }) {
                decoded.append((index: index, image: image))
            }
        }
        return decoded
    }

    /// A thumbnail for one attachment, decoded off the main thread on the first call and reused
    /// after.
    ///
    /// [data] is a closure so the bytes are only copied out of Kotlin on a miss — even at memcpy
    /// speed that copy and the ImageIO decode are work no first frame should wait behind.
    /// NSCache is thread-safe, so the detached decode can publish into it directly.
    static func thumbnail(key: String, data: @escaping () -> Data) async -> UIImage? {
        if let cached = cache.object(forKey: key as NSString) { return cached }
        let image = await Task.detached(priority: .userInitiated) { downsample(data()) }.value
        guard let image else { return nil }
        cache.setObject(image, forKey: key as NSString)
        return image
    }

    /// The attachment at full size, for the fullscreen viewer. Never cached: it is one image, on
    /// screen until dismissed, and holding megapixel bitmaps for every photo ever tapped is the
    /// memory problem this type exists to avoid.
    static func full(_ data: Data) -> UIImage? {
        UIImage(data: data)
    }

    /// Decodes straight to thumbnail size. `CGImageSourceCreateThumbnailAtIndex` reads the JPEG's
    /// own downscaled representation where it has one and decodes at the target size where it does
    /// not — either way the full bitmap is never allocated. Runs on whatever thread the caller
    /// brought: ImageIO and the copy are both safe off the main thread.
    private static func downsample(_ data: Data) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary)
        else {
            logger.warning("Attachment did not decode")
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            // Honour the EXIF orientation, or a photo taken sideways is cached sideways.
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: thumbnailPixels,
            // Decode here rather than lazily at draw time, which would put the work back on the
            // first frame that shows the image.
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            logger.warning("Attachment produced no thumbnail")
            return nil
        }
        return UIImage(cgImage: thumbnail)
    }
}
