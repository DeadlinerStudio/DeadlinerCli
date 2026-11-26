package com.deadliner.cli

import kotlinx.serialization.json.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// Remote task text must never inject terminal control sequences or visual direction overrides.
private fun safe(value: String): String = buildString {
    for (c in value) when {
        c == '\n' || c == '\r' -> append(" ↵ ")
        c == '\t' -> append(" ")
        c.code < 32 || c.code in 127..159 || c.code in 0x202a..0x202e || c.code in 0x2066..0x2069 -> Unit
        else -> append(c)
    }
}
private fun JsonObject.value(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun date(value: String): String {
    if (value.isEmpty()) return "未设置"
    return try {
        val local = Instant.parse(value).toLocalDateTime(TimeZone.currentSystemDefault())
        "${local.date} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    } catch (_: Exception) { safe(value) }
}
private fun state(task: JsonObject) = if (task.value("isDeleted") == "true") "已删除" else when (task.value("state")) {
    "active" -> "待办"
    "completed" -> "已完成"
    "archived" -> "已归档"
    "abandoned" -> "已放弃"
    "abandonedArchived" -> "已放弃 · 已归档"
    else -> safe(task.value("state"))
}
private fun taskDetails(t: JsonObject): String = buildString {
    appendLine(safe(t.value("title")))
    appendLine("状态：${state(t)}${if (t.value("isStarred") == "true") " · 星标" else ""}")
    appendLine("截止：${date(t.value("dueAt"))}")
    if (t.value("startAt").isNotEmpty()) appendLine("开始：${date(t.value("startAt"))}")
    if (t.value("note").isNotEmpty()) appendLine("备注：${safe(t.value("note"))}")
    if (t.value("categoryUid").isNotEmpty()) appendLine("分类 ID：${safe(t.value("categoryUid"))}")
    val subtasks = t["subtasks"] as? JsonArray
    if (!subtasks.isNullOrEmpty()) {
        appendLine("子任务：")
        subtasks.forEach { child ->
            val item = child.jsonObject
            appendLine("  ${if (item.value("isCompleted") == "true") "[x]" else "[ ]"} ${safe(item.value("content"))}")
        }
    }
    appendLine("ID：${safe(t.value("uid"))}")
    appendLine("更新版本：${safe(t.value("updatedAt"))}")
    append("时间显示：${TimeZone.currentSystemDefault().id}")
}

fun humanOutput(response: JsonObject, words: List<String>): String {
    if (response["ok"]?.jsonPrimitive?.boolean != true) {
        val error = response.getValue("error").jsonObject
        val summary = when (error.value("code")) {
            "NOT_FOUND" -> "找不到这个任务，请先用 task list 查看任务 ID。"
            "CONFLICT" -> "任务已变更或 ID 已被使用，请重新读取后再操作。"
            "BUSY" -> "另一个命令正在使用这个数据目录，请稍后重试。"
            "INVALID_STATE" -> "当前任务状态不支持此操作。"
            "TIMER_REQUIRES_RESOLUTION" -> "任务有正在进行的专注计时，请先处理计时。"
            "SYNC_FAILED" -> "同步失败，请检查网络、WebDAV 地址和凭据。本地变更仍可重试。"
            "INTERNAL_ERROR", "STORAGE_ERROR" -> "无法完成操作，请检查数据目录权限。"
            else -> "参数有误，请用 deadliner --help 查看用法。"
        }
        return "错误：$summary\n${safe(error.value("message"))}\n错误码：${safe(error.value("code"))}"
    }
    val data = response.getValue("data").jsonObject
    if ("commands" in data) return humanHelp
    if ("cli" in data) return "Deadliner ${data.value("cli")}\nCore：${data.value("core")}"
    if ("items" in data) {
        val items = data.getValue("items").jsonArray
        val total = data.value("total")
        val offset = data.value("offset").toInt()
        return buildString {
            appendLine("任务列表 · 共 $total 项")
            if (items.isEmpty()) appendLine(if (total == "0") "没有匹配的任务。" else "这一页没有任务，请减小 --offset。")
            items.forEachIndexed { i, item ->
                val t = item.jsonObject
                appendLine("\n${offset + i + 1}. [${state(t)}] ${safe(t.value("title"))}${if (t.value("isStarred") == "true") " ★" else ""}")
                appendLine("   截止：${date(t.value("dueAt"))}")
                appendLine("   ID：${safe(t.value("uid"))}")
            }
            if (offset + items.size < total.toInt()) appendLine("\n下一页：task list --offset ${offset + items.size}（保留当前筛选条件）")
            append("时间显示：${TimeZone.currentSystemDefault().id}")
        }
    }
    if ("task" in data) {
        val verb = when (words.getOrNull(1)) {
            "add" -> "任务已创建"; "update" -> "任务已更新"; "delete" -> "任务已删除"
            "complete" -> "任务已完成"; "reopen" -> "任务已恢复为待办"; "archive" -> "任务已归档"
            "abandon" -> "任务已放弃"; "unarchive" -> "任务已取消归档"; else -> "操作成功"
        }
        val heading = when {
            data.value("dryRun") == "true" -> "操作预览 · 未保存任何任务变更"
            data.value("outcome") == "NO_OP" -> "任务已符合要求，无需修改"
            else -> verb
        }
        return "$heading\n\n${taskDetails(data.getValue("task").jsonObject)}"
    }
    if ("uid" in data) return taskDetails(data)
    if ("configured" in data) return if (data.value("configured") == "true")
        "WebDAV 地址已配置（尚未测试连接）\n运行 deadliner sync run 开始双向同步。"
        else "尚未配置 WebDAV\n请设置 DEADLINER_WEBDAV_URL、DEADLINER_WEBDAV_USERNAME 和 DEADLINER_WEBDAV_PASSWORD。"
    if ("succeeded" in data) return "同步完成\n应用远端变更：${data.value("remoteApplied")}\n忽略远端变更：${data.value("remoteIgnored")}"
    return "操作完成。"
}

val humanHelp = """
Deadliner · 终端任务管理

快速开始
  deadliner task add --title "提交周报" --due "2026-11-11T18:00:00+08:00"
  deadliner task list
  deadliner task get <ID>
  deadliner task update <ID> --title "修改标题" --note "补充说明"
  deadliner task complete <ID>

任务命令
  list                         查看列表，可加 --state active --query 关键词
                               分页：--limit 20 --offset 0
  get <ID>                     查看详情
  add --title 标题             新建任务，可加 --uid、--note、--start、--due
  update <ID>                  修改字段；--clear-due / --clear-start 清除时间
  complete / reopen <ID>       完成 / 恢复待办
  archive / unarchive <ID>     归档 / 取消归档
  abandon / delete <ID>        放弃 / 删除

其他命令
  sync status                  查看 WebDAV 是否配置
  sync run                     双向同步（需要 WebDAV 环境变量）
  version                      查看版本
  schema                       输出 Agent 使用的 JSON 命令契约

常用选项
  --human                      强制易读输出（也适用于重定向）
  --json                       强制 JSON，适合 Agent 和脚本
  --verbose                    显示 Core 诊断日志
  --data-dir /绝对路径          选择独立数据目录
  --dry-run                    预览任务操作，不保存
  --if-updated-at 时间戳        仅在任务版本未变化时修改
  --input -                    从标准输入读取 JSON（不能与字段选项混用）

终端默认易读输出；管道默认 JSON。日期使用带时区的 RFC3339 格式。
ID 请从列表复制；本地操作不会自动同步。更多字段与错误码见 schema。
""".trimIndent()
