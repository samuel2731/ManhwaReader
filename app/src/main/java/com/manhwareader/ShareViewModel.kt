package com.manhwareader

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Runs Send and Receive. The screens only show what is in here. */
class ShareViewModel(app: Application) : AndroidViewModel(app) {

    var recv by mutableStateOf<RecvStatus>(RecvStatus.Idle)
        private set
    /** A phone wants to send something and is waiting for Accept or Decline. */
    var incoming by mutableStateOf<Offer?>(null)
        private set
    var send by mutableStateOf<SendStatus>(SendStatus.Idle)
        private set
    val peers = mutableStateListOf<Peer>()

    private val main = Handler(Looper.getMainLooper())

    // ---- receiving ----
    private var serverJob: Job? = null
    private var server: ServerSocket? = null
    private var client: Socket? = null
    private var advertiser: PeerAdvertiser? = null
    private var decision: CompletableDeferred<Boolean>? = null
    private var waiting: RecvStatus.Waiting? = null

    // ---- sending ----
    private var sendJob: Job? = null
    private var finder: PeerFinder? = null
    private val holder = SocketHolder()
    @Volatile
    private var userCancelled = false

    // ================================================================ receive

    fun startReceiving() {
        if (serverJob?.isActive == true) return
        val app = getApplication<Application>()
        recv = RecvStatus.Idle
        serverJob = viewModelScope.launch(Dispatchers.IO) {
            val ss = try {
                ServerSocket(Share.PORT)
            } catch (e: IOException) {
                try {
                    ServerSocket(0)
                } catch (e2: IOException) {
                    recv = RecvStatus.Failed("Couldn't start receiving. Check that Wi-Fi is on.")
                    return@launch
                }
            }
            server = ss
            val name = Share.deviceName(app)
            val adv = PeerAdvertiser(app)
            adv.start(name, ss.localPort)
            advertiser = adv
            val ready = RecvStatus.Waiting(name, Share.localAddresses(), ss.localPort)
            waiting = ready
            recv = ready

            while (isActive) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                client = socket
                handle(socket, app)
                client = null
            }
        }
    }

    private suspend fun handle(socket: Socket, app: Application) {
        try {
            val result = Share.receiveSession(
                socket = socket,
                baseDir = Share.receivedDir(app),
                decide = { offer -> askUser(offer) },
                onProgress = { offer, got -> recv = RecvStatus.Receiving(offer.sender, got, offer.totalBytes) },
            )
            when (result) {
                is ReceiveResult.Done -> recv = RecvStatus.Finished(result.manhwa, result.files)
                is ReceiveResult.Declined -> if (recv !is RecvStatus.Failed) recv = waiting ?: RecvStatus.Idle
                is ReceiveResult.Rejected -> {}
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recv = RecvStatus.Failed("The transfer stopped: ${e.message ?: "connection lost"}")
        } finally {
            runCatching { socket.close() }
            incoming = null
        }
    }

    private suspend fun askUser(offer: Offer): Boolean {
        val dir = Share.receivedDir(getApplication<Application>())
        dir.mkdirs()
        if (dir.usableSpace < offer.totalBytes + 50L * 1024 * 1024) {
            recv = RecvStatus.Failed("Not enough free space on this phone for that transfer.")
            return false
        }
        val d = CompletableDeferred<Boolean>()
        decision = d
        incoming = offer
        val accepted = withTimeoutOrNull(110_000L) { d.await() } ?: false
        incoming = null
        decision = null
        return accepted
    }

    /** The Accept / Decline buttons. */
    fun answer(accept: Boolean) {
        decision?.complete(accept)
    }

    fun stopReceiving() {
        decision?.complete(false)
        advertiser?.stop()
        advertiser = null
        runCatching { client?.close() }
        runCatching { server?.close() }
        client = null
        server = null
        serverJob?.cancel()
        serverJob = null
        incoming = null
        waiting = null
        recv = RecvStatus.Idle
    }

    // ================================================================== send

    /** Start looking for phones that are in Receive mode. */
    fun startLooking() {
        stopFinder()
        peers.clear()
        send = SendStatus.Looking
        val f = PeerFinder(
            getApplication<Application>(),
            onFound = { p ->
                main.post {
                    if (peers.none { it.address == p.address && it.port == p.port }) peers.add(p)
                }
            },
            onLost = { name -> main.post { peers.removeAll { it.name == name } } },
        )
        finder = f
        f.start()
    }

    private fun stopFinder() {
        finder?.stop()
        finder = null
    }

    fun sendTo(peer: Peer, chosen: List<Series>) {
        if (sendJob?.isActive == true) return
        stopFinder()
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            transmit(peer.name, peer.address, peer.port, chosen)
        }
    }

    /** For when the phone doesn't show up on its own: "192.168.1.5" or "192.168.1.5:45454". */
    fun sendManual(text: String, chosen: List<Series>) {
        if (sendJob?.isActive == true) return
        stopFinder()
        sendJob = viewModelScope.launch(Dispatchers.IO) {
            val clean = text.trim()
            val host = clean.substringBefore(':').trim()
            val port = clean.substringAfter(':', "").trim().toIntOrNull() ?: Share.PORT
            val address = try {
                InetAddress.getByName(host)
            } catch (e: Exception) {
                send = SendStatus.Failed("That address doesn't look right.")
                return@launch
            }
            transmit(host, address, port, chosen)
        }
    }

    private fun transmit(name: String, address: InetAddress, port: Int, chosen: List<Series>) {
        val app = getApplication<Application>()
        userCancelled = false
        val items = ArrayList<SendItem>()
        var total = 0L
        for (s in chosen) {
            for (ch in s.chapters) {
                items.add(SendItem(Share.relPath(ch), ch.uri))
                total += ch.sizeBytes
            }
            // The cover goes along, so the other phone shows the same picture.
            s.coverImage?.let { items.add(SendItem(s.name + "/cover.jpg", it)) }
        }
        send = SendStatus.Connecting(name)
        try {
            val result = Share.transmit(
                context = app,
                address = address,
                port = port,
                senderName = Share.deviceName(app),
                manhwa = chosen.map { it.name },
                items = items,
                totalBytes = total,
                holder = holder,
                onWaiting = { send = SendStatus.WaitingAccept(name) },
                onProgress = { sent -> send = SendStatus.Sending(name, sent, total) },
            )
            send = if (result == SendResult.DONE) SendStatus.Done(name) else SendStatus.Declined(name)
        } catch (e: Exception) {
            if (!userCancelled) {
                send = SendStatus.Failed(
                    "Couldn't send. Make sure both phones are on the same Wi-Fi and the other phone is on its Receive screen."
                )
            }
        }
    }

    fun cancelSend() {
        userCancelled = true
        runCatching { holder.socket?.close() }
        sendJob?.cancel()
        sendJob = null
        send = SendStatus.Idle
    }

    fun stopSending() {
        userCancelled = true
        stopFinder()
        runCatching { holder.socket?.close() }
        sendJob?.cancel()
        sendJob = null
        peers.clear()
        send = SendStatus.Idle
    }

    override fun onCleared() {
        stopReceiving()
        stopSending()
    }
}
