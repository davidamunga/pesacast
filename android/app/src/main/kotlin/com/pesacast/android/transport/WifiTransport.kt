package com.pesacast.android.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.pesacast.android.model.MpesaTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet

private const val TAG = "WifiTransport"

const val WIFI_DEFAULT_PORT = 7877
private const val NSD_SERVICE_TYPE = "_pesacast._tcp."
private const val MAX_PENDING = 50

class WifiTransport(private val context: Context) {

    private val gson = Gson()

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    val state: StateFlow<TransportState> = _state.asStateFlow()

    private var server: PesaCastWsServer? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    private val authenticatedClients = CopyOnWriteArraySet<WebSocket>()
    private val pendingQueue = ArrayDeque<String>()
    private val queueLock = Any()

    // MARK: - Public API

    fun start(token: String, port: Int = WIFI_DEFAULT_PORT) {
        stop()
        try {
            val srv = PesaCastWsServer(
                port = port,
                token = token,
                authenticatedClients = authenticatedClients,
                onClientAuthenticated = { count ->
                    _state.value = TransportState.Connected(count)
                    drainQueue()
                },
                onClientDisconnected = { count ->
                    _state.value = if (count > 0) TransportState.Connected(count)
                                   else TransportState.Connecting
                }
            )
            srv.start()
            server = srv
            _state.value = TransportState.Connecting
            Log.i(TAG, "WiFi WebSocket server started on port $port")
            registerNsd(port)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WiFi transport: ${e.message}")
            _state.value = TransportState.Disconnected
        }
    }

    fun stop() {
        unregisterNsd()
        try {
            server?.stop(500)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping WS server: ${e.message}")
        }
        server = null
        authenticatedClients.clear()
        synchronized(queueLock) { pendingQueue.clear() }
        _state.value = TransportState.Disconnected
        Log.i(TAG, "WiFi transport stopped")
    }

    fun sendTransaction(txn: MpesaTransaction) {
        // Server not running (WiFi disabled): nothing to deliver to, and nothing to queue for.
        if (server == null) return

        val json = gson.toJson(txn)
        val openClients = authenticatedClients.filter { it.isOpen }
        if (openClients.isEmpty()) {
            Log.d(TAG, "WiFi: no authenticated clients — queuing")
            synchronized(queueLock) {
                if (pendingQueue.size >= MAX_PENDING) pendingQueue.removeFirst()
                pendingQueue.addLast(json)
            }
            return
        }
        for (client in openClients) {
            client.send(json)
        }
        Log.d(TAG, "WiFi: sent transaction to ${openClients.size} client(s)")
    }

    // MARK: - NSD (mDNS)

    private fun registerNsd(port: Int) {
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = mgr

        val info = NsdServiceInfo().apply {
            serviceName = "PesaCast"
            serviceType = NSD_SERVICE_TYPE
            setPort(port)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "mDNS registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "mDNS unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "mDNS unregistration failed: $code")
            }
        }
        nsdListener = listener
        mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregisterNsd() {
        val mgr = nsdManager ?: return
        val listener = nsdListener ?: return
        try {
            mgr.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering NSD: ${e.message}")
        }
        nsdManager = null
        nsdListener = null
    }

    private fun drainQueue() {
        val pending = synchronized(queueLock) {
            pendingQueue.toList().also { pendingQueue.clear() }
        }
        val openClients = authenticatedClients.filter { it.isOpen }
        for (json in pending) {
            for (client in openClients) {
                client.send(json)
            }
        }
    }

    // MARK: - Token generation

    companion object {
        fun generateToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

/**
 * WebSocket server that enforces private-IP filtering and token authentication before
 * accepting any client into the [authenticatedClients] set. Transaction data is only
 * ever sent to authenticated clients.
 */
private class PesaCastWsServer(
    port: Int,
    private val token: String,
    private val authenticatedClients: CopyOnWriteArraySet<WebSocket>,
    private val onClientAuthenticated: (count: Int) -> Unit,
    private val onClientDisconnected: (count: Int) -> Unit,
) : WebSocketServer(InetSocketAddress(port)) {

    init {
        isReuseAddr = true
        connectionLostTimeout = 30
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val remote = conn.remoteSocketAddress?.address
        if (remote == null || !isPrivateIp(remote)) {
            Log.w(TAG, "Rejected connection from non-private IP: $remote")
            conn.close(1008, "Forbidden")
            return
        }
        Log.d(TAG, "New connection from $remote — awaiting auth")
        // Client not added to authenticatedClients yet; waits for {"auth":"TOKEN"}
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if (authenticatedClients.contains(conn)) return // already authenticated

        try {
            val obj = JsonParser.parseString(message).asJsonObject
            val incoming = obj.get("auth")?.asString
            if (incoming == token) {
                authenticatedClients.add(conn)
                conn.send("""{"status":"ok"}""")
                Log.i(TAG, "Client authenticated: ${conn.remoteSocketAddress}")
                onClientAuthenticated(authenticatedClients.size)
            } else {
                Log.w(TAG, "Bad auth token from ${conn.remoteSocketAddress} — closing")
                conn.close(1008, "Unauthorized")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Malformed auth message from ${conn.remoteSocketAddress}: ${e.message}")
            conn.close(1008, "Bad request")
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val wasAuthenticated = authenticatedClients.remove(conn)
        if (wasAuthenticated) {
            Log.i(TAG, "Authenticated client disconnected: ${conn.remoteSocketAddress} (${authenticatedClients.size} remaining)")
            onClientDisconnected(authenticatedClients.size)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error for ${conn?.remoteSocketAddress}: ${ex.message}")
    }

    override fun onStart() {
        Log.i(TAG, "PesaCast WebSocket server listening on port ${address.port}")
    }

    /**
     * Accepts only RFC-1918 private addresses and loopback.
     * Rejects any public IP even if somehow reachable (e.g. via port forwarding).
     */
    private fun isPrivateIp(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        val b = address.address
        if (b.size != 4) return true // IPv6 link-local — allow
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return b0 == 10 ||
            (b0 == 172 && b1 in 16..31) ||
            (b0 == 192 && b1 == 168)
    }
}
