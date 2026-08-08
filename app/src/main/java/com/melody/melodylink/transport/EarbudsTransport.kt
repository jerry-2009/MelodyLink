package com.melody.melodylink.transport

import android.bluetooth.BluetoothDevice
import java.util.UUID
import kotlinx.coroutines.flow.Flow

sealed interface TransportEndpoint {
    data class Rfcomm(val device: BluetoothDevice, val serviceUuid: UUID) : TransportEndpoint
    data class Gatt(val serviceUuid: String, val characteristicUuid: String) : TransportEndpoint
}

interface EarbudsTransport {
    suspend fun connect(endpoint: TransportEndpoint): Result<Unit>
    suspend fun send(packet: ByteArray): Result<Unit>
    fun incomingFrames(): Flow<ByteArray>
    suspend fun close()
}

/** Marker for the future BLE implementation; no GATT behavior is assumed yet. */
interface GattTransport : EarbudsTransport
