package infoscry.llm

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI

/**
 * Reads one line of UTF-8 from a Ktor response body channel. Provider SSE payloads are JSON, which is
 * well-formed UTF-8, so a direct decode is exact. A byte at a time so a newline never splits an
 * unreadable remainder; [awaitContent] suspends the reader instead of blocking a thread, which is what
 * lets a cancelled consumer unwind the loop.
 *
 * Shared by the OpenAI (part B) and Anthropic (part C) adapters' response consumption, so the two
 * providers cannot drift apart in line handling.
 *
 * [readBuffer] is marked internal by Ktor (it is the raw octet pipe under the public channel), so this
 * opts in: there is no public line-reading API on [ByteReadChannel] itself.
 */
internal object LlmSseLineReader {

    @OptIn(InternalAPI::class)
    suspend fun readLine(channel: ByteReadChannel): String? {
        val bytes = ArrayList<Int>()
        while (true) {
            if (channel.readBuffer.exhausted() && !channel.awaitContent(1)) {
                return if (bytes.isEmpty()) null else decodeLine(bytes)
            }
            val next = channel.readBuffer.readByte().toInt().and(0xFF)
            if (next == '\n'.code) return decodeLine(bytes)
            bytes.add(next)
        }
    }

    /**
     * Decodes one line of UTF-8 from its octets (dropping a trailing carriage return). The bitwise
     * building uses the Kotlin 2.x method forms (`.and`/`.or`/`.shl`) because the operator symbols are
     * gone.
     */
    private fun decodeLine(bytes: List<Int>): String {
        val length = if (bytes.lastOrNull() == '\r'.code) bytes.size - 1 else bytes.size
        val text = StringBuilder()
        var index = 0
        while (index < length) {
            val first = bytes.get(index).and(0xFF)
            index += 1
            if (first < 0x80) {
                text.append(first.toChar())
            } else if (first < 0xE0) {
                text.append(
                    first.and(0x1F).shl(8)
                        .or(bytes.get(index).and(0x3F))
                        .toChar(),
                )
                index += 1
            } else if (first < 0xF0) {
                text.append(
                    first.and(0x0F).shl(12)
                        .or(bytes.get(index).and(0x3F).shl(6))
                        .or(bytes.get(index + 1).and(0x3F))
                        .toChar(),
                )
                index += 2
            } else {
                text.append(
                    first.and(0x07).shl(18)
                        .or(bytes.get(index).and(0x3F).shl(12))
                        .or(bytes.get(index + 1).and(0x3F).shl(6))
                        .or(bytes.get(index + 2).and(0x3F))
                        .toChar(),
                )
                index += 3
            }
        }
        return text.toString()
    }
}