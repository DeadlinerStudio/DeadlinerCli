package com.deadliner.cli

import kotlinx.serialization.json.*

val wire = Json { encodeDefaults = true }
class CliError(val code: String, override val message: String, val status: Int = 2) : Exception(message)
fun invalid(message: String): Nothing = throw CliError("INVALID_ARGUMENT", message)
fun envelope(data: JsonElement) = buildJsonObject { put("ok", true); put("version", 1); put("data", data) }
fun failure(e: CliError) = buildJsonObject {
    put("ok", false); put("version", 1)
    putJsonObject("error") { put("code", e.code); put("message", e.message) }
}

class Arguments(args: Array<String>) {
    val words = mutableListOf<String>()
    val options = mutableMapOf<String, String>()
    init {
        var i = 0
        while (i < args.size) {
            val a = args[i++]
            if (!a.startsWith("--")) words.add(a)
            else {
                val key = a.removePrefix("--")
                if (key in options) invalid("Duplicate option: $a")
                options[key] = if (key in setOf("dry-run", "help", "json")) "true"
                else args.getOrNull(i++) ?: invalid("Missing value for $a")
            }
        }
    }
    fun validate(allowed: Set<String>) {
        val unknown = options.keys - allowed - setOf("data-dir", "json", "help")
        if (unknown.isNotEmpty()) invalid("Unknown options: ${unknown.joinToString()}")
    }
    fun input(): JsonObject {
        if (options["input"] != "-") invalid("Use --input - and supply one JSON object on stdin")
        val text = buildString {
            while (true) {
                val line = readlnOrNull() ?: break
                appendLine(line)
                if (length > 1048576) invalid("Input exceeds 1 MiB")
            }
        }
        return try { wire.parseToJsonElement(text) as? JsonObject ?: invalid("Input must be an object") }
        catch (_: kotlinx.serialization.SerializationException) { invalid("Invalid JSON") }
    }
}

fun JsonObject.text(key: String, nullable: Boolean = false): String? {
    val v = get(key) ?: return null
    if (v == JsonNull && nullable) return null
    if (v !is JsonPrimitive || !v.isString) invalid("$key must be a string${if (nullable) " or null" else ""}")
    return v.content
}
fun JsonObject.flag(key: String): Boolean? {
    val v = get(key) ?: return null
    if (v !is JsonPrimitive || v.isString) invalid("$key must be boolean")
    return v.booleanOrNull ?: invalid("$key must be boolean")
}

val commandSchema: JsonObject get() = buildJsonObject {
    put("name", "deadliner"); put("protocolVersion", 1); put("platform", "macos-arm64")
    put("output", "One JSON envelope on stdout; diagnostics on stderr. No interactive prompts.")
    put("input", "task add/update --input - reads a strict JSON object from stdin")
    putJsonArray("commands") {
        listOf("schema", "version", "task list", "task get UID", "task add", "task update UID",
            "task delete UID", "task complete UID", "task reopen UID", "task archive UID",
            "task abandon UID", "task unarchive UID", "sync status", "sync run").forEach { add(it) }
    }
    putJsonObject("taskFields") {
        put("uid", "string, optional on add only; stable retry identifier")
        put("title", "nonblank string; required on add")
        put("note", "string")
        put("startAt", "RFC3339 instant with timezone or null")
        put("dueAt", "RFC3339 instant with timezone or null")
        put("categoryUid", "string or null")
        put("isStarred", "boolean")
    }
    putJsonObject("options") {
        put("global", "--data-dir ABSOLUTE_PATH; --json (default)")
        put("list", "--state active|completed|archived|abandoned|abandonedArchived --query TEXT --limit 1..1000 --offset N")
        put("mutations", "--dry-run; update/delete/actions: --if-updated-at RFC3339")
        put("sync", "DEADLINER_WEBDAV_URL, DEADLINER_WEBDAV_USERNAME, DEADLINER_WEBDAV_PASSWORD; optional DEADLINER_WEBDAV_TIMEOUT_MS")
    }
    putJsonObject("exitCodes") {
        put("0", "success"); put("2", "invalid input"); put("3", "not found")
        put("4", "conflict/state/busy"); put("5", "sync failed"); put("1", "storage/internal failure")
    }
}
