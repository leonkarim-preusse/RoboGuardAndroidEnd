package com.example.roboguardandroid

import android.util.Log
import kotlin.collections.set


class room(val name: String){
    var sensors: MutableMap<String, Boolean> = mutableMapOf(
        "Mikrofon" to true,
        "LIDAR" to true,
        "Collision" to true,
        "Kamera" to true,
        "Ultrasonic" to true

    )
    var dont_follow = false

    internal fun can_follow(status: Boolean){
        this.dont_follow = status
    }

    internal fun update_sensors(sensor: String,status: Boolean){
        if (sensor in this.sensors){
            this.sensors[sensor] = status
            Log.d("Sensor", "Sensor $sensor for room $this.name updated")
        }
        else{
            Log.e("Sensor", "Sensor $sensor for room $this.name not found")
            throw Exception("Sensor $sensor for room $this.name not found")

        }

    }


}