# Deadliner CLI

Agent Native 的 Deadliner 命令行客户端。Kotlin/Native 直接编译接入 DeadlinerCore，复用 SQLite 仓储、任务状态机、change log、删除墓碑及 WebDAV V3 合并逻辑。

首版支持 **macOS Apple Silicon**，不是 Linux/Windows 通用二进制。没有网络时本地任务管理仍可使用。CLI 不依赖 JVM 运行；源码构建需要 JDK、Xcode Command Line Tools 以及 Core 的 Gradle/Kotlin 工具链（当前 Core 配置还需要 Android SDK）。

## 一键安装（macOS Apple Silicon）

```sh
npm install -g https://github.com/DeadlinerStudio/DeadlinerCli/releases/download/v0.2.0/deadlinerstudio-cli-0.2.0.tgz
deadliner version
deadliner schema
```

安装包内置原生可执行文件，无运行时依赖和安装脚本，不需要 JDK、Xcode、Core 源码或 npm 登录。npm 按 `os`/`cpu` 限制目标平台。此版本通过 GitHub Release 分发，尚未发布到 npm registry，因此请使用完整安装包 URL。发布页提供 SHA256SUMS。

卸载：`npm uninstall -g @deadlinerstudio/cli`。卸载保留用户数据目录。

## 构建与运行

```sh
# 此聚合工作区默认使用 ../../DeadlinerCore，也可显式设置：
export DEADLINER_CORE_DIR=/absolute/path/to/DeadlinerCore
./scripts/build.sh
./bin/deadliner schema
./bin/deadliner version
```

构建将 Core 复制到忽略目录 `.build/core`，排除私有 Lifi overlay 并使用 stub；不修改原 Core checkout。可将 Gradle 参数传给构建脚本，例如 `./scripts/build.sh --offline`。Core 基线见 `core-revision.txt`；兼容更新后需重新运行集成测试。`version` 输出构建时 Core commit 标识；本地 Core 未提交变更也会进入源码快照，因此发布构建应使用干净 checkout。

把 `bin/deadliner` 复制到 PATH 中已有的用户目录即可。默认数据库目录为 `~/.local/share/deadliner`，可设置 `DEADLINER_HOME` 或逐次传 `--data-dir /absolute/path`。目录含 SQLite 数据、持久设备 ID、进程锁，应作为一个独立 CLI profile 使用，不应指向正在使用的 App 数据库。备份时先停掉该 profile 的 CLI 进程，再复制整个目录。

## 日常使用

直接在终端运行会显示中文帮助、任务列表、详情和操作结果；不用阅读或编写 JSON。

```sh
deadliner --help
deadliner task add --title "提交周报" --due "2026-11-11T18:00:00+08:00"
deadliner task list
deadliner task get <ID>
deadliner task update <ID> --note "附上数据" --clear-due
deadliner task complete <ID>
```

列表保留完整任务 ID；日期按本机时区显示，并标明时区。`--human` 强制易读输出，适合复制到文件。默认隐藏易读模式下的 Core 诊断日志，`--verbose` 可以打开。错误写到 stderr 并保持原退出码。`--dry-run` 会明确提示未保存。

人类输入可用 `--title`、`--note`、`--start`、`--due`；新建可用 `--uid`。`--clear-start` / `--clear-due` 清除时间。复杂字段仍支持以下 JSON 输入；两种输入方式不能混用。

## JSON 任务管理

```sh
printf '%s\n' '{"uid":"report-20261110","title":"提交周报","dueAt":"2026-11-10T18:00:00+08:00"}' \
  | deadliner task add --input -
deadliner task list --state active --query 周报 --limit 20 --offset 0
deadliner task get report-20261110
printf '%s\n' '{"note":"附上数据","dueAt":null}' \
  | deadliner task update report-20261110 --input -
deadliner task complete report-20261110
deadliner task archive report-20261110
deadliner task unarchive report-20261110
deadliner task reopen report-20261110
deadliner task abandon report-20261110
deadliner task delete report-20261110 --dry-run
```

