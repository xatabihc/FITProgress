package ru.fitprogress.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Enforce the limit while reading, before a provider can allocate an unbounded file. */
fun readLimitedUtf8(input: InputStream, maximumBytes: Int): String {
    require(maximumBytes > 0)
    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while(true) {
        val count = input.read(buffer)
        if(count < 0) break
        require(output.size().toLong() + count <= maximumBytes) { "Файл больше ${maximumBytes / 1_000_000} МБ" }
        output.write(buffer, 0, count)
    }
    return output.toString("UTF-8").removePrefix("\uFEFF")
}
