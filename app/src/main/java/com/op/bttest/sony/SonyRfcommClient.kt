package com.op.bttest.sony

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SonyRfcommClient(
    private val onLog: (SonyLogEntry) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private val writeMutex = Mutex()
    private val ackLock = Any()
    private val responseWaiters = mutableListOf<ResponseWaiter>()

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var running = false
    private var sequence = 0
    private var ackWaiter: CompletableDeferred<Unit>? = null
    private var collectingFrame = false
    private val frameBuffer = mutableListOf<Byte>()

    val isConnected: Boolean
        get() = socket?.isConnected == true && running

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice, version: SonyProtocolVersion): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                disconnect()
                logInfo("Connecting ${device.name ?: device.address} with Sony ${version.name}")

                val newSocket = device.createRfcommSocketToServiceRecord(SonyUuids.forVersion(version))
                newSocket.connect()

                socket = newSocket
                inputStream = newSocket.inputStream
                outputStream = newSocket.outputStream
                running = true
                sequence = 0
                readJob = scope.launch { readLoop() }
                logInfo("RFCOMM connected.")
                true
            } catch (throwable: Throwable) {
                logError("Connect failed: ${throwable.message}", throwable)
                closeSocketOnly()
                false
            }
        }
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            if (!running && socket == null) return@withContext
            running = false
            readJob?.cancel()
            readJob = null
            closeSocketOnly()
            failWaiters()
            logInfo("Disconnected.")
        }
    }

    suspend fun sendCommand(
        messageType: Int,
        payload: ByteArray,
        ackTimeoutMs: Long = 1_500L,
    ): Boolean = commandMutex.withLock {
        sendCommandLocked(messageType, payload, ackTimeoutMs)
    }

    suspend fun sendCommandForResponse(
        messageType: Int,
        payload: ByteArray,
        timeoutMs: Long,
        accept: (SonyFrame) -> Boolean,
    ): SonyFrame? = commandMutex.withLock {
        val waiter = ResponseWaiter(accept, CompletableDeferred())
        synchronized(responseWaiters) {
            responseWaiters += waiter
        }
        try {
            val ackOk = sendCommandLocked(messageType, payload, ackTimeoutMs = 1_500L)
            if (!ackOk) {
                logInfo("ACK timeout; still waiting briefly for response.")
            }
            val response = withTimeoutOrNull(timeoutMs) {
                waiter.deferred.await()
            }
            if (response == null) {
                logInfo("Response timeout for payload ${payload.toHexString()}.")
            }
            response
        } finally {
            synchronized(responseWaiters) {
                responseWaiters.remove(waiter)
            }
        }
    }

    fun logInfo(message: String) {
        onLog(SonyLogEntry(SonyLogEntry.Direction.INFO, message))
    }

    private suspend fun sendCommandLocked(
        messageType: Int,
        payload: ByteArray,
        ackTimeoutMs: Long,
    ): Boolean {
        if (!isConnected) {
            logInfo("Cannot send: socket is not connected.")
            return false
        }

        val commandSequence = sequence
        sequence = 1 - sequence
        val packet = SonyFrameCodec.encode(messageType, commandSequence, payload)
        val ack = CompletableDeferred<Unit>()
        synchronized(ackLock) {
            ackWaiter = ack
        }

        return try {
            sendRaw(packet)
            withTimeoutOrNull(ackTimeoutMs) {
                ack.await()
                true
            } ?: false
        } catch (throwable: Throwable) {
            logError("Send failed: ${throwable.message}", throwable)
            running = false
            closeSocketOnly()
            failWaiters()
            false
        } finally {
            synchronized(ackLock) {
                if (ackWaiter === ack) {
                    ackWaiter = null
                }
            }
        }
    }

    private suspend fun sendAck(sequence: Int) {
        if (!isConnected) return
        val packet = SonyFrameCodec.encode(SonyMessageType.ACK, sequence, byteArrayOf())
        sendRaw(packet)
    }

    private suspend fun sendRaw(packet: ByteArray) {
        val out = outputStream ?: return
        writeMutex.withLock {
            out.write(packet)
            out.flush()
            onLog(SonyLogEntry(SonyLogEntry.Direction.TX, packet.toHexString(), packet))
        }
    }

    private suspend fun readLoop() {
        val input = inputStream ?: return
        val buffer = ByteArray(1024)
        try {
            while (running && scope.isActive) {
                val count = input.read(buffer)
                if (count <= 0) break
                for (index in 0 until count) {
                    collectByte(buffer[index].u8())?.let { frame ->
                        processFrame(frame)
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (running) {
                logError("Read loop failed: ${throwable.message}", throwable)
            }
        } finally {
            if (running) {
                running = false
                closeSocketOnly()
                failWaiters()
                logInfo("Socket read loop ended.")
            }
        }
    }

    private fun collectByte(value: Int): ByteArray? {
        if (!collectingFrame) {
            if (value == SonyFrameConstants.HEADER) {
                collectingFrame = true
                frameBuffer.clear()
                frameBuffer += value.asByte()
            }
            return null
        }

        frameBuffer += value.asByte()
        if (value != SonyFrameConstants.TRAILER) return null

        collectingFrame = false
        val frame = ByteArray(frameBuffer.size) { frameBuffer[it] }
        frameBuffer.clear()
        return frame
    }

    private suspend fun processFrame(rawFrame: ByteArray) {
        onLog(SonyLogEntry(SonyLogEntry.Direction.RX, rawFrame.toHexString(), rawFrame))
        val frame = SonyFrameCodec.decode(rawFrame)
        if (frame == null) {
            logInfo("Invalid Sony frame.")
            return
        }

        if (frame.messageType == SonyMessageType.ACK) {
            completeAck()
            return
        }

        if (
            frame.messageType == SonyMessageType.COMMAND_1 ||
            frame.messageType == SonyMessageType.COMMAND_2
        ) {
            sendAck(1 - frame.sequence)
        }

        val waiter = synchronized(responseWaiters) {
            val index = responseWaiters.indexOfFirst { it.accept(frame) }
            if (index >= 0) responseWaiters.removeAt(index) else null
        }
        waiter?.deferred?.complete(frame)
    }

    private fun completeAck() {
        val waiter = synchronized(ackLock) {
            val current = ackWaiter
            ackWaiter = null
            current
        }
        waiter?.complete(Unit)
    }

    private fun closeSocketOnly() {
        try {
            inputStream?.close()
        } catch (_: Throwable) {
        }
        try {
            outputStream?.close()
        } catch (_: Throwable) {
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        inputStream = null
        outputStream = null
        socket = null
        collectingFrame = false
        frameBuffer.clear()
    }

    private fun failWaiters() {
        synchronized(ackLock) {
            ackWaiter?.cancel()
            ackWaiter = null
        }
        synchronized(responseWaiters) {
            responseWaiters.forEach { it.deferred.cancel() }
            responseWaiters.clear()
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        onLog(SonyLogEntry(SonyLogEntry.Direction.ERROR, message))
        throwable?.printStackTrace()
    }

    private data class ResponseWaiter(
        val accept: (SonyFrame) -> Boolean,
        val deferred: CompletableDeferred<SonyFrame>,
    )
}
