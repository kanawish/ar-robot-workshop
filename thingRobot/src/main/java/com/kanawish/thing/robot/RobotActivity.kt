package com.kanawish.thing.robot

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import com.google.android.things.contrib.driver.motorhat.MotorHat
import com.google.android.things.pio.PeripheralManager
import com.jakewharton.rxrelay2.BehaviorRelay
import com.kanawish.socket.BITMAPS_SERVICE
import com.kanawish.socket.COMMANDS_SERVICE
import com.kanawish.socket.SERVICE_TYPE
import com.kanawish.socket.buildDiscoveryListener
import com.kanawish.socket.buildRegistrationListener
import com.kanawish.socket.buildServerSocket
import com.kanawish.socket.discoverService
import com.kanawish.socket.findNsdManager
import com.kanawish.socket.receiveCommand
import com.kanawish.socket.registerService
import com.kanawish.socket.sendBitmapByteArray
import com.kanawish.socket.stopServiceDiscovery
import com.kanawish.socket.unregisterService
import com.kanawish.utils.camera.CameraHelper
import com.kanawish.utils.camera.VideoHelper
import com.kanawish.utils.camera.dumpFormatInfo
import com.kanawish.utils.camera.toImageAvailableListener
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
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
    // Bind server socket on random port.
    private val serverSocket: ServerSocket by lazy {
        buildServerSocket()
    }
    private val registrationListener by lazy {
        buildRegistrationListener()
    }

    private val outgoingImages = BehaviorRelay.create<ByteArray>()
    private val discoveryListener by lazy {
        findNsdManager()
            .buildDiscoveryListener(BITMAPS_SERVICE) { discoveredService ->
                disposables += outgoingImages.subscribe { byteArray ->
                    sendBitmapByteArray(discoveredService, byteArray)
                }
            }
    }

    @Inject lateinit var cameraHelper: CameraHelper
    @Inject lateinit var videoHelper: VideoHelper

    /**
     * Using Relays simplifies conversion of data to streams.
     */
    private val capturedImages = BehaviorRelay.create<ByteArray>()

    private var disposables: CompositeDisposable = CompositeDisposable()

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

    override fun onResume() {
        Timber.w("onResume()")
        super.onResume()
        registerService(SERVICE_TYPE, COMMANDS_SERVICE, serverSocket.localPort, registrationListener)
        discoverService(discoveryListener)

        // Each frame received from Camera2 API will be processed by ::onPictureTaken
        videoHelper.startVideoCapture(::onPictureTaken.toImageAvailableListener())

        // Whenever a new image frame is published (by ::onPictureTake), we send it over the network.
        disposables += capturedImages
            .throttleLast(333, TimeUnit.MILLISECONDS)
            .subscribe {
                // Timber.d("Sending image data [${it.size} bytes]")
                outgoingImages.accept(it)
            }

        // Our stream of commands.
        disposables += serverSocket.receiveCommand()
            // SwitchMap will drop previous commands in favor of latest one.
            .switchMap { cmd ->
                // This programs the "timed-release" of the left and right wheel drives.
                Observable.concat(
                        Observable.just({
                            Timber.d("drive(Command(${cmd.duration}, ${cmd.left}, ${cmd.right}))")
                            motorHat.drive(cmd.left, cmd.right)
                        }),
                        Observable.just({
                            Timber.d("releaseAll(${cmd.duration}, ${cmd.left}, ${cmd.right})")
                            motorHat.releaseAll()
                        }).delay(cmd.duration, TimeUnit.MILLISECONDS)
                )
            }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { it() }

    }

    override fun onPause() {
        super.onPause()
        unregisterService(registrationListener)
        stopServiceDiscovery(discoveryListener)
    }

    override fun onDestroy() {
        Timber.i("onDestroy()")
        super.onDestroy()
        serverSocket.close()

        safeClose("Problem closing camera %s", cameraHelper::closeCamera)
        safeClose("Problem closing motorHat %s", motorHat::close)
    }

    // Send pictures as nearbyManager payloads.
    private fun onPictureTaken(imageBytes: ByteArray) {
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size).also {
            val outputStream = ByteArrayOutputStream()

            // Forced downgrading of bitmap to emphasize speed, add quality when everything works.
            Bitmap.createScaledBitmap(it, 160, 120, false)
                .compress(Bitmap.CompressFormat.PNG, 100, outputStream)

            // Push new image into our Relay
            capturedImages.accept(outputStream.toByteArray())
            // Also push it in parallel to the image data socket

            outputStream.close()
        }
    }

}