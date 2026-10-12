---
name: "resilient-large-file-download"
description: "Builds reliable large-file downloaders for constrained networks. Invoke when implementing HTTP download, fixing truncated downloads, or adding integrity checks for model/checkpoint/artifact files."
---

# Resilient Large-File Download

在受限网络环境（手机容器、弱网、移动数据、Proot/Docker 里）下载几百 MB 以上的大文件（GGUF、模型权重、数据集）时，网络抖动是常态。一个"能跑"的下载器必须被加固成一个"**能自恢复、能自校验、能自报告**"的下载器——否则用户会反复遇到"下完了但用不了"的体验。

## 何时触发

遇到以下场景时调用本 skill：

- 写一个模型/大文件下载器（HTTP/HTTPS）
- 用户反馈"下载成功但打开/加载报错"、"文件损坏"
- 下载被静默截断（文件大小比预期小，进程却以为成功了）
- 需要给现有下载器加完整性校验、重试或断点续传
- 下载发生在受限容器 / Proot / Android 沙箱 / 弱网环境

## 核心原则（按顺序严格执行）

### 1. 临时文件 + 原子 rename

```python
# 正确：先写 <target>.part，成功才 rename 到 <target>
part = target.with_suffix(target.suffix + ".part")
try:
    await download_into(url, part)
    part.replace(target)          # os.replace = 原子替换
except Exception:
    part.unlink(missing_ok=True)  # 失败了清掉残缺文件
```

**禁止**直接写目标文件——用户取消、进程崩溃、网络中断都会留下"看似存在实则残缺"的文件，下一次启动就会把它当成有效文件加载。

### 2. 完整性校验（三层）

下载完立刻验，**任何一层不通过都视为失败**：

| 层 | 校验 | 说明 |
|----|------|------|
| L1 | `Content-Length` 对比 | 服务端返回了 Content-Length 就必须精确匹配（==） |
| L2 | 文件头魔数/格式标识 | 例如 GGUF 前 4 字节是 `b"GGUF"`，ELF 是 `b"\x7fELF"`，PNG 是 `b"\x89PNG"` |
| L3（可选） | 哈希（SHA-256） | catalog 里预置哈希，适合重要文件；HuggingFace 有 `?sha256=` 查询参数 |

L1 挡不住"连接被服务器提前关闭但 Python 读到 EOF 正常退出"的情况，**L2 是最后一道防线**——残缺文件的头部是对的（前几个 chunk 肯定是文件头），但 L1 能挡住大小不对。两层叠加覆盖 99% 的截断场景。

### 3. 网络重试 + 指数退避

手机网络抖动、Proot 容器偶尔丢包是常态。**不要让一次 socket 超时或 EOF 就直接 fail**：

```python
MAX_RETRIES = 3
BACKOFF = 2.0  # 秒

for attempt in range(1, MAX_RETRIES + 1):
    try:
        await _single_attempt(url, part)
        if task.done != task.total:
            raise IOError(f"下载不完整：期望 {task.total}，实际 {task.done}")
        if not _check_magic(part):
            raise IOError("文件头不是合法魔数，可能被截断")
        return  # 成功
    except (TimeoutError, OSError, IOError):
        part.unlink(missing_ok=True)  # 重试前清掉旧 .part
        if attempt < MAX_RETRIES:
            task.error = f"下载中断，{BACKOFF*attempt:.0f}s 后第 {attempt+1} 次重试…"
            time.sleep(BACKOFF * attempt)
            continue
        raise
```

### 4. 断点续传（可选但推荐）

HuggingFace / hf-mirror 等镜像站**都支持 `Range` 头**，用上它能在"下载到一半断了"时从上一次的位置继续：

```python
existing = part.stat().st_size if part.exists() else 0
range_header = f"bytes={existing}-" if existing > 0 else "bytes=0-"

request.headers["Range"] = range_header
response = urlopen(request)
resuming = response.status == 206 and existing > 0

with part.open("ab" if resuming else "wb") as f:
    # ...正常读 chunk 写 chunk...
```

注意：服务器可能忽略 `Range` 返回 200 而不是 206，这时要从 0 覆盖写入（`truncate + seek(0)`），否则会拼出一个头部混乱的文件。

