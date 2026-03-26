package com.example.roboguardandroid

import android.util.Log
import kotlin.collections.set


/**
 * Represents a room in the house with its associated sensors and privacy settings.
 * @property name The name of the room.
 * @property initialSensors A list of sensor names available in this room.
 */
class room(val name: String, initialSensors: List<String> = emptyList()){
    /** A map of sensor names to their enabled/disabled status within this specific room. */
    var sensors: MutableMap<String, Boolean> = initialSensors.associateWith { true }.toMutableMap()
    
    /** Flag indicating if the robot is allowed to follow the user into this room. */
    var dont_follow = false

    /**
     * Updates the 'dont_follow' status for this room.
     * @param status True if the robot should NOT follow, false otherwise.
     */
    internal fun can_follow(status: Boolean){
        this.dont_follow = status
    }

    /**
     * Updates the status of a specific sensor in this room.
     * @param sensor The name of the sensor to update.
     * @param status The new status (enabled/disabled).
     */
    internal fun update_sensors(sensor: String, status: Boolean){
        if (sensor in this.sensors){
            this.sensors[sensor] = status
            Log.d("Sensor", "Sensor $sensor for room $name updated")
        }
        else{
            Log.e("Sensor", "Sensor $sensor for room $name not found")
        }
    }
}
