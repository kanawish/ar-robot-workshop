package com.kanawish.prototype

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import com.jakewharton.rxbinding2.widget.textChanges
import com.kanawish.robot.Command
import com.kanawish.socket.NetworkClient
import com.kanawish.socket.NetworkServer
import com.kanawish.socket.ROBOT_ADDRESS
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import kotlinx.android.synthetic.main.controller_ui.*
import javax.inject.Inject

/**
 * Simpler "phone in hand" remote control Activity.
 * Sends joystick readings as commands to the Robot.
 */
@SuppressLint("SetTextI18n")
class RemoteControlActivity : Activity() {

    @Inject lateinit var server: NetworkServer // Our input channel
    @Inject lateinit var client: NetworkClient // Our output channel

    private val disposables = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.controller_ui)
    }

    override fun onResume() {
        super.onResume()

        // Bitmap feed processing.
        disposables += server
            .receiveBitmaps() // InetSocketAddress(SERVER_IP, PORT_BM)
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

    fun send(command: Command) = client.sendCommand(ROBOT_ADDRESS, command)

    override fun onPause() {
        super.onPause()
        disposables.clear()
    }

}
