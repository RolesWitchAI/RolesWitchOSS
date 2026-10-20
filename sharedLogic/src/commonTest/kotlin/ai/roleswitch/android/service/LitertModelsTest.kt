//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.service

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LitertModelsTest {

    @AfterTest
    fun tearDown() {
        // setCustom is process-wide; a test that pushed user-added models must not leak
        // them into another test's lookups.
        LitertModels.setCustom(emptyList())
    }

    @Test
    fun registryIdsAreUnique() {
        val ids = LitertModels.registry.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun everyRegistryModelHasAConsistentLayoutOnDisk() {
        LitertModels.registry.forEach { model ->
            assertEquals("litert/${model.id}/${model.commitHash}", model.relativeDir)
            assertEquals("${model.relativeDir}/${model.fileName}", model.relativePath)
        }
    }

    @Test
    fun downloadUrlPinsTheCommit() {
        LitertModels.registry.forEach { model ->
            val expected = "https://huggingface.co/${model.repoId}/resolve/${model.commitHash}/${model.fileName}?download=true"
            assertEquals(expected, model.downloadUrl)
        }
    }

    @Test
    fun gatedModelsHaveNoPinnedDigestAndUngatedOnesDo() {
        LitertModels.registry.forEach { model ->
            if (model.gated) {
                assertNull(model.sha256, "gated ${model.id} must not pretend to pin a digest")
            } else {
                val sha = assertNotNull(model.sha256, "ungated ${model.id} should pin its digest")
                assertTrue(sha.matches(Regex("^[0-9a-f]{64}$")), "digest of ${model.id} is not a sha256")
            }
        }
    }

    @Test
    fun visionModelsUseTheGpuVisionAccelerator() {
        LitertModels.registry.filter { it.supportsImage }.forEach { model ->
            assertEquals("gpu", model.visionAccelerator, "vision tower of ${model.id} must run on GPU")
        }
    }

    @Test
    fun registryModelsAreNotCustom() {
        LitertModels.registry.forEach { model ->
            assertFalse(model.isCustom)
        }
    }

    @Test
    fun customPrefixMarksAUserAddedModel() {
        assertTrue(testLitertModel(id = "custom:anything").isCustom)
    }

    @Test
    fun getResolvesRegistryModels() {
        assertEquals("gemma-4-E2B-it", LitertModels.get("gemma-4-E2B-it")?.id)
    }

    @Test
    fun getReturnsNullForUnknownIds() {
        assertNull(LitertModels.get("no-such-model"))
        assertNull(LitertModels.get(""))
    }

    @Test
    fun customModelsJoinTheCatalogAndCanBeReplaced() {
        val first = testLitertModel(id = "custom:owner/one")
        LitertModels.setCustom(listOf(first))
        assertEquals(first, LitertModels.get("custom:owner/one"))
        assertTrue(LitertModels.all.contains(first))

        val replacement = testLitertModel(id = "custom:owner/one", fileName = "bigger.litertlm")
        LitertModels.setCustom(listOf(replacement))
        assertEquals(replacement, LitertModels.get("custom:owner/one"))

        LitertModels.setCustom(emptyList())
        assertNull(LitertModels.get("custom:owner/one"))
    }

    @Test
    fun customModelsRoundTripThroughTheirPersistedEncoding() {
        val models = listOf(
            testLitertModel(id = "custom:owner/a", fileName = "a.litertlm"),
            testLitertModel(id = "custom:owner/b", fileName = "b.litertlm")
        )
        val encoded = encodeCustomLitertModels(models)
        assertEquals(models, decodeCustomLitertModels(encoded))
    }

    @Test
    fun emptyCustomModelListEncodesAsAnEmptyArray() {
        assertEquals("[]", encodeCustomLitertModels(emptyList()))
    }

    @Test
    fun decodingDegenerateInputYieldsAnEmptyList() {
        assertEquals(emptyList(), decodeCustomLitertModels(""))
        assertEquals(emptyList(), decodeCustomLitertModels("   "))
        assertEquals(emptyList(), decodeCustomLitertModels("not json at all"))
        assertEquals(emptyList(), decodeCustomLitertModels("""{"id":"object-not-array"}"""))
    }
}
