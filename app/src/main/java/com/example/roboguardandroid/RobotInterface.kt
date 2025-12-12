package com.example.roboguardandroid

class RobotInterface {
    var paired: Boolean = false
    var robot_ip: String? = null
    var robot_API: RobotAPI? = null

    suspend internal fun init_robot(rob_ip: String){
        this.robot_ip = robot_ip
        val rob_API = RobotAPI(rob_ip)
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