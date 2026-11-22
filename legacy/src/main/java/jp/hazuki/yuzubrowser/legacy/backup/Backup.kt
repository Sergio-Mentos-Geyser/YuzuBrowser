/*
 * Copyright (C) 2017-2021 Hazuki
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.hazuki.yuzubrowser.legacy.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import jp.hazuki.yuzubrowser.adblock.repository.abp.AbpDatabase
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "YuzuSettingsBackup"
private const val ABP_DATABASE_PATH = "databases/abp.db"
private const val ABP_FILTER_CACHE_PREFIX = "app_adblock_filter/"
private const val MAX_SUBSCRIPTION_JSON_BYTES = 4 * 1024 * 1024

/**
 * The subscription list is stored as JSON rather than copying the live Room
 * database. Compiled ad-block filters are re-downloaded after restore.
 */
suspend fun BackupManager.backup(context: Context, uri: Uri, abpDatabase: AbpDatabase): Boolean {
    return try {
        val subscriptionJson = AbpSubscriptionBackup.export(abpDatabase)
        if (subscriptionJson.size > MAX_SUBSCRIPTION_JSON_BYTES) {
            throw IOException("ABP subscription JSON exceeds the size limit")
        }
        val os = context.contentResolver.openOutputStream(uri) ?: return false
        ZipOutputStream(os).use { zip ->
            // Place the JSON in a unique archive entry; no permanent file is needed.
            zip.putNextEntry(ZipEntry(AbpSubscriptionBackup.ENTRY_NAME))
            zip.write(subscriptionJson)
            zip.closeEntry()

            val rootUri = root.toURI()
            backUpFiles.forEach { file ->
                val name = rootUri.relativize(file.toURI()).toString()
                if (isManagedAbpFile(name) || name == AbpSubscriptionBackup.ENTRY_NAME) {
                    return@forEach
                }
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Settings backup failed", e)
        false
    }
}

/**
 * Read and validate the optional subscription JSON before deleting any files.
 * Old archives without this entry continue through the legacy restore path.
 */
suspend fun BackupManager.restore(context: Context, uri: Uri, abpDatabase: AbpDatabase): Boolean {
    return try {
        val json = readSubscriptionJson(context, uri)
        val subscriptions = json?.let { AbpSubscriptionBackup.parse(it) }

        val rootPath = root.canonicalPath
        val ins = context.contentResolver.openInputStream(uri) ?: return false
        ZipInputStream(ins).use { zip ->
            // The live Room database must not be deleted when JSON is available.
            cleanFiles(subscriptions != null)

            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val file = File(rootPath, name)
                if (!file.canonicalPath.startsWith(rootPath + File.separator)) {
                    throw IOException("Invalid backup entry path")
                }

                when {
                    name == AbpSubscriptionBackup.ENTRY_NAME -> {
                        // Already read and validated before cleanFiles().
                    }
                    subscriptions != null && isManagedAbpFile(name) -> {
                        // Never overwrite the live Room DB or restore stale filters.
                    }
                    "main_preference.xml".equals(file.name, ignoreCase = true) -> {
                        val parser = PrefXmlParser(context, "main_preference")
                        try {
                            parser.load(zip)
                        } catch (e: XmlPullParserException) {
                            e.printStackTrace()
                        }
                    }
                    entry.isDirectory -> {
                        if (file.exists()) file.deleteRecursively()
                        file.mkdir()
                    }
                    else -> {
                        if (file.exists()) {
                            file.deleteRecursively()
                        } else if (!file.parentFile!!.exists()) {
                            file.parentFile!!.mkdirs()
                        }
                        file.outputStream().use { os -> zip.copyTo(os) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (subscriptions != null) {
            AbpSubscriptionBackup.restore(abpDatabase, subscriptions)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Settings restore failed", e)
        false
    }
}

private fun isManagedAbpFile(name: String): Boolean {
    return name == ABP_DATABASE_PATH || name.startsWith(ABP_FILTER_CACHE_PREFIX)
}

/**
 * Scans the ZIP without modifying the app and bounds the size of its JSON entry.
 * ACTION_OPEN_DOCUMENT URIs can be opened a second time for actual restoration.
 */
private fun readSubscriptionJson(context: Context, uri: Uri): ByteArray? {
    val ins = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Cannot open backup file")
    ZipInputStream(ins).use { zip ->
        var json: ByteArray? = null
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == AbpSubscriptionBackup.ENTRY_NAME) {
                if (entry.isDirectory || json != null) {
                    throw IOException("Duplicate or invalid ABP JSON entry")
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = zip.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > MAX_SUBSCRIPTION_JSON_BYTES) {
                        throw IOException("ABP subscription JSON exceeds the size limit")
                    }
                    output.write(buffer, 0, size)
                }
                json = output.toByteArray()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        return json
    }
}
