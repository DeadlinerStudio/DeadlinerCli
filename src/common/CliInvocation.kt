package com.deadliner.cli

import com.deadliner.platform.KmpBuildMetadata
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class CliInvocation(private val terminal: Boolean) {
    var jsonOutput = !terminal
        private set
    var words: List<String> = emptyList()
        private set
    var status = 0
        private set

    fun run(args: Array<String>, serve: ((Arguments, Boolean) -> Nothing)? = null, quietLogs: () -> Unit): JsonObject {
        return try {
            val a = Arguments(args)
            words = a.words
            jsonOutput = "json" in a.options || ("human" !in a.options && (!terminal || a.words == listOf("schema")))
            if ("json" in a.options && "human" in a.options) invalid("--json and --human cannot be used together")
            if (!jsonOutput && "verbose" !in a.options) quietLogs()
            val data = when {
                a.words.isEmpty() || a.options.containsKey("help") -> commandSchema
                a.words == listOf("schema") -> { a.validate(emptySet()); commandSchema }
                a.words == listOf("version") -> {
                    a.validate(emptySet())
                    buildJsonObject { put("cli", "0.2.0"); put("core", KmpBuildMetadata.coreVersion); put("lifi", com.deadliner.interfaces.LifiAvailability.isAvailable) }
                }
                a.words == listOf("serve") && serve != null -> serve(a, jsonOutput)
                a.words == listOf("task-peer-exchange") && serve != null -> withDatabase(a) { db -> exchangeTasks(db, a) }
                else -> withDatabase(a) { db -> runBlocking { execute(db, a) } }
            }
            envelope(data)
        } catch (e: CliError) { status = e.status; failure(e) }
        catch (_: Exception) { status = 1; failure(CliError("INTERNAL_ERROR", "Storage or runtime operation failed", 1)) }
    }
}
