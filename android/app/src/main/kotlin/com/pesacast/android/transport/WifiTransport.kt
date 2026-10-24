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
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val TAG = "WifiTransport"

const val WIFI_DEFAULT_PORT = 7877
private const val NSD_SERVICE_TYPE = "_pesacast._tcp."
private const val MAX_PENDING = 50
private const val AUTH_TIMEOUT_MS = 5_000L

class WifiTransport(private val context: Context) {

    private val gson = Gson()

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    val state: StateFlow<TransportState> = _state.asStateFlow()

    /** Set when the server could not start (e.g. port already in use); cleared on next start/stop. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var server: PesaCastWsServer? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    private val authenticatedClients = CopyOnWriteArraySet<WebSocket>()
    private val pendingQueue = ArrayDeque<String>()
    private val queueLock = Any()

    // MARK: - Public API

    fun start(token: String, port: Int = WIFI_DEFAULT_PORT, allowLegacy: () -> Boolean = { false }) {
        stop()
        _error.value = null
        try {
            val srv = PesaCastWsServer(
                port = port,
                token = token,
                authenticatedClients = authenticatedClients,
                allowLegacy = allowLegacy,
                onClientAuthenticated = { count ->
                    _state.value = TransportState.Connected(count)
                    drainQueue()
                },
                onClientDisconnected = { count ->
                    _state.value = if (count > 0) TransportState.Connected(count)
                                   else TransportState.Connecting
                },
                onServerError = { ex ->
                    // Bind failures arrive asynchronously via onError, not from start().
                    Log.e(TAG, "WiFi server failed: ${ex.message}")
                    _error.value = ex.message ?: "WiFi server failed"
                    _state.value = TransportState.Disconnected
                }
            )
            srv.start()
            server = srv
            _state.value = TransportState.Connecting
            Log.i(TAG, "WiFi WebSocket server started on port $port")
            registerNsd(port)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WiFi transport: ${e.message}")
            _error.value = e.message ?: "Failed to start WiFi transport"
            _state.value = TransportState.Disconnected
        }
    }

    fun stop() {
        unregisterNsd()
        try {
            server?.shutdownTimers()
            server?.stop(500)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping WS server: ${e.message}")
        }
        server = null
        authenticatedClients.clear()
        synchronized(queueLock) { pendingQueue.clear() }
        _error.value = null
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
        server?.sendToAuthenticated(json)
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
        for (json in pending) server?.sendToAuthenticated(json)
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
 *
 * Two protocols share the port (docs/protocol.md): v2 clients negotiate an encrypted channel with
 * `{"v":2,"hello":...}`; legacy v1 clients send `{"auth":"<token>"}` in the clear and are only
 * accepted while [allowLegacy] returns true.
 */
internal class PesaCastWsServer(
    port: Int,
    private val token: String,
    private val allowLegacy: () -> Boolean = { false },
    private val authenticatedClients: CopyOnWriteArraySet<WebSocket>,
    private val onClientAuthenticated: (count: Int) -> Unit,
    private val onClientDisconnected: (count: Int) -> Unit,
    private val onServerError: (Exception) -> Unit,
    private val authTimeoutMs: Long = AUTH_TIMEOUT_MS,
    private val guard: AuthGuard = AuthGuard(token),
) : WebSocketServer(InetSocketAddress(port)) {

    private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val sessions = ConcurrentHashMap<WebSocket, WifiSession>()

    init {
        isReuseAddr = true
        connectionLostTimeout = 30
    }

    fun shutdownTimers() = timers.shutdownNow()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val remote = conn.remoteSocketAddress?.address
        if (remote == null || !PrivateIp.isAllowed(remote)) {
            Log.w(TAG, "Rejected connection from non-private IP: $remote")
            conn.close(1008, "Forbidden")
            return
        }
        if (guard.isBlocked(remote.hostAddress.orEmpty())) {
            Log.w(TAG, "Rejected $remote: too many failed auth attempts")
            conn.close(1008, "Too many attempts")
            return
        }
        Log.d(TAG, "New connection from $remote — awaiting auth")
        // Client not added to authenticatedClients yet; waits for {"auth":"TOKEN"}.
        // Drop sockets that never authenticate so they can't be held open indefinitely.
        try {
            timers.schedule({
                if (conn.isOpen && !authenticatedClients.contains(conn)) {
                    Log.w(TAG, "Auth timeout for ${conn.remoteSocketAddress}")
                    conn.close(1008, "Auth timeout")
                }
            }, authTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            conn.close(1001, "Shutting down")
        }
    }

    /** Sends [json] to every authenticated client, encrypting it for v2 clients. */
    fun sendToAuthenticated(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        for (client in authenticatedClients) {
            if (!client.isOpen) continue
            val session = sessions[client]
            if (session != null) client.send(session.seal(bytes)) else client.send(json)
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if (authenticatedClients.contains(conn)) return // already authenticated

        try {
            val obj = JsonParser.parseString(message).asJsonObject
            when {
                obj.has("v") -> handleHello(conn, obj)
                obj.has("auth") -> handleLegacyAuth(conn, obj.get("auth")?.asString)
                else -> conn.close(1008, "Bad request")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Malformed first message from ${conn.remoteSocketAddress}: ${e.message}")
            conn.close(1008, "Bad request")
        }
    }

    private fun handleHello(conn: WebSocket, obj: com.google.gson.JsonObject) {
        val hello = obj.getAsJsonObject("hello")
        val accepted = if (obj.get("v")?.asInt == 2 && hello != null && !sessions.containsKey(conn)) {
            WifiSession.accept(hello.get("pub")?.asString.orEmpty(), hello.get("nonce")?.asString.orEmpty(), token)
        } else null
        if (accepted == null) {
            Log.w(TAG, "Bad v2 hello from ${conn.remoteSocketAddress}")
            conn.close(1008, "Bad request")
            return
        }
        sessions[conn] = accepted.session
        conn.send(accepted.serverHello)
    }

    private fun handleLegacyAuth(conn: WebSocket, incoming: String?) {
        if (!allowLegacy()) {
            Log.w(TAG, "Legacy client from ${conn.remoteSocketAddress} refused: encryption required")
            conn.close(1008, "Encryption required")
            return
        }
        if (guard.isValid(incoming)) {
            authenticatedClients.add(conn)
            conn.send("""{"status":"ok"}""")
            Log.i(TAG, "Legacy client authenticated: ${conn.remoteSocketAddress}")
            onClientAuthenticated(authenticatedClients.size)
        } else {
            Log.w(TAG, "Bad auth token from ${conn.remoteSocketAddress} — closing")
            conn.remoteSocketAddress?.address?.hostAddress?.let(guard::recordFailure)
            conn.close(1008, "Unauthorized")
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        val session = sessions[conn]
        if (session == null) {
            conn.close(1008, "Bad request")
            return
        }
        if (session.authenticated) return // client records after `ready` are ignored

        val record = ByteArray(message.remaining()).also(message::get)
        val plaintext = session.open(record)
        val ready = plaintext?.let {
            try {
                JsonParser.parseString(String(it, Charsets.UTF_8)).asJsonObject.get("ready")?.asBoolean == true
            } catch (_: Exception) {
                false
            }
        } ?: false
        if (!ready) {
            // Failed GCM authentication means the client derived different keys: wrong token.
            Log.w(TAG, "v2 authentication failed from ${conn.remoteSocketAddress}")
            conn.remoteSocketAddress?.address?.hostAddress?.let(guard::recordFailure)
            conn.close(1008, "Unauthorized")
            return
        }
        session.authenticated = true
        authenticatedClients.add(conn)
        conn.send(session.seal("""{"status":"ok"}""".toByteArray(Charsets.UTF_8)))
        Log.i(TAG, "Encrypted client authenticated: ${conn.remoteSocketAddress}")
        onClientAuthenticated(authenticatedClients.size)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        sessions.remove(conn)
        val wasAuthenticated = authenticatedClients.remove(conn)
        if (wasAuthenticated) {
            Log.i(TAG, "Authenticated client disconnected: ${conn.remoteSocketAddress} (${authenticatedClients.size} remaining)")
            onClientDisconnected(authenticatedClients.size)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error for ${conn?.remoteSocketAddress}: ${ex.message}")
        // conn == null means a server-level failure (typically BindException).
        if (conn == null) onServerError(ex)
    }

    override fun onStart() {
        Log.i(TAG, "PesaCast WebSocket server listening on port ${address.port}")
    }
}
