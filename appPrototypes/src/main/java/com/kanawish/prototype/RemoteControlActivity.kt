package com.kanawish.prototype

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import com.jakewharton.rxbinding2.widget.textChanges
import com.jakewharton.rxrelay2.BehaviorRelay
import com.kanawish.robot.Command
import com.kanawish.socket.BITMAPS_SERVICE
import com.kanawish.socket.COMMANDS_SERVICE
import com.kanawish.socket.SERVICE_TYPE
import com.kanawish.socket.buildDiscoveryListener
import com.kanawish.socket.buildRegistrationListener
import com.kanawish.socket.buildServerSocket
import com.kanawish.socket.discoverService
import com.kanawish.socket.findNsdManager
import com.kanawish.socket.receiveBitmaps
import com.kanawish.socket.registerService
import com.kanawish.socket.sendCommand
import com.kanawish.socket.stopServiceDiscovery
import com.kanawish.socket.unregisterService
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import kotlinx.android.synthetic.main.controller_ui.backwardButton
import kotlinx.android.synthetic.main.controller_ui.durationEditText
import kotlinx.android.synthetic.main.controller_ui.forwardButton
import kotlinx.android.synthetic.main.controller_ui.imageView
import kotlinx.android.synthetic.main.controller_ui.leftButton
import kotlinx.android.synthetic.main.controller_ui.rightButton
import kotlinx.android.synthetic.main.controller_ui.scanButton
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Simpler "phone in hand" remote control Activity.
 * Sends joystick readings as commands to the Robot.
 */
@SuppressLint("SetTextI18n")
class RemoteControlActivity : Activity() {

    // Bind server socket on random port.
    private val serverSocket by lazy {
        buildServerSocket()
    }
    private val registrationListener by lazy {
        buildRegistrationListener()
    }

    private val outgoingCommands = BehaviorRelay.create<Command>()
    private val discoveryListener by lazy {
        findNsdManager()
            .buildDiscoveryListener(COMMANDS_SERVICE) { discoveredService ->
                disposables += outgoingCommands.subscribe { command ->
                    sendCommand(discoveredService, command)
                }
            }
    }

    private val disposables = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.controller_ui)
    }

    override fun onResume() {
        super.onResume()
        registerService(SERVICE_TYPE, BITMAPS_SERVICE, serverSocket.localPort, registrationListener)
        discoverService(discoveryListener)

        // Incoming Bitmap processing.
        disposables += serverSocket.receiveBitmaps() // InetSocketAddress(SERVER_IP, PORT_BM)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(imageView::setImageBitmap)

        // This setup hooks up the UI buttons to send commands to the robot
        var duration = 1L
        disposables += durationEditText.textChanges()
            .filter { it.isNotBlank() }
            .map { charSequence -> charSequence.toString().toLong() }
            .subscribe { duration = it }

        forwardButton.setOnClickListener { send(Command(duration, -128, -128)) }
        rightButton.setOnClickListener { send(Command(duration, 128, -128)) }
        backwardButton.setOnClickListener { send(Command(duration, 128, 128)) }
        leftButton.setOnClickListener { send(Command(duration, -128, 128)) }
        scanButton.setOnClickListener { send(Command(duration, 0, 0)) }
    }

    private fun send(command: Command) = outgoingCommands.accept(command)

    override fun onPause() {
        super.onPause()
        unregisterService(registrationListener)
        stopServiceDiscovery(discoveryListener)
        disposables.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        serverSocket.close()
    }


    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val y = event.getAxisValue(MotionEvent.AXIS_Y) // (Y left analog)
        val rz = event.getAxisValue(MotionEvent.AXIS_RZ) // (Y right analog)

        send(Command(10000, calcDrive(y), calcDrive(rz)))
        return true
    }

    /** Massage values received from joystick. */
    fun calcDrive(axis: Float): Int = deadZone(clamp(-axis * 255))

    /** Limit possible values. */
    fun clamp(result: Float) = Math.min(Math.max(-255, result.roundToInt()), 255)

    /** Ignore joysticks at rest. */
    fun deadZone(result: Int) = if (result.absoluteValue > 8) result else 0

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return true
    }
}