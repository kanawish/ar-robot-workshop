package com.kanawish.thing.robot

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import com.google.android.things.contrib.driver.motorhat.MotorHat
import com.google.android.things.pio.PeripheralManager
import com.jakewharton.rxrelay2.BehaviorRelay
import com.kanawish.robot.Telemetry
import com.kanawish.socket.*
import com.kanawish.utils.camera.CameraHelper
import com.kanawish.utils.camera.VideoHelper
import com.kanawish.utils.camera.dumpFormatInfo
import com.kanawish.utils.camera.toImageAvailableListener
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.withLatestFrom
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * @startuml
 * object Robot
 * object Camera
 * object MotorDrive {
 * wheels[4]
 * }
 * Robot *.. Camera
 * Robot *.. MotorDrive
 * @enduml
 *
 * @startuml
 * "Robot Client" --> "Controller Server": telemetry
 * @enduml
 *
 * @startuml
 * "Robot Server" <-- "Controller Client": command
 * @enduml
 *
 * @startuml
 * class RobotActivity {
 * <i>// Android Things Drivers
 * motorHat : MotorHat
 * ..
 * }
 * @enduml
 *
 */
class RobotActivity : Activity() {

    @Inject lateinit var cameraHelper: CameraHelper
    @Inject lateinit var videoHelper: VideoHelper

    @Inject lateinit var networkClient: NetworkClient // Output telemetry
    @Inject lateinit var server: NetworkServer // Input commands

    /**
     * MotorHat is a contributed driver for LadyAda's Motor Hat
     */
    private val motorHat: MotorHat by lazy {
        try {
            MotorHat(currentDevice().i2cBus())
        } catch (e: IOException) {
            throw RuntimeException("Failed to create MotorHat", e)
        }
    }

    private val manager by lazy {
        PeripheralManager.getInstance()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.d("${manager.gpioList}")

        Timber.d("${manager.pwmList}")
        Timber.d("${manager.i2cBusList}")
        Timber.d("${manager.spiBusList}")
        Timber.d("${manager.uartDeviceList}")

        // Dump Camera Diagnostics to logcat.
        dumpFormatInfo()
    }

    override fun onDestroy() {
        Timber.i("onDestroy()")
        super.onDestroy()

        safeClose("Problem closing camera %s", cameraHelper::closeCamera)
        safeClose("Problem closing motorHat %s", motorHat::close)
    }

}