- 更新是局部 patch；未知字段、非法类型和空标题均拒绝。`null` 清除日期/分类，`""` 清空 note。日期需 RFC3339 且有时区。
- 任务状态只能通过动作修改；动作约束由 Core 决定。`complete`/`abandon` 不会在重试时切回 active。
- add 可传稳定 `uid`；相同意图的重试返回 `NO_OP`，UID 内容冲突返回 4。任务后来被修改/删除时同样返回冲突，不复活旧数据。
- update/delete/状态动作支持 `--if-updated-at`，值来自最近一次 get 的 `updatedAt`。冲突后重新读取；不要自动移除条件。
- mutation 支持 `--dry-run`。它预览目标字段/状态，不持久化任务，不保留锁/版本，也不保证未来提交能通过活跃计时器等 Core 约束。
- list 默认返回所有未删除状态，按 UID 稳定排序；默认 limit=100，最大 1000。跨次分页期间数据可能变化，不是快照游标。
- delete 使用 Core soft-delete/tombstone，后续同步可传播删除。

## Agent 协议

终端默认易读输出；输出到管道/文件时默认 JSON。Agent 应显式传 `--json`，不依赖是否有终端。`schema` 默认始终为 JSON；`--human` 可查看易读帮助。JSON 模式 stdout 只输出一个 JSON 对象；Core 日志转至 stderr，无交互提示。通过 `schema` 自发现命令和字段。成功示例：

```json
{"ok":true,"version":1,"data":{"items":[],"total":0,"offset":0,"limit":100}}
```

错误示例：

```json
{"ok":false,"version":1,"error":{"code":"NOT_FOUND","message":"Task not found"}}
```

退出码：0 成功，2 参数错误，3 不存在，4 版本/状态冲突或 profile 被占用，5 同步失败，1 存储/内部失败。用退出码和 `ok` 判断结果，勿解析人类日志。所有同 profile 的 CLI 操作受非阻塞进程锁保护；占用时返回 `BUSY`，不用删除锁文件。

## WebDAV

凭据通过环境变量传入；不要将密码写入命令参数或版本库。配置所用的环境变量：

| 环境变量 | 含义 |
| --- | --- |
| `DEADLINER_WEBDAV_URL` | WebDAV 根集合 HTTP(S) URL；远端建议 HTTPS |
| `DEADLINER_WEBDAV_USERNAME` | 用户名 |
| `DEADLINER_WEBDAV_PASSWORD` | 密码/应用密码 |
| `DEADLINER_WEBDAV_TIMEOUT_MS` | 单请求超时，默认 15000，范围 1..300000 |

```sh
deadliner sync status
deadliner sync run
```

`status` 只报告环境是否配置，不测试连接。`run` 是显式双向同步：拉取、合并、条件上传并确认 change log。与当前 Core 客户端共用 `Deadliner/kmp-changelog-v1.json`；Core ETag 失败会重读并重试一次。同步覆盖 Core 支持的实体，CLI 当前仅提供 Task 的命令，不丢弃远端习惯等数据。不自动迁移旧 V2，也不读写旧 snapshot-v2 文件。旧客户端需先在现有 App 中完成迁移。

同步失败时可能已应用拉取内容；未成功上传的本地变更仍待重试。数据修改不会隐式同步。未提供真实账户时只验证本地 fixture，不代表任意云厂商都已验收。

## 验证

```sh
python3 tests/integration.py
```

测试运行真实 native binary/SQLite 和两个独立 profile，使用回环 HTTP fixture 验证 HEAD/GET/PROPFIND/PUT、Basic Auth、ETag 412 重试、失败恢复及删除传播。不会连接用户账户。

配套 Skills 位于相邻的 [DeadlinerCli-Skills](https://github.com/DeadlinerStudio/DeadlinerCli-Skills) 仓库。

## 打包发布

在 Apple Silicon 上构建并运行集成测试后执行 `npm pack`，得到 `deadlinerstudio-cli-0.2.0.tgz`。prepack 会检查 Mach-O 架构、CLI 版本和 Core commit；将包与 SHA256SUMS 上传到同版本 GitHub Release。安装包不包含开发缓存或账号信息。

Core 对应源码：[DeadlinerCore](https://github.com/DeadlinerStudio/DeadlinerCore)，准确提交见 `core-revision.txt`；CLI 对应源代码随安装包附带，也保存在 Release tag 中。
