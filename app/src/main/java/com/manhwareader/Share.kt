package com.manhwareader

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** A phone in "Receive" mode that this phone found on the network. */
data class Peer(val name: String, val address: InetAddress, val port: Int)

/** What a sender announces before anything is copied. The receiver must accept it first. */
data class Offer(val sender: String, val manhwa: List<String>, val fileCount: Int, val totalBytes: Long)

/** One file to send: where it goes on the other phone, and where it is read from here. */
data class SendItem(val relPath: String, val uri: Uri)

enum class SendResult { DONE, DECLINED }

sealed interface ReceiveResult {
    data object Rejected : ReceiveResult
    data class Declined(val offer: Offer) : ReceiveResult
    data class Done(val manhwa: List<String>, val files: Int) : ReceiveResult
}

sealed interface RecvStatus {
    data object Idle : RecvStatus
    data class Waiting(val name: String, val addresses: List<String>, val port: Int) : RecvStatus
    data class Receiving(val sender: String, val done: Long, val total: Long) : RecvStatus
    data class Finished(val manhwa: List<String>, val files: Int) : RecvStatus
    data class Failed(val message: String) : RecvStatus
}

sealed interface SendStatus {
    data object Idle : SendStatus
    data object Looking : SendStatus
    data class Connecting(val name: String) : SendStatus
    data class WaitingAccept(val name: String) : SendStatus
    data class Sending(val name: String, val sent: Long, val total: Long) : SendStatus
    data class Done(val name: String) : SendStatus
    data class Declined(val name: String) : SendStatus
    data class Failed(val message: String) : SendStatus
}

/** Lets the screen cancel a send that is blocked on the network. */
class SocketHolder {
    @Volatile
    var socket: Socket? = null
}

/**
 * Phone-to-phone sharing over the local network (same Wi-Fi, or one phone's hotspot).
 * Nothing goes through the internet.
 *
 * Conversation, in order:
 *  sender   -> receiver : who is sending and what (names, number of files, total size)
 *  receiver -> sender   : 1 = accept, 0 = decline
 *  sender   -> receiver : each file as a path, then pieces, then 0 to end the file
 *  receiver -> sender   : 1 = everything arrived
 */
object Share {
    private const val MAGIC = "NUXREADER-SHARE-1"
    const val SERVICE_TYPE = "_nuxreader._tcp."
    const val PORT = 45454
    private const val CHUNK = 64 * 1024
    private val ALLOWED = setOf("pdf", "png", "jpg", "jpeg", "webp")

