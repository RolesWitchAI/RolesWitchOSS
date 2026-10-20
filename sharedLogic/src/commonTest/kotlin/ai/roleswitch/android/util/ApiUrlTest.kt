//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiUrlTest {

    // ---- normalizeApiUrl: explicit schemes are always respected --------------------

    @Test
    fun explicitHttpsIsKept() {
        assertEquals("https://api.openai.com/v1", normalizeApiUrl("https://api.openai.com/v1"))
    }

    @Test
    fun explicitHttpIsKeptEvenForARemoteHost() {
        assertEquals("http://api.example.com/v1", normalizeApiUrl("http://api.example.com/v1"))
    }

    @Test
    fun inputIsTrimmed() {
        assertEquals("https://api.openai.com/v1", normalizeApiUrl("  https://api.openai.com/v1  "))
    }

    @Test
    fun emptyInputIsEmptyOutput() {
        assertEquals("", normalizeApiUrl(""))
        assertEquals("", normalizeApiUrl("   "))
    }

    // ---- /v1 appending ---------------------------------------------------------------

    @Test
    fun missingPathGetsV1() {
        assertEquals("https://api.openai.com/v1", normalizeApiUrl("api.openai.com"))
    }

    @Test
    fun rootPathGetsV1() {
        assertEquals("https://api.openai.com/v1", normalizeApiUrl("api.openai.com/"))
        assertEquals("https://api.openai.com/v1", normalizeApiUrl("https://api.openai.com"))
    }

    @Test
    fun existingPathIsPreservedVerbatim() {
        assertEquals("https://proxy.example.com/openai/v1", normalizeApiUrl("https://proxy.example.com/openai/v1"))
        assertEquals("https://api.openai.com/v1/", normalizeApiUrl("https://api.openai.com/v1/"))
    }

    // ---- schemaless hosts: local vs remote decides scheme and default port ----------

    @Test
    fun schemalessRemoteHostGetsHttpsAndNoInventedPort() {
        assertEquals("https://api.openai.com/v1", normalizeApiUrl("api.openai.com"))
    }

    @Test
    fun schemalessLocalhostGetsHttpAndLmStudioPort() {
        assertEquals("http://localhost:1234/v1", normalizeApiUrl("localhost"))
    }

    @Test
    fun explicitPortOnSchemalessLocalHostIsKept() {
        assertEquals("http://localhost:8080/v1", normalizeApiUrl("localhost:8080"))
    }

    @Test
    fun explicitSchemeSuppressesTheDefaultLocalPort() {
        assertEquals("http://localhost/v1", normalizeApiUrl("http://localhost"))
    }

    @Test
    fun rfc1918AddressesAreLocal() {
        assertEquals("http://192.168.1.5:1234/v1", normalizeApiUrl("192.168.1.5"))
        assertEquals("http://10.0.2.2:1234/v1", normalizeApiUrl("10.0.2.2"))
        assertEquals("http://127.0.0.1:1234/v1", normalizeApiUrl("127.0.0.1"))
    }

    @Test
    fun linkLocal169Dot254IsLocal() {
        assertEquals("http://169.254.10.5:1234/v1", normalizeApiUrl("169.254.10.5"))
    }

    @Test
    fun private172RangeIsLocalOnlyWithin16To31() {
        assertEquals("http://172.16.0.1:1234/v1", normalizeApiUrl("172.16.0.1"))
        assertEquals("http://172.31.255.255:1234/v1", normalizeApiUrl("172.31.255.255"))
        // 172.15 / 172.32 are public ranges despite looking private.
        assertEquals("https://172.15.0.1/v1", normalizeApiUrl("172.15.0.1"))
        assertEquals("https://172.32.0.1/v1", normalizeApiUrl("172.32.0.1"))
    }

    @Test
    fun singleLabelHostnamesAreLocal() {
        assertEquals("http://raspberrypi:1234/v1", normalizeApiUrl("raspberrypi"))
    }

    @Test
    fun mdnsNamesAreLocal() {
        assertEquals("http://mybox.local:1234/v1", normalizeApiUrl("mybox.local"))
    }

    @Test
    fun remoteLookingIpIsRemote() {
        assertEquals("https://8.8.8.8/v1", normalizeApiUrl("8.8.8.8"))
    }

    // ---- degenerate input is returned unchanged --------------------------------------

    @Test
    fun hostlessInputIsReturnedAsTyped() {
        assertEquals("https://", normalizeApiUrl("https://"))
        assertEquals("http://", normalizeApiUrl("http://"))
    }

    // ---- hostnameFromApiUrl -----------------------------------------------------------

    @Test
    fun hostnameIsExtractedWithOrWithoutScheme() {
        assertEquals("api.openai.com", hostnameFromApiUrl("https://api.openai.com/v1"))
        assertEquals("api.openai.com", hostnameFromApiUrl("api.openai.com"))
    }

    @Test
    fun hostnameExcludesThePort() {
        assertEquals("192.168.0.100", hostnameFromApiUrl("http://192.168.0.100:1234/v1"))
    }

    @Test
    fun hostnameIsNullWhenThereIsNothingToShow() {
        assertNull(hostnameFromApiUrl(""))
        assertNull(hostnameFromApiUrl("   "))
        assertNull(hostnameFromApiUrl("https://"))
        assertNull(hostnameFromApiUrl("http:///v1"))
    }
}
