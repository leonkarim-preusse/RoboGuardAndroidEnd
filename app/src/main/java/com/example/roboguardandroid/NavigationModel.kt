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
 * The robot's "Navigation and Map" screen, seen from the phone.
 *
 * The phone holds no navigation state of its own: it shows what the robot reports and sends commands back. The robot
 * remains the place where the privacy rules are checked — a phone that is out of range, switched off or simply wrong can
 * therefore never make the robot enter a private area.
 */

@Serializable
data class NavPoint(
    val name: String,
    val x: Double,
    val y: Double,
    /** A place known to RobotOS (from the map) rather than one saved in RoboGuard. */
    val saved: Boolean = false,
    /** Saved in RoboGuard and kept after a restart (as opposed to a point just tapped on the map). */
    val persistent: Boolean = false
)

@Serializable
data class NavPose(val x: Double, val y: Double, val theta: Double, val status: String = "")

@Serializable
data class NavCorner(val x: Double, val y: Double)

/** A private area. [allowedUntil] is set while the robot has a temporary permission to cross it (ms since epoch). */
@Serializable
data class NavArea(val name: String, val allowedUntil: Long? = null, val corners: List<NavCorner> = emptyList())

/** An open "may I cross this private area?" question of the robot; the phone may answer it like the robot's screen. */
@Serializable
data class NavQuestion(val id: Long, val area: String)

@Serializable
data class NavState(
    val ok: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
    val map: String? = null,
    val localized: Boolean? = null,
    val sdkControl: Boolean = false,
    val navState: String = "",
    val speed: String = "DEFAULT",
    val speedLabel: String = "",
    val measuredSpeed: String? = null,
    /** Name of the phone that sent the last command, while it counts as steering. */
    val remoteControl: String? = null,
    /** The robot's clock when it answered, so a countdown on the phone does not depend on the phone's clock. */
    val now: Long = 0,
    val pose: NavPose? = null,
    val selected: NavPoint? = null,
    val places: List<NavPoint> = emptyList(),
    val points: List<NavPoint> = emptyList(),
    val areas: List<NavArea> = emptyList(),
    val privacyMargin: Double = 0.0,
    /** Corners collected so far while an area is being drawn, null = not drawing. */
    val drawing: List<NavCorner>? = null,
    val areasLoaded: Boolean = false,
    val areaStoreError: String? = null,
    val areaSaveWarning: String? = null,
    val locationStoreError: String? = null,
    val question: NavQuestion? = null,
    val log: List<String> = emptyList(),
    /** Answer to the last command (refusal wording of the robot), only in a command answer. */
    val message: String? = null
)

/** Where the map picture sits in the robot's world, so the phone can draw markers and turn a tap into coordinates. */
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
    object Online : NavConnection()

    /**
     * The robot server could not be found: wrong WiFi, robot switched off, or its address changed.
     * [since] is the phone's clock when it first failed, so the screen can say how long it has been away.
     */
    data class Offline(val reason: String, val since: Long) : NavConnection()

    /** The robot answered but refused (403 = not in the local network, 401 = pairing no longer valid). */
    data class Refused(val code: Int, val reason: String) : NavConnection()
}

/**
 * Polls the robot's navigation state and sends commands. One instance per open screen.
 *
 * While the robot answers, the state is refreshed every [POLL_MS]; when it cannot be reached, the screen keeps the last
 * known state visible, shows that it is offline and retries more slowly ([RETRY_MS]) instead of hammering the network.
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

    /** Answer of the last command, shown once and then cleared by the screen. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() { _message.value = null }

    /** Forces a new attempt right away (the "Try again" button of the offline banner). */
    @Volatile
    private var retryNow = false

    fun retry() { retryNow = true }

    /** Runs until the coroutine is cancelled (i.e. until the screen closes). */
    suspend fun run() {
        while (true) {
            val fresh = refresh()
            var waited = 0L
            val wait = if (fresh) POLL_MS else RETRY_MS
            // Wait in small steps so "Try again" and a command answer take effect at once.
            while (waited < wait && !retryNow) {
                delay(STEP_MS)
                waited += STEP_MS
            }
            retryNow = false
        }
    }

    /** One state fetch (plus the map picture when it is missing or the map changed). Returns true if the robot answered. */
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

    /** Loads the map picture once per map; a "reload" on the robot changes the map name or its size, which re-triggers it. */
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
     * Sends one command and takes the state from its answer, so the screen reacts immediately instead of after the next
     * poll. A refusal from the robot (unknown name, not localized, areas not loaded) is shown as [message].
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
                _message.value = readMessage(answer.reason) ?: "The robot refused: ${answer.reason}"
                // A refused command still means the robot is there.
                _connection.value = NavConnection.Online
                retry()
            }
            is RobotAPI.NavCall.Unreachable -> {
                _message.value = "The robot could not be reached: ${answer.reason}"
                _connection.value = NavConnection.Offline(answer.reason, System.currentTimeMillis())
            }
        }
        (_state.value?.message)?.let { _message.value = it }
    }

    /** Pulls the robot's own wording out of a refusal body. */
    private fun readMessage(body: String): String? = try {
        val obj = json.parseToJsonElement(body).jsonObject
        val field = obj["message"] ?: obj["error"]
        (field as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (e: Exception) {
        null
    }

    /** Small wrapper so screen code can add command parameters without importing the serialization builder. */
    class JsonObjectBuilderScope(private val builder: kotlinx.serialization.json.JsonObjectBuilder) {
        fun name(value: String) = builder.put("name", value)
        fun at(x: Double, y: Double) { builder.put("x", x); builder.put("y", y) }
        fun preset(value: String) = builder.put("preset", value)
        fun id(value: Long) = builder.put("id", value)
        fun minutes(value: Int) = builder.put("minutes", value)
    }

    companion object {
        private const val TAG = "NavigationClient"
        /** Refresh while the robot answers. Position and drive state change fast enough to want this. */
        const val POLL_MS = 500L
        /** Retry while the robot cannot be found. */
        const val RETRY_MS = 3000L
        private const val STEP_MS = 100L
    }
}
