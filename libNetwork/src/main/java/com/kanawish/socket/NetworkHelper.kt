package com.kanawish.socket

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.kanawish.robot.Command
import com.kanawish.robot.Telemetry
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.inject.Inject
import javax.inject.Singleton


//const val HOST_PHONE_ADDRESS = "10.11.94.193" // [for RobotActivity] Pixel 2 Remote on ATR
//const val HOST_P2_ADDRESS = "192.168.43.60" // [for ARRemoteActivity] Pixel 2 on ATR
const val HOST_PHONE_ADDRESS = "192.168.43.1"    // [for RobotActivity] Nexus 5 Remote on ATR
const val ROBOT_ADDRESS = "192.168.43.87" // Robot on ATR

const val PORT_CMD = 60123
const val PORT_TM = 60124
const val PORT_BM = 60125


fun ByteArray.toBitmap(): Bitmap {
    return BitmapFactory.decodeByteArray(this, 0, this.size)
}

/**
 * Common service type for robot and controller.
 */
const val SERVICE_TYPE = "_robot._tcp"

/**
 * Defaults to port 0, auto-allocated.
 *
 * @param port to assign, 0 being auto-allocation.
 */
fun buildServerSocket(port: Int = 0): ServerSocket {
    // Auto assign port
    return ServerSocket(0).also { socket ->
        Timber.d("Got port ${socket.localPort} allocated.")
    }
}

/**
 * Convenience function to lookup NsdManager system service.
 */
fun Context.findNsdManager() = getSystemService(Context.NSD_SERVICE) as NsdManager

/**
 * Register a given serviceInfo with the NsdManager.
 *
 * @param serviceInfo the info to register
 * @param registeredHandler optional `registered` handler.
 */

fun Context.registerService(
    serviceType: String,
    desiredServiceName: String,
    localPort:Int,
    registrationListener: NsdManager.RegistrationListener
) {
    // Build service info for advertising purposes.
    val serviceInfo = buildServiceInfo(
            serviceType,
            desiredServiceName,
            localPort
    )

    // Advertise the server socket (with automatically assigned port)
    findNsdManager().registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener
    )
}

fun Context.unregisterService(registrationListener: NsdManager.RegistrationListener) {
    findNsdManager().unregisterService(registrationListener)
}

/**
 * Create the NsdServiceInfo object, and populate it.
 */
fun buildServiceInfo(serviceType: String, serviceName: String, localPort: Int) =
    NsdServiceInfo().apply {
        // The name is subject to change based on conflicts
        // with other services advertised on the same network.
        this.serviceName = serviceName
        this.serviceType = serviceType
        this.port = localPort
    }

fun buildRegistrationListener(registeredHandler: (NsdServiceInfo) -> Unit = {}) =
    object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            // NOTE: Android may have changed service name in order to resolve a conflict.
            Timber.d("onServiceRegistered($serviceInfo)")
            registeredHandler(serviceInfo)
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // Registration failed! Put debugging code here to determine why.
            Timber.d("onRegistrationFailed($serviceInfo, $errorCode)")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            // Service has been unregistered. This only happens when you call
            // NsdManager.unregisterService() and pass in this listener.
            Timber.d("onServiceUnregistered($serviceInfo)")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // Unregistration failed. Put debugging code here to determine why.
            Timber.d("onUnregistrationFailed($serviceInfo, $errorCode)")
        }
    }

const val BITMAPS_SERVICE = "bitmaps"
fun ServerSocket.receiveBitmaps() = receiver(::bitmapMapper)

const val COMMANDS_SERVICE = "commands"
fun ServerSocket.receiveCommand() = receiver(::commandMapper)

const val TELEMETRY_SERVICE = "telemetry"
fun ServerSocket.receiveTelemetry() = receiver(::telemetryMapper)

/**
 * Uses NsdManager to advertise a server socket over Wifi.
 *
 * Takes all incoming requests, uses the provided mapper
 * function to build an observable of T received from client.
 *
 * @param serviceType top level service type shared between client/server.
 * @param desiredServiceName a service name used by clients to find server.
 *
 * @return an Observable<T> of received content.
 */
