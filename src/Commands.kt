package com.deadliner.cli

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.repository.TaskActionResult
import com.deadliner.domain.task.*
import com.deadliner.sync.webdav.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import platform.Foundation.NSUUID

private val fields = setOf("title", "note", "startAt", "dueAt", "categoryUid", "isStarred")
private val actions = mapOf("complete" to TaskAction.MARK_COMPLETE, "reopen" to TaskAction.RESTORE_ACTIVE,
    "archive" to TaskAction.MARK_ARCHIVE, "abandon" to TaskAction.MARK_GIVE_UP, "unarchive" to TaskAction.UNARCHIVE)

suspend fun execute(db: DeadlinerDatabase, a: Arguments): JsonElement {
    if (a.words.firstOrNull() == "sync") return sync(db, a)
    if (a.words.firstOrNull() != "task") invalid("Unknown command; use schema")
    val op = a.words.getOrNull(1) ?: invalid("Missing task operation")
    if (op !in setOf("list", "add", "get", "update", "delete") + actions.keys) invalid("Unknown task operation")
    val needsId = op !in setOf("list", "add")
    if (a.words.size != if (needsId) 3 else 2) invalid("Unexpected or missing positional argument")
    val allowed = when (op) {
        "list" -> setOf("state", "query", "limit", "offset")
        "get" -> emptySet()
        "add" -> setOf("input", "dry-run", "uid", "title", "note", "start", "due", "clear-start", "clear-due")
        "update" -> setOf("input", "dry-run", "if-updated-at", "title", "note", "start", "due", "clear-start", "clear-due")
        else -> setOf("dry-run", "if-updated-at")
    }
    a.validate(allowed)
    if (op == "list") {
        val state = a.options["state"]?.let { name ->
            TaskState.entries.firstOrNull { wire.encodeToJsonElement(it).jsonPrimitive.content == name }
                ?: invalid("Unknown state")
        }
        fun number(key: String, default: Int, min: Int, max: Int): Int = a.options[key]?.let {
            it.toIntOrNull()?.takeIf { n -> n in min..max } ?: invalid("Invalid $key")
        } ?: default
        val limit = number("limit", 100, 1, 1000)
        val offset = number("offset", 0, 0, Int.MAX_VALUE)
        val tasks = db.tasks.list().filter { !it.isDeleted && (state == null || it.state == state) }
            .filter { a.options["query"]?.let { q -> it.title.contains(q, true) || it.note.contains(q, true) } ?: true }
            .sortedBy { it.uid }
        return buildJsonObject {
            put("items", wire.encodeToJsonElement(tasks.drop(offset).take(limit)))
            put("total", tasks.size); put("offset", offset); put("limit", limit)
        }
    }
    val now = Clock.System.now().toString()
    val dry = "dry-run" in a.options
    if (op == "add") {
        val input = a.input()
        validateFields(input, true)
        val uid = input.text("uid") ?: NSUUID().UUIDString
        if (uid.isBlank() || uid.length > 200) invalid("uid must contain 1..200 characters")
        val title = input.text("title") ?: invalid("title is required")
        val candidate = patch(Task(uid, title, createdAt = now, updatedAt = now), input)
        val existing = db.tasks.find(uid)
        if (existing != null) {
            // A stable UID is an idempotency key only while the existing task still matches the intent.
            if (existing.isDeleted || candidate.copy(createdAt = existing.createdAt, updatedAt = existing.updatedAt) != existing)
                throw CliError("CONFLICT", "uid already exists with different content", 4)
            return taskResult(existing, dry, "NO_OP")
        }
        if (!dry) db.tasks.create(candidate)
        return taskResult(candidate, dry)
    }
    val uid = a.words[2]
    val current = db.tasks.find(uid)?.takeUnless { it.isDeleted }
        ?: throw CliError("NOT_FOUND", "Task not found", 3)
    if (op == "get") return wire.encodeToJsonElement(current)
    a.options["if-updated-at"]?.let {
        if (it != current.updatedAt) throw CliError("CONFLICT", "Task changed; read it again", 4)
    }
    if (op == "update") {
        val input = a.input()
        validateFields(input, false)
        if (input.isEmpty()) invalid("Patch must not be empty")
        val candidate = patch(current, input).copy(updatedAt = now)
        return if (dry) taskResult(candidate, true) else checked(db.tasks.update(candidate))
    }
    if (op == "delete") return if (dry) taskResult(current.copy(isDeleted = true), true)
        else checked(db.tasks.delete(uid, now))
    val action = actions.getValue(op)
    // Core's complete/give-up actions toggle. CLI verbs express the desired state and are retry safe.
    val satisfied = (op == "complete" && current.state in setOf(TaskState.COMPLETED, TaskState.ARCHIVED)) ||
        (op == "abandon" && current.state in setOf(TaskState.ABANDONED, TaskState.ABANDONED_ARCHIVED)) ||
        TaskStateMachine.isNoOp(current.state, action)
    if (satisfied) return taskResult(current, dry, "NO_OP")
    val target = TaskStateMachine.apply(current.state, action)
        ?: throw CliError("INVALID_STATE", "Action $op is invalid for ${current.state}", 4)
    return if (dry) taskResult(current.copy(state = target, updatedAt = now), true)
        else checked(db.tasks.applyAction(uid, action, now))
}

