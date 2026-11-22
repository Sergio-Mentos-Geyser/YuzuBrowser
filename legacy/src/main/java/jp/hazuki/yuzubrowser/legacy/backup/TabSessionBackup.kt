package jp.hazuki.yuzubrowser.legacy.backup

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Portable tab list only: never serializes history, cookies, or WebView state.
 * The existing app_tabs/index is saved by BrowserActivity.onStop().
 */
object TabSessionBackup {
    private const val FORMAT = "yuzubrowser-tabs"
    private const val VERSION = 1
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_TABS = 1000
    private const val MAX_URL = 8192
    private const val MAX_TITLE = 2048
    private const val PENDING = "pending_tabs_import.json"
    private const val APPLYING = "pending_tabs_import.applying"

    data class Tab(
        val url: String?,
        val title: String?,
        val tabType: Int,
        val pinned: Boolean,
        val navigationLocked: Boolean
    )

    data class Session(val privateModeAtBackup: Boolean, val tabs: List<Tab>)

    private fun tabDirectory(context: Context): File =
        context.getDir("tabs", Context.MODE_PRIVATE)

    @Throws(IOException::class)
    fun export(context: Context, uri: Uri, privateMode: Boolean): Int {
        val index = File(tabDirectory(context), "index")
        if (!index.isFile) throw IOException("No saved tab index")
        val records = try {
            JSONArray(String(readLimited(index), Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("Invalid saved tab index", e)
        }
        if (records.length() > MAX_TABS) throw IOException("Too many tabs")
        val tabs = ArrayList<Tab>(records.length())
        for (i in 0 until records.length()) {
            val item = records.getJSONObject(i)
            tabs.add(Tab(
                optionalString(item, "url"),
                optionalString(item, "title"),
                item.optInt("type", 0),
                item.optBoolean("pin", false),
                item.optBoolean("nav", false)
            ))
        }
        val bytes = encode(Session(privateMode, tabs)).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Tab backup exceeds size limit")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IOException("Cannot create tab backup file")
        return tabs.size
    }

    @Throws(IOException::class)
    fun read(context: Context, uri: Uri): Session {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open tab backup")
        return input.use { decode(readLimited(it)) }
    }

    /**
     * SettingsActivity only stages data. BrowserActivity is responsible for
     * appending it to the live tab manager on its next onStart().
     */
    @Throws(IOException::class)
    fun stage(context: Context, session: Session) {
        val directory = tabDirectory(context)
        val pending = File(directory, PENDING)
        if (pending.exists()) throw IOException("Previous tab import still pending")
        val bytes = encode(session).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Tab import exceeds size limit")
        val temporary = File.createTempFile("tab_import_", ".tmp", directory)
        try {
            temporary.outputStream().use { it.write(bytes) }
            if (!temporary.renameTo(pending)) throw IOException("Cannot stage tab import")
        } finally {
            temporary.delete()
        }
    }

    /**
     * Claim the pending import before adding tabs. Crash recovery never
     * silently replays a batch, which would duplicate existing tabs.
     */
    @Throws(IOException::class)
    fun takePending(context: Context): Session? {
        val directory = tabDirectory(context)
        val pending = File(directory, PENDING)
        if (!pending.exists()) return null
        val applying = File(directory, APPLYING)
        if (applying.exists() && !applying.delete()) {
            throw IOException("Cannot clear interrupted tab import marker")
        }
        if (!pending.renameTo(applying)) throw IOException("Cannot claim tab import")
        return try {
            decode(readLimited(applying))
        } finally {
            applying.delete()
        }
    }

    private fun optionalString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.opt(key) as? String
            ?: throw IOException("Invalid tab string field")
    }

    private fun encode(session: Session): String {
        val tabs = JSONArray()
        session.tabs.forEachIndexed { index, tab ->
            tabs.put(JSONObject().apply {
                put("position", index)
                put("url", tab.url ?: JSONObject.NULL)
                put("title", tab.title ?: JSONObject.NULL)
                put("tabType", tab.tabType)
                put("pinned", tab.pinned)
                put("navigationLocked", tab.navigationLocked)
            })
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("schemaVersion", VERSION)
            put("privateModeAtBackup", session.privateModeAtBackup)
            put("tabs", tabs)
        }.toString(2)
    }

    private fun decode(bytes: ByteArray): Session {
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("Invalid tab JSON", e)
        }
        if (root.optString("format") != FORMAT || root.optInt("schemaVersion") != VERSION) {
            throw IOException("Unsupported tab backup format")
        }
        val privateMode = root.opt("privateModeAtBackup") as? Boolean
            ?: throw IOException("Missing privacy metadata")
        val entries = root.optJSONArray("tabs") ?: throw IOException("Missing tabs")
        if (entries.length() > MAX_TABS) throw IOException("Too many tabs")
        val tabs = ArrayList<Tab>(entries.length())
        for (i in 0 until entries.length()) {
            val item = try {
                entries.getJSONObject(i)
            } catch (e: Exception) {
                throw IOException("Invalid tab entry", e)
            }
            val pos = item.opt("position")
            if (pos !is Int || pos != i) throw IOException("Invalid tab position")
            val url = optionalString(item, "url")
            val title = optionalString(item, "title")
            if (url != null && url.length > MAX_URL) throw IOException("Tab URL too long")
            if (title != null && title.length > MAX_TITLE) throw IOException("Tab title too long")
            validateRestorableUrl(url)
            val tabType = item.opt("tabType")
            if (tabType !is Int || tabType !in 0..2) throw IOException("Invalid tab type")
            val pinned = item.opt("pinned") as? Boolean
                ?: throw IOException("Missing pin state")
            val locked = item.opt("navigationLocked") as? Boolean
                ?: throw IOException("Missing navigation lock state")
            tabs.add(Tab(url, title, tabType, pinned, locked))
        }
        return Session(privateMode, tabs)
    }

    private fun validateRestorableUrl(url: String?) {
        if (url.isNullOrEmpty()) return
        if (url == "about:blank" || url == "yuzu:speeddial" ||
            url == "yuzu://speeddial") return
        val parsed = Uri.parse(url)
        val scheme = parsed.scheme?.toLowerCase(Locale.ROOT)
        if ((scheme != "http" && scheme != "https") || parsed.host.isNullOrEmpty()) {
            throw IOException("Unsupported URL in tab backup")
        }
    }

    private fun readLimited(file: File): ByteArray =
        file.inputStream().use { readLimited(it) }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) throw IOException("Tab JSON too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