fun <T> ServerSocket.receiver(mapper: (ObjectInputStream, ObjectOutputStream) -> T): Observable<T> {
    return toSocketObservable()
        .map { s ->
            val ois = ObjectInputStream(s.getInputStream())
            val oos = ObjectOutputStream(s.getOutputStream())
            mapper(ois, oos)
        }
}

fun bitmapMapper(
    input: ObjectInputStream,
    output: ObjectOutputStream
): Bitmap = mapper<ByteArray>(input, output).toBitmap()

fun commandMapper(
    input: ObjectInputStream,
    output: ObjectOutputStream
): Command = mapper(input, output)

fun telemetryMapper(
    input: ObjectInputStream,
    output: ObjectOutputStream
): Telemetry = mapper(input, output)

inline fun <reified T> mapper(input: ObjectInputStream, output: ObjectOutputStream): T {
    val received = input.readObject() as T
    input.close()
    output.close()
    return received
}

/**
 * Server Socket accept() new connections, emits the resulting sockets when
 * clients connect.
 *
 * subscribes on `Schedulers.io()`
 */
private fun ServerSocket.toSocketObservable(): Observable<Socket> {
    return Observable
        .create<Socket> { e ->
            var isStopped = false
            try {
                Timber.d("New server Observable<Socket> [$inetAddress : $localPort]")

                e.setCancellable {
                    Timber.d("Server Observable onCancel()/onDispose() [$inetAddress : $localPort]")
                    isStopped = true
                    try {
                        close()
                    } catch (e: IOException) {
                        Timber.e(e, "Error closing [$inetAddress : $localPort]")
                    }
                }

                try {
                    while (!isClosed) e.onNext(accept())
                } catch (e: Exception) {
                    if (isStopped) {
                        Timber.d("Server observable stopped [$inetAddress : $localPort]")
                    } else {
                        Timber.e(e, "Server exception [$inetAddress : $localPort]")
                    }
                } finally {
                    close()
                }

            } catch (t: Exception) {
                Timber.e(t, "Server top level server exception caught [$inetAddress : $localPort]")
            }
        }
        .subscribeOn(Schedulers.io())
}


fun Context.discoverService(discoveryListener: NsdManager.DiscoveryListener) {
    findNsdManager().discoverServices(
            SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
    )
}

fun Context.stopServiceDiscovery(discoveryListener: NsdManager.DiscoveryListener) {
    findNsdManager().stopServiceDiscovery(discoveryListener)
}

/**
 *
 * @param serviceName name of service we're looking for.
 * @param handler triggered when target service is found.
 */
fun NsdManager.buildDiscoveryListener(
    serviceName: String,
    serviceResolveHandler: (NsdServiceInfo) -> Unit
) = object : NsdManager.DiscoveryListener {
    // Called as soon as service discovery begins.
    override fun onDiscoveryStarted(regType: String) {
        Timber.d("Service discovery started : $regType")
    }

    override fun onServiceFound(service: NsdServiceInfo) {
        // A service was found! Do something with it.
        Timber.d("onServiceFound($service)")
        when {
            // Service type is the string containing the protocol and transport layer for this service.
            !service.serviceType.contains(SERVICE_TYPE) ->
                Timber.d("Looking for $SERVICE_TYPE in ${service.serviceType}")
            // The name of the service tells the user what they'd be
            // connecting to. It could be "Bob's Chat App".
            service.serviceName.contains( serviceName ) -> {
                Timber.d("Found serviceName $serviceName in ${service.serviceName}")
                resolveService(service, buildResolveListener(serviceResolveHandler))
            }
            else -> Timber.d("Didn't match desired serviceName: $serviceName")
        }
    }

    override fun onServiceLost(service: NsdServiceInfo) {
        // When the network service is no longer available.
        // Internal bookkeeping code goes here.
        Timber.e("Service lost: $service")
    }

    override fun onDiscoveryStopped(serviceType: String) {
        Timber.i("Discovery stopped: $serviceType")
    }

    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        Timber.e("Discovery failed: Error code:$errorCode")
        stopServiceDiscovery(this)
    }

    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
        Timber.e("Discovery failed: Error code:$errorCode")
        stopServiceDiscovery(this)
    }
}

