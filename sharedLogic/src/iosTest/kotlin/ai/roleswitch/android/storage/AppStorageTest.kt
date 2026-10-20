//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.storage

import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The single directory everything the app persists lives under. */
class AppStorageTest {

    @Test
    fun appDataDirectoryLivesUnderDocumentsAndExists() {
        val path = appDataDirectory()
        assertTrue(path.endsWith("/RolesWitch"), path)
        val documents = (
            NSFileManager.defaultManager.URLsForDirectory(
                directory = platform.Foundation.NSDocumentDirectory,
                inDomains = platform.Foundation.NSUserDomainMask
            ).first() as platform.Foundation.NSURL
            ).path!!
        assertTrue(path.startsWith(documents), "$path vs $documents")
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path))
    }

    @Test
    fun callingItAgainIsIdempotent() {
        assertEquals(appDataDirectory(), appDataDirectory())
    }
}
