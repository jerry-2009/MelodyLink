package com.melody.melodylink.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Vendor-neutral RFCOMM byte transport. Protocol framing and ACK rules belong to vendor adapters. */
class RfcommTransport : EarbudsTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    private var socket: BluetoothSocket? = null
    private var reader: Job? = null

    @SuppressLint("MissingPermission")
    override suspend fun connect(endpoint: TransportEndpoint): Result<Unit> = withContext(Dispatchers.IO) {
        val rfcomm = endpoint as? TransportEndpoint.Rfcomm
            ?: return@withContext Result.failure(IllegalArgumentException("RFCOMM endpoint required"))
        close()
        runCatching {
            val connectedSocket = rfcomm.device.createRfcommSocketToServiceRecord(rfcomm.serviceUuid)
            connectedSocket.connect()
            socket = connectedSocket
            reader = scope.launch { readLoop(connectedSocket) }
        }
    }

    override suspend fun send(packet: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val output = socket?.outputStream ?: throw IOException("RFCOMM output unavailable")
            writeMutex.withLock {
                output.write(packet)
                output.flush()
            }
        }
    }

    override fun incomingFrames(): Flow<ByteArray> = incoming.asSharedFlow()

    override suspend fun close() = withContext(Dispatchers.IO) {
        reader?.cancel()
        reader = null
        val active = socket
        socket = null
        runCatching { active?.inputStream?.close() }
        runCatching { active?.outputStream?.close() }
        runCatching { active?.close() }
        Unit
    }

    fun release() {
        scope.cancel()
    }

    private suspend fun readLoop(activeSocket: BluetoothSocket) {
        val input = runCatching { activeSocket.inputStream }.getOrNull() ?: return
        val buffer = ByteArray(1024)
        try {
            while (socket === activeSocket) {
                val count = input.read(buffer)
                if (count <= 0) break
                incoming.emit(buffer.copyOf(count))
            }
        } catch (_: IOException) {
            // Closing an RFCOMM socket interrupts the blocking read as expected.
        } finally {
            if (socket === activeSocket) close()
        }
    }
}
