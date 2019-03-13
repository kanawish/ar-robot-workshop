package com.kanawish.prototype

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import com.kanawish.socket.NetworkClient
import com.kanawish.socket.NetworkServer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import kotlinx.android.synthetic.main.controller_ui.imageView
import javax.inject.Inject

class TestServerActivity : AppCompatActivity() {

    @Inject lateinit var server: NetworkServer // Our input channel
    @Inject lateinit var client: NetworkClient // Our output channel

    private val disposables = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_server)

    }

    override fun onResume() {
        super.onResume()
        // Bitmap feed processing.
        disposables += server
            .receiveBitmaps() // InetSocketAddress(SERVER_IP, PORT_BM)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(imageView::setImageBitmap)
    }

    override fun onPause() {
        super.onPause()
        disposables.clear()
    }

}
