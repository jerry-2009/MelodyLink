package com.melody.melodylink.sony

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import com.op.bttest.sony.SonyAncMode
import com.op.bttest.sony.SonyAncState
import com.op.bttest.sony.SonyLogEntry
import com.op.bttest.sony.SonyProtocol
import com.op.bttest.sony.SonyProtocolVersion
import com.op.bttest.sony.SonyRfcommClient
import com.op.bttest.sony.SonyUuids
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Target-only Sony transport facade with a Java-callable callback surface. */
class SonyTransportAdapter(
    private val listener: Listener,
) {
    interface Listener {
        fun onConnecting()
        fun onConnected(state: SonyAncState)
        fun onAncWriteResult(success: Boolean, state: SonyAncState?, reason: String)
        fun onDisconnected()
        fun onFailed(reason: String)
        fun onLog(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong()
    private var activeJob: Job? = null
    private var client: SonyRfcommClient? = null
    private var protocol: SonyProtocol? = null
    private var connectingAddress: String? = null
    private var connectedAddress: String? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var currentState: SonyAncState? = null
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(device: BluetoothDevice) {
        val address = try {
            device.address
        } catch (_: SecurityException) {
            null
        }
        if (address != null && isConnected && connectedAddress.equals(address, ignoreCase = true)) {
            listener.onLog("Sony RFCOMM already connected; ignoring duplicate connect")
            return
        }
        if (address != null && connectingAddress.equals(address, ignoreCase = true)
            && activeJob?.isActive == true
        ) {
            listener.onLog("Sony RFCOMM connection already in progress; ignoring duplicate connect")
            return
        }

        val request = generation.incrementAndGet()
        activeJob?.cancel()
        connectingAddress = address
        activeJob = scope.launch {
            listener.onConnecting()
            isConnected = false
            currentState = null
            connectedAddress = null

            val versions = protocolCandidates(device)
            var lastFailure = "no Sony protocol connection succeeded"
            for (version in versions) {
                if (request != generation.get()) return@launch
                val candidate = SonyRfcommClient { entry ->
                    when (entry.direction) {
                        SonyLogEntry.Direction.INFO,
                        SonyLogEntry.Direction.ERROR -> listener.onLog(entry.message)
                        SonyLogEntry.Direction.TX,
                        SonyLogEntry.Direction.RX -> Unit
                    }
                }
                client = candidate
                try {
                    listener.onLog("trying Sony ${version.name} transport")
                    if (!candidate.connect(device, version)) {
                        lastFailure = "Sony ${version.name} RFCOMM connect failed"
                        candidate.disconnect()
                        continue
                    }
                    val candidateProtocol = SonyProtocol(candidate, version)
                    protocol = candidateProtocol
                    if (!candidateProtocol.initialize()) {
                        lastFailure = "Sony ${version.name} protocol initialization failed"
                        candidate.disconnect()
                        continue
                    }
                    val state = candidateProtocol.getAncState()
                    if (state == null) {
                        lastFailure = "Sony ${version.name} ANC state read failed"
                        candidate.disconnect()
                        continue
                    }
                    if (request != generation.get()) {
                        candidate.disconnect()
                        return@launch
                    }
                    isConnected = true
                    currentState = state
                    connectedAddress = address
                    connectingAddress = null
                    listener.onConnected(state)
                    return@launch
                } catch (cancelled: CancellationException) {
                    candidate.disconnect()
                    throw cancelled
                } catch (throwable: Throwable) {
                    lastFailure = "Sony ${version.name} transport failed: ${throwable.javaClass.simpleName}"
                    listener.onLog(lastFailure)
                    candidate.disconnect()
                }
            }
            if (request == generation.get()) {
                client = null
                protocol = null
                connectingAddress = null
                connectedAddress = null
                listener.onFailed(lastFailure)
            }
        }
    }

    @Synchronized
    fun disconnect() {
        generation.incrementAndGet()
        activeJob?.cancel()
        activeJob = scope.launch {
            client?.disconnect()
            client = null
            protocol = null
            connectingAddress = null
            connectedAddress = null
            val wasConnected = isConnected
            isConnected = false
            currentState = null
            if (wasConnected) listener.onDisconnected()
        }
    }

    fun setAncMode(mode: SonyAncMode, ambientLevel: Int, focusOnVoice: Boolean) {
        val request = generation.get()
        scope.launch {
            val activeProtocol = protocol
            if (!isConnected || activeProtocol == null) {
                listener.onFailed("Sony ANC write requested while disconnected")
                return@launch
            }
            try {
                val ok = activeProtocol.setAncMode(
                    SonyAncState(
                        mode = mode,
                        ambientLevel = ambientLevel,
                        focusOnVoice = focusOnVoice,
                    ),
                )
                if (!ok || request != generation.get()) {
                    listener.onAncWriteResult(false, null, "Sony ANC write failed")
                    return@launch
                }
                val state = activeProtocol.getAncState()
                if (state == null) {
                    listener.onAncWriteResult(false, null, "Sony ANC state refresh failed")
                } else {
                    currentState = state
                    listener.onAncWriteResult(true, state, "")
                }
            } catch (throwable: Throwable) {
                listener.onLog("Sony ANC write failed: ${throwable.javaClass.simpleName}")
                listener.onAncWriteResult(false, null, "Sony ANC write failed")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun protocolCandidates(device: BluetoothDevice): List<SonyProtocolVersion> {
        return try {
            val uuids: Set<UUID> = device.uuids?.map { it.uuid }?.toSet().orEmpty()
            when {
                SonyUuids.V2 in uuids -> listOf(SonyProtocolVersion.V2, SonyProtocolVersion.V1)
                SonyUuids.V1 in uuids -> listOf(SonyProtocolVersion.V1, SonyProtocolVersion.V2)
                else -> listOf(SonyProtocolVersion.V2, SonyProtocolVersion.V1)
            }
        } catch (_: SecurityException) {
            listOf(SonyProtocolVersion.V2, SonyProtocolVersion.V1)
        }
    }
}
