# 验证记录

日期：2026-11-09（Asia/Shanghai）。平台：macOS Apple Silicon。

- `./scripts/build.sh --offline`：通过，Kotlin/Native debug executable 构建成功。Core commit `7ce16d278753e8550b0e40a7483f4ad2170dfdfd`，采用当前本地源码快照；Core 的已有弃用/expect-actual 警告不阻断构建。
- `python3 tests/integration.py`：6 组通过，2.773 秒。每次测试使用临时数据库与真实 CLI 子进程。
- CRUD：跨进程读取、条件更新冲突、局部 patch、日期清除、非法字段/类型/空标题、dry-run、软删除。
- Agent 行为：schema、version、稳定 UID 重试与冲突、complete/abandon 重试不翻转、归档恢复、筛选分页、非阻塞 profile 锁。
- WebDAV：两个独立 Core 数据库；Basic Auth；远端创建/更新/删除传播；ETag 412 内部重试；认证失败；PUT 503 后本地数据保留与成功重试。
- 每次 CLI 调用均将整个 stdout 解析为单个 JSON，对日志隔离进行实际验证。
- Python 语法和 shell 语法检查通过；两个配套 Skills 均通过 skill-creator quick_validate（临时 PyYAML 依赖）。

边界：未连接真实用户 WebDAV 服务；未验证不同云厂商行为、跨 App 真机同步或 Linux/Windows。CLI 构建目标明确为 macOS Apple Silicon。原 Core checkout 无源码修改。

## npm 安装包验收（2026-11-10）

- `npm pack` 成功，包含 arm64 原生 binary、CLI 源码、构建脚本和 Core commit 记录，无第三方 npm 依赖。
- 安装到临时 prefix，并使用 `--ignore-scripts`，`deadliner version` / `schema` 均成功；无需安装脚本。
- 对安装后的 binary 运行 `DEADLINER_TEST_BINARY=... python3 tests/integration.py`：6 组全部通过（2.591 秒）。
- 发布渠道为 GitHub Release 的 `.tgz`，没有发布至 npm registry；包名 `@deadlinerstudio/cli` 用于本地安装/卸载标识。
