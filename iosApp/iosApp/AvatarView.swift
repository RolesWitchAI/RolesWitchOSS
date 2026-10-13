//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import SwiftUI
import UIKit
import SharedLogic

/// Renders a thread's avatar. Mirrors the Android `ThreadAvatar` priority:
///  1. `avatarBytes` — `UIImage(data:)` shown filling a circle (center-cropped).
///  2. otherwise — a colored `InitialsCircle` derived from `name`.
///
/// The decoded `UIImage` is cached on the bytes' identity so list rows don't
/// re-decode on every redraw. `KotlinByteArray.toData()` bridges the KMP BLOB.
struct AvatarView: View {
    let name: String
    var avatarBytes: KotlinByteArray? = nil
    var size: CGFloat = 40

    var body: some View {
        if let image = decodedImage {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: size, height: size)
                .clipShape(Circle())
        } else {
            InitialsCircle(name: name, size: size)
        }
    }

    /// Decoded once and cached on the bytes' content hash so list rows and the chat
    /// header don't re-decode the same BLOB on every redraw.
    private var decodedImage: UIImage? {
        guard let bytes = avatarBytes else { return nil }
        return AvatarView.imageCache.value(for: bytes)
    }

    /// Caches decoded `UIImage`s by `KotlinByteArray` content hash. Keeps the
    /// decoded `UIImage` alive across SwiftUI re-renders without copying the bytes.
    private static let imageCache = ImageCache()
}

/// Colored-circle fallback with up to two initials. Mirrors Android
/// `colorFromString` (HSL s=0.6, l=0.5 → HSB s=0.6, b=0.8) and `extractInitials`.
struct InitialsCircle: View {
    let name: String
    var size: CGFloat = 40

    var body: some View {
        let initials = Self.extractInitials(name)
        ZStack {
            Circle()
                .fill(Color(hue: Self.hue(for: name), saturation: 0.6, brightness: 0.8))
                .frame(width: size, height: size)
            // Derived from the user's thread title — never a catalog lookup.
            Text(verbatim: initials.isEmpty ? "?" : initials)
                .font(.system(size: size * 0.4, weight: .medium))
                .foregroundStyle(.white)
        }
    }

    static func extractInitials(_ name: String) -> String {
        let words = name.split(whereSeparator: { $0.isWhitespace }).filter { !$0.isEmpty }
        return words.prefix(2).compactMap { $0.first.map { String($0).uppercased() } }.joined()
    }

    static func hue(for string: String) -> Double {
        let hash = string.reduce(0) { $0 + Int($1.unicodeScalars.first?.value ?? 0) }
        return Double(abs(hash) % 360) / 360.0
    }
}

/// Tiny LRU-ish image cache keyed by `KotlinByteArray` content hash. Keeps the
/// decoded `UIImage` alive across SwiftUI re-renders without copying the bytes.
private final class ImageCache {
    private let lock = NSLock()
    private var storage: [Int: UIImage] = [:]
    private var order: [Int] = []
    private let limit = 64

    func value(for bytes: KotlinByteArray) -> UIImage? {
        let key = bytes.contentHash()
        lock.lock(); defer { lock.unlock() }
        if let img = storage[key] { return img }
        guard let img = UIImage(data: bytes.toData()) else { return nil }
        storage[key] = img
        order.append(key)
        if order.count > limit, let oldest = order.first {
            order.removeFirst()
            storage.removeValue(forKey: oldest)
        }
        return img
    }
}

private extension KotlinByteArray {
    /// Stable content hash so the cache can key on bytes identity without copying.
    func contentHash() -> Int {
        var h = 0
        for i in 0..<size {
            h = 31 &* h &+ Int(get(index: i))
        }
        return h
    }
}

#if DEBUG
#Preview {
    HStack(spacing: 16) {
        AvatarView(name: "Ada Lovelace", size: 56)
        AvatarView(name: "Roles Witch", size: 40)
        InitialsCircle(name: "Roles Witch", size: 32)
    }
    .padding()
}
#endif
