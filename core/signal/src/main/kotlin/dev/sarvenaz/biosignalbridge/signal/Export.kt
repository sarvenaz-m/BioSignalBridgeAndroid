package dev.sarvenaz.biosignalbridge.signal

object CsvContract {
    const val HEADER = "session_id,channel,sequence,timestamp_ns,device_tick_us,raw_value,gap_before"

    fun encode(session: Session): String {
        validateSession(session)
        return buildString {
            append(HEADER).append('\n')
            session.samples.sortedWith(compareBy<Sample> { it.timeNs }.thenBy { it.channel }).forEach { sample ->
                append(csvField(session.id)).append(',')
                append(sample.channel).append(',').append(sample.sequence).append(',')
                append(sample.timeNs - session.originNs).append(',')
                append(sample.deviceTickUs).append(',').append(sample.raw).append(',')
                append(sample.gapBefore).append('\n')
            }
        }
    }

    private fun csvField(value: String): String = if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else value
}

/** Metadata companion for the CSV. Host boot timestamps never leave the device. */
object SessionJson {
    const val SCHEMA = "biosignal-bridge.session/v1"

    fun encode(session: Session): String {
        validateSession(session)
        val duration = session.samples.maxOfOrNull { it.timeNs - session.originNs } ?: 0
        val statistics = session.stats
        return buildString {
            append("{\n  \"schema\": ").append(quote(SCHEMA))
            append(",\n  \"sessionId\": ").append(quote(session.id))
            append(",\n  \"source\": ").append(quote(session.source.name))
            append(",\n  \"startedAtEpochMs\": ").append(session.startedAtEpochMs)
            append(",\n  \"timeUnit\": \"ns\",\n  \"originNs\": 0")
            append(",\n  \"sampleCount\": ").append(session.samples.size)
            append(",\n  \"durationNs\": ").append(duration)
            append(",\n  \"channels\": [")
            session.channels.forEachIndexed { index, channel ->
                if (index > 0) append(',')
                append("\n    {\"id\": ").append(channel.id)
                append(", \"name\": ").append(quote(channel.name))
                append(", \"unit\": ").append(quote(channel.unit))
                append(", \"scalePerCount\": ").append(channel.scalePerCount).append('}')
            }
            append("\n  ],\n  \"markers\": [")
            session.markers.sortedBy { it.timeNs }.forEachIndexed { index, marker ->
                if (index > 0) append(',')
                append("\n    {\"id\": ").append(quote(marker.id))
                append(", \"timeNs\": ").append(marker.timeNs - session.originNs)
                append(", \"label\": ").append(quote(marker.label)).append('}')
            }
            append("\n  ],\n  \"stats\": {")
            append("\n    \"receivedPackets\": ").append(statistics.receivedPackets)
            append(",\n    \"acceptedPackets\": ").append(statistics.acceptedPackets)
            append(",\n    \"malformedPackets\": ").append(statistics.malformedPackets)
            append(",\n    \"lostPackets\": ").append(statistics.lostPackets)
            append(",\n    \"duplicatePackets\": ").append(statistics.duplicatePackets)
            append(",\n    \"outOfOrderPackets\": ").append(statistics.outOfOrderPackets)
            append(",\n    \"clockResets\": ").append(statistics.clockResets)
            append("\n  }\n}\n")
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 32) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}

private fun validateSession(session: Session) {
    require(session.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Use a portable session identifier." }
    require(session.startedAtEpochMs >= 0 && session.originNs >= 0)
    require(session.channels.isNotEmpty() && session.channels.map { it.id }.distinct().size == session.channels.size)
    val channels = session.channels.map { it.id }.toSet()
    val previous = mutableMapOf<Int, Long>()
    session.samples.forEach { sample ->
        require(sample.channel in channels && sample.sequence in 0..65535 && sample.raw in -32768..32767)
        require(sample.deviceTickUs in 0..0xFFFFFFFFL)
        require(sample.timeNs >= session.originNs && sample.timeNs >= (previous[sample.channel] ?: session.originNs))
        previous[sample.channel] = sample.timeNs
    }
    require(session.markers.all { it.timeNs >= session.originNs })
    require(session.markers.map { it.id }.distinct().size == session.markers.size)
    with(session.stats) {
        require(listOf(receivedPackets, acceptedPackets, malformedPackets, lostPackets, duplicatePackets, outOfOrderPackets, clockResets).all { it >= 0 })
        require(acceptedPackets <= receivedPackets && malformedPackets <= receivedPackets)
    }
}
