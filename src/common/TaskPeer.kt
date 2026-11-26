package com.deadliner.cli

import com.deadliner.db.DeadlinerDatabase
import kotlinx.serialization.json.*

// Limit the peer protocol to tasks and the categories referenced by native cards.
// Preserve Core's entity versions, explicit tombstones, and embedded subtasks.
private fun taskSnapshot(payload: JsonObject): JsonObject {
    val snapshot = payload["snapshot"] as? JsonObject ?: invalid("Missing task snapshot")
    val keys = setOf("format_version", "version", "categories", "tasks")
    return buildJsonObject {
        payload["formatVersion"]?.let { put("formatVersion", it) }
        put("snapshot", JsonObject(snapshot.filterKeys { it in keys }))
    }
}

fun exchangeTasks(db: DeadlinerDatabase, a: Arguments): JsonObject {
    a.validate(emptySet())
    val line = readlnOrNull() ?: invalid("Expected a task snapshot")
    val input = try { wire.parseToJsonElement(line) as? JsonObject } catch (_: Exception) { null }
    val remote = taskSnapshot(input ?: invalid("Invalid task snapshot"))
    val result = db.sync.mergeRemotePayload(remote.toString())
    if (result.failureMessage != null) invalid("Core rejected the task snapshot")
    return taskSnapshot(wire.parseToJsonElement(result.payload).jsonObject)
}
