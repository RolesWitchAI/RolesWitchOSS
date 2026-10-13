//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.numberWithBool

private const val APP_DIRECTORY_NAME = "RolesWitch"

/**
 * The single directory holding everything the app persists — the Room database
 * and the DataStore both live under here.
 *
 * It exists so the iCloud exclusion can be applied once, to one directory, and
 * cover every file inside. Flagging individual files does not work for the
 * database: SQLite creates `-wal` and `-shm` siblings on demand, so the
 * write-ahead log — containing not-yet-checkpointed message text — would still
 * be backed up.
 *
 * The app is privacy-first: conversations, role prompts and the API key must not
 * leave the device, and `NSDocumentDirectory` is included in iCloud backup by
 * default.
 */
@OptIn(ExperimentalForeignApi::class)
fun appDataDirectory(): String {
    val documents = (
        NSFileManager.defaultManager.URLsForDirectory(
            directory = NSDocumentDirectory,
            inDomains = NSUserDomainMask
        ).first() as NSURL
        ).path!!

    val path = "$documents/$APP_DIRECTORY_NAME"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null
    )

    // Re-applied on every call rather than only at creation: this is a filesystem
    // attribute, and a restore or a file-level copy can drop it while leaving the
    // directory in place.
    NSURL.fileURLWithPath(path).setResourceValue(
        value = NSNumber.numberWithBool(true),
        forKey = NSURLIsExcludedFromBackupKey,
        error = null
    )

    return path
}
