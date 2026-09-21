package com.example.roboguardandroid

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * All texts the phone app shows. They live in one file, `assets/texts/texts.json`: one entry per text, so the wording — or
 * the whole language — can be changed there without touching the code. The robot app has the same arrangement for its own
 * screens and spoken sentences, so both ends of RoboGuard are worded in one readable place each.
 *
 * File format:
 * ```json
 * "settings.button.sync": { "text": "Sync with your Robot!", "note": "Sends the privacy settings to the robot" }
 * ```
 * `note` only says where the text appears and is never shown.
 *
 * Placeholders are named and written in curly braces, e.g. `"Drive to {location}"`; pass them as pairs:
 * `UiText.get("nav.button.drive_to", "location" to name)`. A missing key returns the key name, so a gap is visible on
 * screen instead of an empty label.
 *
 * Only DISPLAYED texts belong here. Values the app works with internally — sensor names from the robot, the sleep
 * durations that are turned into seconds — are not texts and must stay as they are, or the phone and the robot would stop
 * understanding each other.
 */
object UiText {

    private const val TAG = "UiText"
    private const val ASSET_FILE = "texts/texts.json"

    @Volatile
    private var texts: Map<String, String> = emptyMap()

    @Volatile
    private var appContext: Context? = null

    /** Loads the texts (idempotent). Called in MainActivity.onCreate before anything is shown. */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        reload()
    }

    /** Re-reads the file (after an app update or for tests). */
    @Synchronized
    fun reload() {
        val context = appContext ?: return
        val json = runCatching { JSONObject(context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }) }
            .onFailure { Log.e(TAG, "could not read $ASSET_FILE: $it") }
            .getOrNull() ?: return
        val entries = json.optJSONObject("texts")
        val merged = LinkedHashMap<String, String>()
        if (entries != null) {
            for (key in entries.keys()) {
                val entry = entries.opt(key)
                val text = if (entry is JSONObject) entry.optString("text") else entry?.toString()
                if (!text.isNullOrEmpty()) merged[key] = text
            }
        }
        texts = merged
        Log.i(TAG, "loaded ${merged.size} texts")
    }

    /**
     * The text for [key], with [placeholders] filled in ("location" to "Kitchen" replaces `{location}`).
     * Unknown keys return the key itself and are logged.
     */
    fun get(key: String, vararg placeholders: Pair<String, Any?>): String {
        val template = texts[key] ?: run {
            Log.w(TAG, "missing text '$key' in assets/$ASSET_FILE")
            return key
        }
        if (placeholders.isEmpty()) return template
        var result = template
        for ((name, value) in placeholders) result = result.replace("{$name}", value?.toString() ?: "")
        return result
    }

    /** Like [get], but returns null for a missing key (for optional texts). */
    fun getOrNull(key: String): String? = texts[key]

    /** All keys currently loaded, for tooling and tests. */
    val keys: Set<String> get() = texts.keys
}

/**
 * The colours of the RoboGuard phone app in one place, so every screen codes the same meaning with the same colour.
 * They are the ones the settings screen already used; the map screen now takes them from here instead of repeating hex
 * values.
 */
object RoboGuardColors {
    /** Header bar, and anything that points at the robot itself (its position, the area being drawn). */
    val Header = androidx.compose.ui.graphics.Color(0xFF1A73E8)
    /** Opens something bigger, e.g. "Navigation and Map". */
    val Action = androidx.compose.ui.graphics.Color(0xFF4CAF50)
    /** Stop, delete, unpair: everything that takes something away or interrupts. */
    val Danger = androidx.compose.ui.graphics.Color.Red
    /** Confirmed and running: connection alive, allowed answer, saved places. */
    val Good = androidx.compose.ui.graphics.Color(0xFF2E7D32)
    /** Attention, not an error: a temporary permission to cross a private area, a refused request. */
    val Warn = androidx.compose.ui.graphics.Color(0xFFEF6C00)
    /** A granted crossing permission on the map (same meaning as [Warn], brighter for thin lines). */
    val Allowed = androidx.compose.ui.graphics.Color(0xFFFF9100)
    /** Unknown or switched off. */
    val Idle = androidx.compose.ui.graphics.Color(0xFF9E9E9E)
    /** Points the person put on the map themselves. */
    val OwnPoint = androidx.compose.ui.graphics.Color(0xFF7B1FA2)
    /** Background and text of the error banners. */
    val ErrorBackground = androidx.compose.ui.graphics.Color(0xFFFFE5E5)
    val ErrorText = androidx.compose.ui.graphics.Color(0xFFB00020)
    /** Neutral backgrounds: map placeholder, log box. */
    val Surface = androidx.compose.ui.graphics.Color(0xFFEEEEEE)
    val LogBackground = androidx.compose.ui.graphics.Color(0xFFF2F2F2)
}
