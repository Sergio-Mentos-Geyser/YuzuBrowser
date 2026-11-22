/*
 * Copyright (C) 2026 YuzuBrowser Derivative contributors
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

import androidx.room.withTransaction
import jp.hazuki.yuzubrowser.adblock.repository.abp.AbpDatabase
import jp.hazuki.yuzubrowser.adblock.repository.abp.AbpEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal data class AbpSubscription(val url: String, val enabled: Boolean)

/**
 * Portable ABP subscription settings, without compiled filters, Room IDs,
 * download timestamps, or HTTP cache metadata.
 */
internal object AbpSubscriptionBackup {
    const val ENTRY_NAME = "metadata/yuzubrowser_abp_subscriptions_v1.json"

    private const val FORMAT = "yuzubrowser-abp-subscriptions"
    private const val VERSION = 1
    private const val MAX_SUBSCRIPTIONS = 20000
    private const val MAX_URL_LENGTH = 8192

    suspend fun export(database: AbpDatabase): ByteArray {
        val subscriptions = database.abpDao().getAll()
        if (subscriptions.size > MAX_SUBSCRIPTIONS) {
            throw IOException("Too many ABP subscriptions")
        }

        val entries = JSONArray()
        subscriptions.forEach { entity ->
            entries.put(JSONObject().apply {
                put("url", entity.url)
                put("enabled", entity.enabled)
            })
        }
        val root = JSONObject().apply {
            put("format", FORMAT)
            put("schemaVersion", VERSION)
            put("subscriptions", entries)
        }
        return root.toString(2).toByteArray(Charsets.UTF_8)
    }

    fun parse(bytes: ByteArray): List<AbpSubscription> {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        if (root.getString("format") != FORMAT || root.getInt("schemaVersion") != VERSION) {
            throw IOException("Unsupported ABP backup format or version")
        }

        val entries = root.getJSONArray("subscriptions")
        if (entries.length() > MAX_SUBSCRIPTIONS) {
            throw IOException("Too many ABP subscriptions")
        }
        return List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            val url = item.get("url") as? String
                ?: throw IOException("Invalid ABP subscription URL")
            val enabled = item.get("enabled") as? Boolean
                ?: throw IOException("Invalid ABP enabled value")
            if (url.isBlank() || url.length > MAX_URL_LENGTH) {
                throw IOException("Invalid ABP subscription URL length")
            }
            AbpSubscription(url, enabled)
        }
    }

    /**
     * Replaces the list atomically through Room, not by replacing a live DB file.
     * Default entity values make restored subscriptions due for re-download.
     */
    suspend fun restore(database: AbpDatabase, subscriptions: List<AbpSubscription>) {
        database.withTransaction {
            val dao = database.abpDao()
            dao.getAll().forEach { dao.delete(it) }
            subscriptions.forEach { subscription ->
                dao.inset(AbpEntity(url = subscription.url, enabled = subscription.enabled))
            }
        }
    }
}
