package com.example.roboguardandroid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Data model and client of the phone's navigation screen. The phone only shows the state the robot reports and sends
 * commands; the robot checks the privacy rules.
 */

/**
 * A RobotOS place or a location saved on the robot, in map coordinates (metres).
 * @property name name of the place
 * @property x x coordinate
 * @property y y coordinate
 * @property saved true for locations saved in RoboGuard, false for RobotOS places
 * @property persistent whether the point is saved in RoboGuard
 */
@Serializable
data class NavPoint(
    val name: String,
    val x: Double,
    val y: Double,
    val saved: Boolean = false,
    val persistent: Boolean = false
)

/**
 * The robot's position (metres) and heading (radians) as the robot reports it.
 * @property x x in metres
 * @property y y in metres
 * @property theta heading in radians
 * @property status pose status
 */
@Serializable
data class NavPose(val x: Double, val y: Double, val theta: Double, val status: String = "")

/**
 * One corner of a private area, in map coordinates (metres).
 * @property x x in metres
 * @property y y in metres
 */
@Serializable
data class NavCorner(val x: Double, val y: Double)

/**
 * A private area. [allowedUntil] is set while the robot has a temporary permission to cross it (ms since epoch).
 * @property name area name
 * @property allowedUntil end of a crossing permission, or null
 * @property corners corners of the area
 */
@Serializable
data class NavArea(val name: String, val allowedUntil: Long? = null, val corners: List<NavCorner> = emptyList())

/**
 * An open "may I cross this private area?" question of the robot; the phone may answer it like the robot's screen.
 * @property id question id
 * @property area area name
 */
@Serializable
data class NavQuestion(val id: Long, val area: String)

/**
 * The navigation state reported by `GET /nav/state`.
 * @property ok whether the request succeeded
 * @property running whether navigation runs
 * @property error error text, or null
 * @property map map name, or null
 * @property localized localization state, or null
 * @property sdkControl whether RoboGuard has SDK control
 * @property navState drive state
 * @property moving whether the robot drives or turns
 * @property speed speed preset
 * @property speedLabel speed label
 * @property measuredSpeed measured speed, or null
 * @property remoteControl phone that is steering, or null
 * @property now robot clock in milliseconds
 * @property pose robot pose, or null
 * @property selected selected point, or null
 * @property places RobotOS places
 * @property points saved and temporary points
 * @property areas private areas
 * @property privacyMargin margin in metres
 * @property drawing corners of the area being drawn, or null
 * @property areasLoaded whether the areas are loaded
 * @property areaStoreError area storage error, or null
 * @property areaSaveWarning area save warning, or null
 * @property locationStoreError location storage error, or null
 * @property question open crossing question, or null
 * @property log recent robot log lines
 * @property message answer to the last command, or null
 */
@Serializable
data class NavState(
    val ok: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
    val map: String? = null,
    val localized: Boolean? = null,
    val sdkControl: Boolean = false,
    val navState: String = "",
    val moving: Boolean = false,
    val speed: String = "DEFAULT",
    val speedLabel: String = "",
    val measuredSpeed: String? = null,
    val remoteControl: String? = null,
    val now: Long = 0,
    val pose: NavPose? = null,
    val selected: NavPoint? = null,
    val places: List<NavPoint> = emptyList(),
    val points: List<NavPoint> = emptyList(),
    val areas: List<NavArea> = emptyList(),
    val privacyMargin: Double = 0.0,
    val drawing: List<NavCorner>? = null,
    val areasLoaded: Boolean = false,
    val areaStoreError: String? = null,
    val areaSaveWarning: String? = null,
    val locationStoreError: String? = null,
    val question: NavQuestion? = null,
    val log: List<String> = emptyList(),
    val message: String? = null
)

/**
 * Where the map picture sits in the robot's world.
 * @property ok whether the request succeeded
 * @property name map name, or null
 * @property widthPx picture width
 * @property heightPx picture height
 * @property resolution metres per pixel
 * @property minX lowest x
 * @property maxX highest x
 * @property minY lowest y
 * @property maxY highest y
 * @property error error text, or null
 */
@Serializable
data class NavMapInfo(
    val ok: Boolean = false,
    val name: String? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val resolution: Double = 0.05,
    val minX: Double = 0.0,
    val maxX: Double = 0.0,
    val minY: Double = 0.0,
    val maxY: Double = 0.0,
    val error: String? = null
)

/** What the phone currently knows about the connection to the robot server. */
sealed class NavConnection {
    /** Nothing tried yet or the first answer is still on its way. */
    object Connecting : NavConnection()
    /** The robot answered the last request. */
    object Online : NavConnection()

    /**
     * The robot server could not be found.
     * @property reason reason
     * @property since time of the last success
     */
    data class Offline(val reason: String, val since: Long) : NavConnection()

    /**
     * The robot answered but refused.
     * @property code HTTP status
     * @property reason reason
     */
    data class Refused(val code: Int, val reason: String) : NavConnection()
}

/**
 * Polls the robot's navigation state every [POLL_MS] and sends commands. While the robot cannot be reached, the last
 * state stays visible and polling slows down to [RETRY_MS].
 * @param api connection to the robot
 */
