package com.example.roboguardandroid

import android.content.Context

/**
 * Interface layer for managing robot connection status and API initialization.
 */
class RobotInterface {
    /** Indicates if the robot is currently paired and reachable. */
    var paired: Boolean = false
    /** The IP address of the robot. */
    var robot_ip: String? = null
    /** Reference to the [RobotAPI] instance for communication. */
    var robot_API: RobotAPI? = null

    /**
     * Initializes the robot API and checks connection by pinging the robot.
     * @param rob_ip The IP address to connect to.
     * @param context Android context for API initialization.
     * @throws IllegalStateException If the robot cannot be reached.
     */
    suspend internal fun init_robot(rob_ip: String, context: Context){
        this.robot_ip = robot_ip
        val rob_API = RobotAPI(context)
        if (rob_API.pingRobot() == "alive"){
            this.paired = true
            this.robot_API = rob_API
        }
        else{
            throw IllegalStateException("cannot connect to robot, please retry")
        }
        paired = true

    }
}