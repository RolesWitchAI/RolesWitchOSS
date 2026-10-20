//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.interop

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * Bulk `ByteArray` <-> `NSData` conversion for the Swift side.
 *
 * KMP exports `ByteArray` to Swift as `KotlinByteArray`, which has no bulk accessor —
 * only `get(index:)` / `set(index:value:)`, and every one of those calls crosses the
 * ObjC interop boundary. The per-byte loops this replaces copied a multi-megabyte photo
 * attachment one virtual call at a time, synchronously inside `MessageBubble.body` on
 * the first frame — the load stall behind `MessageImages`. Here the whole copy is one
 * interop call with a `memcpy` behind it, inside Kotlin.
 *
 * `KotlinByteArrayExt.swift` wraps these under the same `toData()` /
 * `toKotlinByteArray()` names the old loops had, so no Swift call site changed.
 */

/** Copies the array into a new `NSData` (`initWithBytes:length:` copies its buffer).
 *
 *  An empty array is special-cased: `addressOf(0)` on a zero-length array is an
 *  out-of-bounds access, so pinning-and-reading a first byte that does not exist
 *  crashed (an empty photo attachment hit this in production code paths).
 */
@OptIn(ExperimentalForeignApi::class)
fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData.create(bytes = null, length = 0uL)
    } else {
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }

/** Copies `data`'s bytes into a new array. */
@OptIn(ExperimentalForeignApi::class)
fun toKotlinByteArray(data: NSData): ByteArray {
    val result = ByteArray(data.length.toInt())
    if (result.isNotEmpty()) {
        result.usePinned { pinned ->
            data.bytes?.let { source ->
                memcpy(pinned.addressOf(0), source.reinterpret<ByteVar>(), data.length)
            }
        }
    }
    return result
}
