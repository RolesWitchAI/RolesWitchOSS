//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

/**
 * A user-added (`custom:`-prefixed) model for tests. Field values are chosen to match
 * what `decodeCustomLitertModels` reconstructs, so an encode/decode round trip through
 * the real persistence format compares equal.
 */
fun testLitertModel(
    id: String = "custom:test/model",
    fileName: String = "model.litertlm",
    supportsImage: Boolean = false
): LitertModel = LitertModel(
    id = id,
    displayName = id.substringAfterLast('/'),
    repoId = id.removePrefix(CUSTOM_ID_PREFIX),
    fileName = fileName,
    commitHash = "0123456789abcdef0123456789abcdef01234567",
    sizeBytes = 1_000_000_000L,
    sha256 = null,
    minDeviceMemoryGb = 4,
    supportsImage = supportsImage,
    supportsThinking = true,
    gated = false,
    licenseName = "Test License",
    licenseUrl = "https://example.com/license",
    defaultTopK = 64,
    defaultTopP = 0.95f,
    defaultTemperature = 1.0f,
    defaultMaxTokens = 4096,
    maxContextLength = null,
    defaultAccelerator = "cpu",
    visionAccelerator = "gpu"
)
