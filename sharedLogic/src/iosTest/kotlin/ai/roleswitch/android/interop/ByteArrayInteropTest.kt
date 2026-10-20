//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.interop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bulk ByteArray <-> NSData bridge the Swift side wraps. */
class ByteArrayInteropTest {

    @Test
    fun bytesRoundTripThroughNSData() {
        val original = byteArrayOf(1, 2, 3, -1, -128, 127, 0)
        val restored = toKotlinByteArray(original.toNSData())
        assertTrue(original.contentEquals(restored))
    }

    @Test
    fun emptyArraysSurviveBothWays() {
        assertTrue(ByteArray(0).toNSData().length == 0uL)
        assertEquals(0, toKotlinByteArray(ByteArray(0).toNSData()).size)
    }

    @Test
    fun largeBuffersCopyIntact() {
        val original = ByteArray(100_000) { (it % 251).toByte() }
        val restored = toKotlinByteArray(original.toNSData())
        assertTrue(original.contentEquals(restored))
    }
}