private fun validateFields(input: JsonObject, add: Boolean) {
    val unknown = input.keys - fields - if (add) setOf("uid") else emptySet()
    if (unknown.isNotEmpty()) invalid("Unknown or read-only fields: ${unknown.joinToString()}")
    input.text("title")?.let { if (it.isBlank()) invalid("title must not be blank") }
    input.text("note"); input.flag("isStarred")
    input.text("categoryUid", true)?.let { if (it.isBlank()) invalid("categoryUid must not be blank") }
    for (key in listOf("startAt", "dueAt")) input.text(key, true)?.let {
        try { Instant.parse(it) } catch (_: Exception) { invalid("$key must be an RFC3339 instant with timezone") }
    }
}

private fun patch(t: Task, p: JsonObject) = t.copy(
    title = p.text("title") ?: t.title, note = p.text("note") ?: t.note,
    startAt = if ("startAt" in p) p.text("startAt", true) else t.startAt,
    dueAt = if ("dueAt" in p) p.text("dueAt", true) else t.dueAt,
    categoryUid = if ("categoryUid" in p) p.text("categoryUid", true) else t.categoryUid,
    isStarred = p.flag("isStarred") ?: t.isStarred,
)
private fun taskResult(t: Task, dry: Boolean, outcome: String = "APPLIED") = buildJsonObject {
    put("task", wire.encodeToJsonElement(t)); put("dryRun", dry); put("outcome", outcome)
}
private fun checked(result: TaskActionResult): JsonElement {
    if (result.outcome !in setOf(TaskActionOutcome.APPLIED, TaskActionOutcome.NO_OP))
        throw CliError(result.outcome.name, "Core rejected task operation: ${result.outcome.name}", 4)
    return taskResult(requireNotNull(result.task), false, result.outcome.name)
}

private suspend fun sync(db: DeadlinerDatabase, a: Arguments): JsonElement {
    a.validate(emptySet())
    if (a.words.size != 2 || a.words[1] !in setOf("status", "run")) invalid("Use sync status or sync run")
    val url = env("DEADLINER_WEBDAV_URL")
    if (a.words[1] == "status") return buildJsonObject {
        put("configured", !url.isNullOrBlank()); put("protocol", "core-v3-changelog")
        put("remotePath", "Deadliner/kmp-changelog-v1.json")
    }
    if (url.isNullOrBlank()) invalid("Set DEADLINER_WEBDAV_URL")
    val parsed = io.ktor.http.Url(url)
    if (parsed.protocol.name !in setOf("http", "https") || parsed.host.isBlank() ||
        parsed.user != null || parsed.password != null || parsed.parameters.names().isNotEmpty() || parsed.fragment.isNotEmpty())
        invalid("WebDAV URL must be HTTP(S) without credentials, query or fragment")
    val timeout = env("DEADLINER_WEBDAV_TIMEOUT_MS")?.let {
        it.toLongOrNull()?.takeIf { n -> n in 1..300000 } ?: invalid("Invalid WebDAV timeout (1..300000 ms)")
    } ?: 15000L
    val config = WebDavTransportConfiguration(url, WebDavCredentials(env("DEADLINER_WEBDAV_USERNAME"), env("DEADLINER_WEBDAV_PASSWORD")), timeout)
    val result = db.webDavChangeLogSync.synchronizeSafely(config)
    if (!result.succeeded) throw CliError("SYNC_FAILED", "WebDAV sync failed; verify connectivity, credentials and remote payload. Local changes remain available for retry.", 5)
    return wire.encodeToJsonElement(result)
}