### 5. 注册表 / 消费端双验

**不能把"文件存在"等同于"文件有效"**。任何会调用 `is_downloaded()` / `has_model()` / `check_file()` 的地方都要加一层魔数校验：

```python
# ❌ 错误
def is_downloaded(spec):
    return self.path_of(spec).exists()

# ✅ 正确
def is_downloaded(spec):
    path = self.path_of(spec)
    if not path.exists():
        return False
    try:
        with path.open("rb") as f:
            return f.read(4) == b"GGUF"  # 或对应格式的魔数
    except OSError:
        return False
```

这样**残缺文件会被当成"没下载"**，UI 会提示重新下载而不是在加载时报不知所云的"tensor out of bounds"。

### 6. 友好错误信息

失败时必须同时给出：退出码（如果有）+ 最后几十行 stderr/stdout（如果有）+ 可能原因的猜测：

```python
raise BackendUnavailableError(
    f"llama-server 启动失败，退出码 {rc}\n"
    f"llama-server 输出:\n{snippet}\n"
    f"可能原因：{diagnose(rc, tail)}"
)
```

`diagnose` 基于关键词匹配，例如：
- `Address already in use` → 端口被占，先杀旧进程或换端口
- `out of memory` / `OOM` → 容器内存不够，换小模型或调小 `n_ctx`
- `not within the file bounds` / `model is corrupted` → GGUF 残缺，删掉重下
- `unknown model architecture` → llama.cpp 版本太旧，升级二进制
- `no such file` → 模型路径拼错了

## 排障清单（用户说"下载的东西用不了"时）

按顺序排查，5 步搞定根因：

| 步骤 | 检查 | 命令 / 代码 |
|------|------|-------------|
| ① 看大小 | 文件大小是否匹配 catalog 里的预期 | `ls -lh file.gguf` 对比 `approx_size_mb` |
| ② 看头部 | 魔数对不对 | `head -c 4 file.gguf \| od -An -tx1`（`47475546` = "GGUF"） |
| ③ 看尾部 | 文件末尾是否被截断（随机 0x00 或不完整的 tensor 块） | `tail -c 100 file.gguf \| xxd` |
| ④ 手动加载 | 直接调消费方（llama-server）看真实 stderr | `llama-server -m file.gguf --port 8099 -c 2048 -t 2 -ngl 0` |
| ⑤ 查日志 | 下载过程中有没有重试/截断提示 | grep 下载日志里的 "下载不完整" / "重试" |

## 常见文件格式魔数

| 格式 | 前 4 字节 | 说明 |
|------|-----------|------|
| GGUF | `47 47 55 46` (`b"GGUF"`) | llama.cpp 模型格式 |
| ELF | `7f 45 4c 46` | Linux 可执行文件 |
| PNG | `89 50 4e 47` | 图片 |
| SQLite | `53 51 4c 69` (`b"SQLi"`) | 数据库 |
| ZIP / APK | `50 4b 03 04` | 压缩包 |
| Torch / bin (pickle) | `80 02 00 00` (或前 2 字节是 pickle 协议号) | PyTorch 权重 |

## 不要做的事

- ❌ 不要用 `wget` / `curl` 直接写目标文件——进程被杀会留残缺
- ❌ 不要只用 `os.path.exists` 校验"已下载"——残缺文件也存在
- ❌ 不要让一次网络抖动就直接 fail——3 次重试是底线
- ❌ 不要吞掉 stderr——消费方（llama-server、python、java）的真实错误信息是无价的调试线索
- ❌ 不要假设 Content-Length 一定存在（某些 CDN 用 Transfer-Encoding: chunked）——这时 L2 魔数校验就是主力

## 典型修改参考（本次 memo-role 修复）

改动文件和目的：

```
memo_role/inference/downloader.py  ← 下载核心：三层校验 + 重试 + 断点续传
memo_role/inference/registry.py    ← 注册表：is_downloaded 加魔数校验
memo_role/inference/llama_server.py ← 消费方：启动失败时 surfacing stderr + 智能诊断
```

这三处形成一个闭环：**下载不完整 → 注册表不认可 → UI 提示重下 → 不会走到消费方**。即使残缺文件已经在磁盘上，注册表的双重校验也会把它挡在加载门外。
