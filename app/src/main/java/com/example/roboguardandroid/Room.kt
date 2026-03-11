package com.example.roboguardandroid

import android.util.Log
import kotlin.collections.set


class room(val name: String, initialSensors: List<String> = emptyList()){
    var sensors: MutableMap<String, Boolean> = initialSensors.associateWith { true }.toMutableMap()
    
    var dont_follow = false

    internal fun can_follow(status: Boolean){
        this.dont_follow = status
    }

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