    /** Received manhwa are kept here, inside the app's own storage. No permission needed. */
    fun receivedDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "Received")

    fun deviceName(context: Context): String {
        val set = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        return (if (set.isNullOrBlank()) Build.MODEL else set).take(40)
    }

    /** This phone's addresses on the local network, to show on the Receive screen. */
    fun localAddresses(): List<String> {
        val out = ArrayList<String>()
        runCatching {
            val all = NetworkInterface.getNetworkInterfaces()
            if (all != null) {
                for (ni in all) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) {
                        val ip = a.hostAddress
                        if (a is Inet4Address && a.isSiteLocalAddress && ip != null) out.add(ip)
                    }
                }
            }
        }
        return out
    }

    /** Where a chapter goes on the other phone: "Manhwa name/Chapter 1.pdf". */
    fun relPath(ch: Chapter): String =
        if (ch.sortKey.startsWith(ch.series + "/")) ch.sortKey else ch.series + "/" + ch.sortKey

    private fun sanitize(segment: String): String? {
        val s = segment
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim()
            .trimEnd('.')
            .take(120)
        return if (s.isEmpty() || s == "." || s == "..") null else s
    }

    private fun topFolder(rel: String): String? =
        rel.replace('\\', '/').split('/').firstOrNull { it.isNotEmpty() }?.let { sanitize(it) }

    /** Turns a path from the other phone into a file inside [base], or null if it is not allowed. */
    private fun safeTarget(base: File, rel: String): File? {
        val parts = rel.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 5) return null
        val clean = parts.map { sanitize(it) ?: return null }
        val ext = clean.last().substringAfterLast('.', "").lowercase()
        if (ext !in ALLOWED) return null
        var f = base
        for (p in clean) f = File(f, p)
        val basePath = base.canonicalPath
        if (!f.canonicalPath.startsWith(basePath + File.separator)) return null
        return f
    }

    // ---------------------------------------------------------------- sending

    /** Runs on a background thread. Throws IOException if the connection fails. */
    fun transmit(
        context: Context,
        address: InetAddress,
        port: Int,
        senderName: String,
        manhwa: List<String>,
        items: List<SendItem>,
        totalBytes: Long,
        holder: SocketHolder,
        onWaiting: () -> Unit,
        onProgress: (Long) -> Unit,
    ): SendResult {
        val socket = Socket()
        holder.socket = socket
        try {
            socket.connect(InetSocketAddress(address, port), 8_000)
            socket.soTimeout = 130_000
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), CHUNK))
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))

            out.writeUTF(MAGIC)
            out.writeUTF(senderName)
            out.writeInt(manhwa.size)
            for (m in manhwa) out.writeUTF(m)
            out.writeInt(items.size)
            out.writeLong(totalBytes)
            out.flush()

            onWaiting()
            if (inp.readInt() != 1) return SendResult.DECLINED

            val buf = ByteArray(CHUNK)
            var sent = 0L
            for (item in items) {
                out.writeUTF(item.relPath)
                val stream = context.contentResolver.openInputStream(item.uri)
                if (stream == null) {
                    out.writeInt(-1) // file not readable: tell the other phone to skip it
                    continue
                }
                stream.use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        out.writeInt(n)
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
                out.writeInt(0)
            }
            out.flush()
            inp.readInt() // the other phone confirms everything arrived
            return SendResult.DONE
        } finally {
            runCatching { socket.close() }
            holder.socket = null
        }
    }

    // -------------------------------------------------------------- receiving

    private fun readOffer(inp: DataInputStream): Offer? {
        if (inp.readUTF() != MAGIC) return null
        val sender = inp.readUTF().take(60)
        val n = inp.readInt()
        if (n !in 0..500) return null
        val names = ArrayList<String>()
        repeat(n) { names.add(inp.readUTF().take(100)) }
        val files = inp.readInt()
        if (files !in 0..200_000) return null
        val total = inp.readLong()
        if (total < 0) return null
        return Offer(sender, names, files, total)
    }

    /**
     * Handles one phone that connected. [decide] is where the user presses Accept or Decline.
     * Only pdf and picture files are kept, and only inside [baseDir].
     */
    suspend fun receiveSession(
        socket: Socket,
        baseDir: File,
        decide: suspend (Offer) -> Boolean,
        onProgress: (Offer, Long) -> Unit,
    ): ReceiveResult {
        val inp = DataInputStream(BufferedInputStream(socket.getInputStream(), CHUNK))
        val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        socket.soTimeout = 15_000

        val offer = try {
            readOffer(inp)
        } catch (e: IOException) {
            null
        } ?: return ReceiveResult.Rejected

        val accepted = decide(offer)
        out.writeInt(if (accepted) 1 else 0)
        out.flush()
        if (!accepted) return ReceiveResult.Declined(offer)

        socket.soTimeout = 60_000
        baseDir.mkdirs()
        val buf = ByteArray(CHUNK)
        var got = 0L
        var saved = 0
        val touched = LinkedHashSet<String>()

        repeat(offer.fileCount) {
            val rel = inp.readUTF()
            val target = safeTarget(baseDir, rel)
            val first = inp.readInt()
            if (first == -1) return@repeat

            var tmp: File? = null
            var os: FileOutputStream? = null
            try {
                if (target != null) {
                    val parent = target.parentFile
                    parent?.mkdirs()
                    tmp = File(parent, target.name + ".part")
                    os = FileOutputStream(tmp)
                }
                var n = first
                while (n != 0) {
                    if (n < 0 || n > CHUNK) throw IOException("Bad data from the other phone")
                    inp.readFully(buf, 0, n)
                    os?.write(buf, 0, n)
                    got += n
                    onProgress(offer, got)
                    n = inp.readInt()
                }
                os?.close()
                os = null
                if (target != null && tmp != null) {
                    if (target.exists()) target.delete()
                    if (tmp.renameTo(target)) {
                        saved++
                        topFolder(rel)?.let { touched.add(it) }
                    }
                }
            } finally {
                runCatching { os?.close() }
                val leftover = tmp
                if (leftover != null && leftover.exists()) leftover.delete()
            }
        }

        out.writeInt(1)
        out.flush()
        return ReceiveResult.Done(touched.toList(), saved)
    }
}

/** Announces "this phone is ready to receive" so senders on the network can find it. */
class PeerAdvertiser(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun start(name: String, port: Int) {
        val info = NsdServiceInfo()
        info.serviceName = "NUX-$name"
        info.serviceType = Share.SERVICE_TYPE
        info.port = port
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        }
        listener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop() {
        val l = listener
        listener = null
        if (l != null) runCatching { nsd.unregisterService(l) }
    }
}

/** Looks for phones that are in Receive mode. Callbacks come from a background thread. */
class PeerFinder(
    context: Context,
    private val onFound: (Peer) -> Unit,
    private val onLost: (String) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var stopped = false

    fun start() {
        stopped = false
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_nuxreader")) return
                synchronized(queue) { queue.addLast(serviceInfo) }
                resolveNext()
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onLost(serviceInfo.serviceName.removePrefix("NUX-"))
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        runCatching { nsd.discoverServices(Share.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    // Android can only look up one phone at a time, so they wait in a line.
    private fun resolveNext() {
        val next = synchronized(queue) {
            if (resolving || stopped || queue.isEmpty()) {
                null
            } else {
                resolving = true
                queue.removeFirst()
            }
        } ?: return

        val ok = runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    finishResolve()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host
                    if (host != null) {
                        onFound(Peer(serviceInfo.serviceName.removePrefix("NUX-"), host, serviceInfo.port))
                    }
                    finishResolve()
                }
            })
        }.isSuccess
        if (!ok) finishResolve()
    }

    private fun finishResolve() {
        synchronized(queue) { resolving = false }
        resolveNext()
    }

    fun stop() {
        stopped = true
        synchronized(queue) { queue.clear() }
        val l = listener
        listener = null
        if (l != null) runCatching { nsd.stopServiceDiscovery(l) }
    }
}
