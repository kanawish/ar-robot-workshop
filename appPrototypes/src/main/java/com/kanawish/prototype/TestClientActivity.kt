package com.kanawish.prototype

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import com.jakewharton.rxrelay2.BehaviorRelay
import com.kanawish.permission.PermissionManager
import com.kanawish.socket.HOST_PHONE_ADDRESS
import com.kanawish.socket.NetworkClient
import com.kanawish.socket.NetworkServer
import com.kanawish.utils.camera.VideoHelper
import com.kanawish.utils.camera.dumpFormatInfo
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class TestClientActivity : AppCompatActivity() {

    @Inject lateinit var videoHelper: VideoHelper
    @Inject lateinit var networkClient: NetworkClient // Output telemetry
    @Inject lateinit var netWorkServer: NetworkServer // Input commands

    @Inject lateinit var permissionManager: PermissionManager

    /**
     * Using Relays simplifies conversion of data to streams.
     */
    private val images = BehaviorRelay.create<ByteArray>()

    private var disposables: CompositeDisposable = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_client)

        if (permissionManager.hasPermissions(Manifest.permission.CAMERA)) {
            dumpFormatInfo()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                permissionManager.requestPermissions(Manifest.permission.CAMERA)
            }
        }

    }

    override fun onResume() {
        super.onResume()

        // Here we send each video frame into our images Relay.
        videoHelper.startVideoCapture(images::accept)

        // At interval, we shrink the last image we received, send it over the network.
        disposables += images
//            .throttleLast(16, TimeUnit.MILLISECONDS)
            .map { shrinkImage(it) }
            .subscribe {
                networkClient.sendImageData(HOST_PHONE_ADDRESS, it)
            }
    }


    private fun shrinkImage(imageBytes: ByteArray): ByteArray {
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size).let { oldBitmap ->
            val outputStream = ByteArrayOutputStream()

            // Forced downgrading of bitmap to emphasize speed, add quality when everything works.
            Bitmap
                .createScaledBitmap(oldBitmap, 160, 120, false)
                .compress(Bitmap.CompressFormat.PNG, 100, outputStream)

            outputStream.toByteArray().also { outputStream.close() }
        }
    }

    override fun onPause() {
        super.onPause()
        disposables.clear()
        videoHelper.closeCamera()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        permissionManager.handleRequestPermissionResult(
                requestCode,
                permissions,
                grantResults,
                this::dumpFormatInfo
        )
    }

}