class NavigationClient(private val api: RobotAPI) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<NavState?>(null)
    val state: StateFlow<NavState?> = _state.asStateFlow()

    private val _mapInfo = MutableStateFlow<NavMapInfo?>(null)
    val mapInfo: StateFlow<NavMapInfo?> = _mapInfo.asStateFlow()

    private val _mapImage = MutableStateFlow<Bitmap?>(null)
    val mapImage: StateFlow<Bitmap?> = _mapImage.asStateFlow()

    private val _connection = MutableStateFlow<NavConnection>(NavConnection.Connecting)
    val connection: StateFlow<NavConnection> = _connection.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Clears the command answer after the screen has shown it. */
    fun clearMessage() { _message.value = null }

    @Volatile
    private var retryNow = false

    /** Asks [run] to try again at once instead of waiting for the next offline retry. */
    fun retry() { retryNow = true }

    /** Runs until the coroutine is cancelled (i.e. until the screen closes). */
    suspend fun run() {
        while (true) {
            val fresh = refresh()
            var waited = 0L
            val wait = if (fresh) POLL_MS else RETRY_MS
            while (waited < wait && !retryNow) {
                delay(STEP_MS)
                waited += STEP_MS
            }
            retryNow = false
        }
    }

    /**
     * Fetches the state, and the map picture when needed.
     * @return true if the robot answered
     */
    private suspend fun refresh(): Boolean {
        when (val answer = api.navState()) {
            is RobotAPI.NavCall.Ok -> {
                val parsed = runCatching { json.decodeFromString<NavState>(answer.value) }.getOrElse {
                    Log.w(TAG, "state could not be read: $it")
                    _connection.value = NavConnection.Refused(0, "the robot's answer could not be read")
                    return true
                }
                _state.value = parsed
                _connection.value = NavConnection.Online
                if (parsed.running) loadMapIfNeeded(parsed.map)
                return true
            }
            is RobotAPI.NavCall.Unreachable -> {
                val before = _connection.value
                if (before !is NavConnection.Offline) {
                    _connection.value = NavConnection.Offline(answer.reason, System.currentTimeMillis())
                }
                return false
            }
            is RobotAPI.NavCall.Refused -> {
                _connection.value = NavConnection.Refused(answer.code, answer.reason)
                return true
            }
        }
    }

    /**
     * Loads the map picture when the map name or size changes.
     * @param mapName name of the robot's current map
     */
    private suspend fun loadMapIfNeeded(mapName: String?) {
        if (mapName == null) return
        if (_mapInfo.value?.name == mapName && _mapImage.value != null) return
        val info = (api.navMapInfo() as? RobotAPI.NavCall.Ok)?.value
            ?.let { runCatching { json.decodeFromString<NavMapInfo>(it) }.getOrNull() } ?: return
        if (!info.ok) return
        val bytes = (api.navMapImage() as? RobotAPI.NavCall.Ok)?.value ?: return
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return
        _mapInfo.value = info
        _mapImage.value = bitmap
    }

    /** Throws away the map picture so the next poll fetches it again (after "Reload map"). */
    fun forgetMap() {
        _mapInfo.value = null
        _mapImage.value = null
    }

    /**
     * Sends one command and takes the new state from the answer; a refusal is shown as [message].
     * @param action command name
     * @param build adds the command's parameters
     */
    suspend fun command(action: String, build: JsonObjectBuilderScope.() -> Unit = {}) {
        val body = buildJsonObject {
            put("action", action)
            JsonObjectBuilderScope(this).build()
        }.toString()
        when (val answer = api.navCommand(body)) {
            is RobotAPI.NavCall.Ok -> {
                val parsed = runCatching { json.decodeFromString<NavState>(answer.value) }.getOrNull()
                if (parsed != null) {
                    _state.value = parsed
                    _connection.value = NavConnection.Online
                    if (parsed.running) loadMapIfNeeded(parsed.map)
                }
            }
            is RobotAPI.NavCall.Refused -> {
                _message.value = readMessage(answer.reason)
                    ?: UiText.get("nav.message.refused", "reason" to answer.reason)
                _connection.value = NavConnection.Online
                retry()
            }
            is RobotAPI.NavCall.Unreachable -> {
                _message.value = UiText.get("nav.message.unreachable", "reason" to answer.reason)
                _connection.value = NavConnection.Offline(answer.reason, System.currentTimeMillis())
            }
        }
        (_state.value?.message)?.let { _message.value = it }
    }

    /**
     * Pulls the robot's own wording out of a refusal body.
     * @param body response body
     * @return the robot's wording, or null
     */
    private fun readMessage(body: String): String? = try {
        val obj = json.parseToJsonElement(body).jsonObject
        val field = obj["message"] ?: obj["error"]
        (field as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (e: Exception) {
        null
    }

    /**
     * Small wrapper so screen code can add command parameters without importing the serialization builder.
     * @property builder underlying JSON builder
     */
    class JsonObjectBuilderScope(private val builder: kotlinx.serialization.json.JsonObjectBuilder) {
        /**
         * Adds the name of a place, location or area.
         * @param value name
         * @return the previous value of this key, or null
         */
        fun name(value: String) = builder.put("name", value)
        /**
         * Adds map coordinates (metres).
         * @param x x in metres
         * @param y y in metres
         */
        fun at(x: Double, y: Double) { builder.put("x", x); builder.put("y", y) }
        /**
         * Adds a speed preset.
         * @param value preset name
         * @return the previous value of this key, or null
         */
        fun preset(value: String) = builder.put("preset", value)
        /**
         * Adds the id of the crossing question being answered.
         * @param value question id
         * @return the previous value of this key, or null
         */
        fun id(value: Long) = builder.put("id", value)
        /**
         * Adds a number of minutes (crossing permission).
         * @param value number of minutes
         * @return the previous value of this key, or null
         */
        fun minutes(value: Int) = builder.put("minutes", value)
    }

    companion object {
        private const val TAG = "NavigationClient"
        const val POLL_MS = 500L
        const val RETRY_MS = 3000L
        private const val STEP_MS = 100L
    }
}
