package dev.sarvenaz.biosignalbridge.signal

sealed interface DecodeResult {
    data class Success(val packet: SensorPacket) : DecodeResult
    data class Invalid(val reason: String) : DecodeResult
}

/** One notification is one 20-byte packet; no larger MTU is required. */
object PacketDecoder {
    const val PACKET_BYTES = 20

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size != PACKET_BYTES) return DecodeResult.Invalid("Expected one 20-byte packet.")
        fun u8(index: Int) = bytes[index].toInt() and 255
        fun u16(index: Int) = u8(index) or (u8(index + 1) shl 8)
        if (u8(0) != 0xB1) return DecodeResult.Invalid("Packet signature does not match.")
        if (u8(1) != 1) return DecodeResult.Invalid("Packet version is not supported.")
        val count = u8(11)
        val period = u16(8)
        if (count !in 1..4) return DecodeResult.Invalid("Packet must contain one to four samples.")
        if (period == 0) return DecodeResult.Invalid("Sample period must be greater than zero.")
        if ((12 + count * 2 until PACKET_BYTES).any { bytes[it] != 0.toByte() }) {
            return DecodeResult.Invalid("Unused sample slots must contain zero bytes.")
        }
        val tick = (0..3).fold(0L) { value, offset -> value or (u8(4 + offset).toLong() shl (8 * offset)) }
        val samples = (0 until count).map { u16(12 + it * 2).toShort().toInt() }
        return DecodeResult.Success(SensorPacket(u16(2), tick, period, u8(10), samples))
    }

    fun encode(packet: SensorPacket): ByteArray {
        val bytes = ByteArray(PACKET_BYTES)
        bytes[0] = 0xB1.toByte()
        bytes[1] = 1
        fun put16(index: Int, value: Int) {
            bytes[index] = value.toByte()
            bytes[index + 1] = (value ushr 8).toByte()
        }
        put16(2, packet.sequence)
        repeat(4) { bytes[4 + it] = (packet.tickUs ushr (8 * it)).toByte() }
        put16(8, packet.periodUs)
        bytes[10] = packet.channel.toByte()
        bytes[11] = packet.samples.size.toByte()
        packet.samples.forEachIndexed { index, sample -> put16(12 + index * 2, sample) }
        return bytes
    }
}
