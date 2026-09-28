package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Bounded, private diagnostics. Callers enqueue redacted lines; disk work runs on one writer thread. */
internal class SessionLogFile(val file: File) : Closeable {
    private sealed interface Command {
        data class Line(val text: String) : Command
        data class Reset(val header: String) : Command
        data class Flush(val done: CountDownLatch) : Command
        data class Stop(val done: CountDownLatch) : Command
    }

    private val lock = Any()
    private val queue = ArrayBlockingQueue<Command>(MAX_PENDING_LINES)
    private val droppedLines = AtomicInteger()
    private var closed = false
    private var closeDone: CountDownLatch? = null
    private val writer = Thread(::writeLoop, "diplay-diagnostic-writer").apply {
        isDaemon = true
        start()
    }

    init { active.add(this) }

    fun reset(header: String) = synchronized(lock) {
        if (!closed) queue.put(Command.Reset(DiagnosticRedactor.redact(header).orEmpty()))
    }

    fun append(line: String) {
        val safe = DiagnosticRedactor.redact(line) ?: return
        synchronized(lock) {
            if (!closed && !queue.offer(Command.Line(safe))) droppedLines.incrementAndGet()
        }
    }

    /** Ensures an exported snapshot includes everything enqueued before the barrier. */
    fun flush() {
        val done = synchronized(lock) {
            if (closed) closeDone else CountDownLatch(1).also { queue.put(Command.Flush(it)) }
        }
        done?.await()
    }

    override fun close() {
        val done = synchronized(lock) {
            if (closed) closeDone else CountDownLatch(1).also {
                closed = true
                closeDone = it
                queue.put(Command.Stop(it))
            }
        }
        done?.await()
        active.remove(this)
    }

    private fun writeLoop() {
        var fileBytes = file.length()
        val pending = StringBuilder()

        fun writePending() {
            if (pending.isEmpty()) return
            runCatching { file.parentFile?.mkdirs(); file.appendText(pending.toString()) }
            pending.clear()
        }

        fun addLine(line: String) {
            if (fileBytes > MAX_BYTES) {
                writePending()
                runCatching { rotate(); file.writeText("") }
                fileBytes = 0L
            }
            val terminated = "$line\n"
            pending.append(terminated)
            fileBytes += terminated.toByteArray(StandardCharsets.UTF_8).size
        }

        while (true) {
            val first = queue.take()
            if (first is Command.Line && queue.size < BATCH_LINES) Thread.sleep(BATCH_DELAY_MILLIS)
            val commands = ArrayList<Command>(BATCH_LINES + 1)
            commands.add(first)
            queue.drainTo(commands, BATCH_LINES - 1)
            val dropped = droppedLines.getAndSet(0)
            if (dropped > 0) addLine("Diagnostic lines omitted due to writer backlog: $dropped")
            for (command in commands) {
                when (command) {
                    is Command.Line -> addLine(command.text)
                    is Command.Reset -> {
                        writePending()
                        runCatching { file.parentFile?.mkdirs(); rotate(); file.writeText("") }
                        fileBytes = 0L
                        if (command.header.isNotEmpty()) addLine(command.header)
                    }
                    is Command.Flush -> {
                        writePending()
                        command.done.countDown()
                    }
                    is Command.Stop -> {
                        writePending()
                        command.done.countDown()
                        return
                    }
                }
            }
            writePending()
        }
    }
    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }
    companion object {
        const val MAX_BYTES = 512 * 1024L
        private const val MAX_PENDING_LINES = 4096
        private const val BATCH_LINES = 256
        private const val BATCH_DELAY_MILLIS = 200L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<SessionLogFile>()
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"

        fun flushActive() { active.forEach(SessionLogFile::flush) }
    }
}
