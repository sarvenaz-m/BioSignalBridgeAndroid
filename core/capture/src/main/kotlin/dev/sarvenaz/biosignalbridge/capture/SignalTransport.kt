package dev.sarvenaz.biosignalbridge.capture

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class Peripheral(val address: String, val name: String, val rssi: Int)

enum class CapturePhase {
    IDLE, SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING, READY, DISCONNECTED, ERROR
}

data class CaptureState(
    val phase: CapturePhase = CapturePhase.IDLE,
    val peripheral: Peripheral? = null,
    val message: String = "Ready to connect",
)

data class ReceivedPacket(val bytes: ByteArray, val hostTimeNs: Long)

/**
 * The acquisition boundary. Implementations own their connection and copy incoming bytes
 * before publishing them. A full delivery buffer must increment overflow; it cannot silently
 * discard an earlier packet. Consumers subscribe before starting a connection.
 */
interface SignalTransport : AutoCloseable {
    val state: StateFlow<CaptureState>
    val devices: StateFlow<List<Peripheral>>
    val packets: SharedFlow<ReceivedPacket>
    val overflow: StateFlow<Long>

    fun scan()
    fun stopScan()
    fun connect(peripheral: Peripheral)
    fun disconnect()
}
