@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.deadliner.cli

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import kotlinx.cinterop.toKString
import platform.Foundation.*
import platform.posix.*
import kotlin.system.exitProcess

const val cliPlatform = "macos-arm64"

fun env(name: String): String? = getenv(name)?.toKString()

fun main(args: Array<String>) {
    val terminal = isatty(STDOUT_FILENO) == 1
    // Core's macOS logger uses stdout. Reserve the original descriptor for the wire protocol.
    val output = fdopen(dup(STDOUT_FILENO), "w") ?: exitProcess(1)
    fflush(stdout)
    dup2(STDERR_FILENO, STDOUT_FILENO)
    val invocation = CliInvocation(terminal)
    val response = invocation.run(args) {
        val quiet = open("/dev/null", O_WRONLY)
        if (quiet >= 0) { dup2(quiet, STDOUT_FILENO); close(quiet) }
    }
    val rendered = if (invocation.jsonOutput) response.toString() else humanOutput(response, invocation.words)
    fputs(rendered + "\n", if (!invocation.jsonOutput && invocation.status != 0) stderr else output)
    fclose(output)
    exitProcess(invocation.status)
}

fun <T> withDatabase(a: Arguments, block: (DeadlinerDatabase) -> T): T {
    val path = a.options["data-dir"] ?: env("DEADLINER_HOME")
        ?: ((env("HOME") ?: invalid("HOME is unset; supply --data-dir")) + "/.local/share/deadliner")
    if (!path.startsWith('/')) invalid("Data directory must be absolute")
    umask(63u) // 0077: database and identity are private to the current user.
    if (!NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null))
        throw CliError("STORAGE_ERROR", "Cannot create data directory", 1)
    val lock = open("$path/cli.lock", O_CREAT or O_RDWR, 384)
    if (lock < 0) throw CliError("STORAGE_ERROR", "Cannot open database lock", 1)
    try {
        if (flock(lock, LOCK_EX or LOCK_NB) != 0) throw CliError("BUSY", "Another CLI process is using this data directory", 4)
        val identityFile = "$path/device-id"
        val identity = fopen(identityFile, "r")?.let { f ->
            try { buildString { while (true) { val c = fgetc(f); if (c == EOF) break; append(c.toChar()) } } }
            finally { fclose(f) }
        } ?: NSUUID().UUIDString.let { id ->
            val f = fopen(identityFile, "w") ?: throw CliError("STORAGE_ERROR", "Cannot write device identity", 1)
            try { if (fputs(id, f) < 0) throw CliError("STORAGE_ERROR", "Cannot write device identity", 1) }
            finally { fclose(f) }
            id
        }
        if (identity.isBlank()) throw CliError("STORAGE_ERROR", "Empty device identity", 1)
        val db = DeadlinerDatabase.create(MacosDatabaseDriverFactory("$path/deadliner.db", identity))
        try { return block(db) } finally { db.close() }
    } finally { close(lock) }
}
