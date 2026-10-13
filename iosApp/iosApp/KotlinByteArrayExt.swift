//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

import Foundation
import SharedLogic

/// KMP's `ByteArray` exports to Swift as `KotlinByteArray`, which (unlike `NSData`)
/// does not auto-bridge and has no bulk accessor — only per-byte `get(index:)` /
/// `set(index:value:)`, each a call across the interop boundary. The conversions
/// therefore live on the Kotlin side (`ByteArrayInterop.kt`), where the whole copy is
/// one interop call with a `memcpy` behind it. An attachment is megabytes; the old
/// per-byte loops ran hundreds of thousands of virtual calls per image, and
/// `MessageBubble` used to run them on the main thread inside `body`.
extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        ByteArrayInteropKt.toKotlinByteArray(data: self)
    }
}

extension KotlinByteArray {
    func toData() -> Data {
        // `toNSData()` lands here as a category method on KotlinByteArray itself —
        // extension functions on Kotlin classes export on the receiver, not on the
        // `*Kt` facade of top-level functions.
        toNSData()
    }
}
