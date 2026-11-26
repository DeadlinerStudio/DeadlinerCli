package com.deadliner.cli

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.deadliner.db.AppDatabase
import com.deadliner.db.DatabaseDriverFactory
import com.deadliner.db.DeadlinerDatabase
import com.deadliner.platform.PlatformIdentity
import java.io.OutputStream
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.system.exitProcess

private val windows = System.getProperty("os.name").startsWith("Windows")
val cliPlatform = when {
    windows -> "windows"
    System.getProperty("os.name") == "Linux" -> "linux"
    else -> "macos"
} + "-" + when (val arch = System.getProperty("os.arch")) {
    "amd64", "x86_64" -> "x64"
    "aarch64" -> "arm64"
    else -> arch
}

fun env(name: String): String? = System.getenv(name)

fun main(args: Array<String>) {
    val out = System.out
    val err = System.err
    // stdout can be a terminal even when an agent redirects stdin.
    val terminal = if (windows) System.console() != null
        else ProcessBuilder("/bin/test", "-t", "1").inheritIO().start().waitFor() == 0
    val invocation = CliInvocation(terminal)
    System.setOut(err)
    val response = invocation.run(args) {
        val sink = PrintStream(OutputStream.nullOutputStream())
        System.setOut(sink)
        System.setErr(sink)
    }
    val rendered = if (invocation.jsonOutput) response.toString() else humanOutput(response, invocation.words)
    (if (!invocation.jsonOutput && invocation.status != 0) err else out).println(rendered)
    exitProcess(invocation.status)
}

fun <T> withDatabase(a: Arguments, block: (DeadlinerDatabase) -> T): T {
    val defaultDir = if (windows) {
        Path.of(env("LOCALAPPDATA") ?: System.getProperty("user.home"), "Deadliner")
    } else Path.of(System.getProperty("user.home"), ".local", "share", "deadliner")
    val profile = Path.of(a.options["data-dir"] ?: env("DEADLINER_HOME") ?: defaultDir.toString())
    if (!profile.isAbsolute) invalid("Data directory must be absolute")
    if (windows) Files.createDirectories(profile)
    else Files.createDirectories(profile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    FileChannel.open(profile.resolve("cli.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        val lock = channel.tryLock() ?: throw CliError("BUSY", "Another CLI process is using this data directory", 4)
        lock.use {
            val identity = profile.resolve("device-id")
            if (!Files.exists(identity)) {
                val temp = Files.createTempFile(profile, ".device-", ".tmp")
                try {
                    Files.writeString(temp, PlatformIdentity.newId())
                    Files.move(temp, identity, StandardCopyOption.ATOMIC_MOVE)
                } finally { Files.deleteIfExists(temp) }
            }
            val id = Files.readString(identity).trim()
            if (id.isEmpty()) throw CliError("STORAGE_ERROR", "Empty device identity", 1)
            PlatformIdentity.configure(id)
            val database = profile.resolve("deadliner.db")
            val factory = object : DatabaseDriverFactory {
                override fun createDriver(): SqlDriver = JdbcSqliteDriver(
                    "jdbc:sqlite:" + database.toUri().toASCIIString(),
                    Properties().apply { setProperty("foreign_keys", "true") }, AppDatabase.Schema,
                )
            }
            return DeadlinerDatabase.create(factory).use(block)
        }
    }
}