private fun buildResolveListener(serviceResolveHandler: NsdServiceInfo.() -> Unit) =
    object : NsdManager.ResolveListener {
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            Timber.e("Resolve Succeeded. $serviceInfo")
            serviceInfo.serviceResolveHandler()
        }

        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // Called when the resolve fails. Use the error code to debug.
            Timber.e("Resolve failed: $errorCode")
        }
    }

fun sendBitmapByteArray(serviceInfo: NsdServiceInfo, bitmapByteArray: ByteArray) =
    send(serviceInfo, bitmapByteArray)
fun sendCommand(serviceInfo: NsdServiceInfo, command: Command) = send(serviceInfo, command)
fun sendTelemetry(serviceInfo: NsdServiceInfo, telemetry: Telemetry) = send(serviceInfo, telemetry)

fun <T> send(serviceInfo: NsdServiceInfo, payload: T): Disposable {
    Timber.i("$payload -> ${serviceInfo.host}, ${serviceInfo.port}")
    return Completable
        .create {
            val socket = Socket(serviceInfo.host, serviceInfo.port)

            val oos = ObjectOutputStream(socket.getOutputStream())
            val ois = ObjectInputStream(socket.getInputStream())

            oos.writeObject(payload)
            oos.flush()

            socket.close()
        }
        .subscribeOn(Schedulers.io())
        .subscribe({}, { t -> Timber.e(t, "Caught exception for this command upload.") })
}

// TODO: Melt for parts
@Singleton
class NetworkClient @Inject constructor() {

    fun sendCommand(serverAddress: String, command: Command): Disposable {
        Timber.i("$command -> $serverAddress")
        return Completable
            .create {
                val socket = Socket(serverAddress, PORT_CMD)

                val oos = ObjectOutputStream(socket.getOutputStream())
                val ois = ObjectInputStream(socket.getInputStream())

                oos.writeObject(command)
                oos.flush()

                socket.close()
            }
            .subscribeOn(Schedulers.io())
            .subscribe({}, { t -> Timber.e(t, "Caught exception for this command upload.") })
    }

    fun sendTelemetry(serverAddress: String, telemetry: Telemetry): Disposable {
        return Completable
            .create {
                val socket = Socket(serverAddress, PORT_TM)

                val oos = ObjectOutputStream(socket.getOutputStream())
                val ois = ObjectInputStream(socket.getInputStream())

                oos.writeObject(telemetry)
                oos.flush()

                socket.close()
            }
            .subscribeOn(Schedulers.io())
            .subscribe({}, { t -> Timber.e(t, "Caught exception for this telemetry upload.") })
    }

    fun sendImageData(serverAddress: String, byteArray: ByteArray): Disposable {
        return Completable
            .create {
                val socket = Socket(serverAddress, PORT_BM)

                val oos = ObjectOutputStream(socket.getOutputStream())
                val ois = ObjectInputStream(socket.getInputStream())

                oos.writeObject(byteArray)
                oos.flush()

                socket.close()
            }
            .subscribeOn(Schedulers.io())
            .subscribe({}, { t -> Timber.e(t, "Caught exception for this image upload.") })
    }

}

fun logIpAddresses() {
    try {
        val enumNetworkInterfaces = NetworkInterface.getNetworkInterfaces()
        enumNetworkInterfaces.toList().forEach { networkInterface ->
            networkInterface.inetAddresses.toList().forEach { address ->
                if (address.isSiteLocalAddress) {
                    Timber.d("SiteLocalAddress: ${address.hostAddress}")
                }
            }
        }
    } catch (e: SocketException) {
        Timber.d(e, "Caught a SocketException")
    }
}
