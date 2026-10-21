"""从 GitHub / Gitee Release 同步 APP 安装包到本后端的 APP 更新通道。

来源：
    GitHub  https://api.github.com/repos/{UPDATE_GITHUB_REPO}/releases
    Gitee   https://gitee.com/api/v5/repos/{UPDATE_GITEE_REPO}/releases
    只认附件名形如 app-{versionCode}-{versionName}.apk 的 Release（build-apk.yml 推 v* 标签产出）。
    同名 .sha256 附件存在时校验 SHA-256。

国内服务器直连 GitHub 下载很慢或不通：先直连，失败再走 APP_SYNC_GITHUB_PROXY
（默认 https://gh-proxy.com/，置空关闭）。Gitee 有同版本时 auto 模式优先 Gitee。

下载在后台线程执行（APK 几十 MB，慢网要几分钟），绝不放进请求线程或事件循环；
跨进程用 app-releases/.sync.lock 文件锁保证同一时间只有一个同步任务，
进度写 app-releases/.sync-status.json。
"""
from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import sqlite3
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

from fastapi import APIRouter, Body, Depends

try:
    from app.main import ApiError, CODE_FORBIDDEN, current_user
    from app import updates as _updates
except ImportError:  # 以 app 目录为 cwd 运行时的兜底
    from main import ApiError, CODE_FORBIDDEN, current_user  # type: ignore
    import updates as _updates  # type: ignore

router = APIRouter()

SOURCES = ("auto", "github", "gitee")
_TAG_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
_SHA256_RE = re.compile(r"\b([0-9a-f]{64})\b")
_UA = {"User-Agent": "material-flow-app-sync"}
_STALE_SECONDS = 40 * 60
_ACTIVE = ("queued", "downloading", "verifying")
# 下载停滞判定：连续这么久没有任何新字节就放弃（换下一个地址/报错），
# 避免像"卡在 45%"那样无限等下去。
_STALL_SECONDS = 90
# 取消信号：下载循环每读一块检查一次；只有本进程的线程会看到它。
_CANCEL = threading.Event()


def _github_proxy() -> str:
    p = os.environ.get("APP_SYNC_GITHUB_PROXY", "https://gh-proxy.com/").strip()
    return p if not p or p.endswith("/") else p + "/"


def _max_seconds() -> int:
    try:
        return max(60, int(os.environ.get("APP_SYNC_MAX_SECONDS", "1800")))
    except ValueError:
        return 1800


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S+00:00", time.gmtime())


def _admin(user: sqlite3.Row) -> None:
    if user["role"] != "ADMIN":
        raise ApiError(403, CODE_FORBIDDEN, "仅 ADMIN 可同步 APP 版本")


def _dir() -> Path:
    return _updates._releases_dir()


def _status_file() -> Path:
    return _dir() / ".sync-status.json"


# --------------------------------------------------------------------------
# 远端 Release 列表
# --------------------------------------------------------------------------

def _get_json(url: str, timeout: int) -> object:
    req = urllib.request.Request(url, headers={**_UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)


def _parse_releases(data: object, source: str) -> list[dict]:
    out: list[dict] = []
    if not isinstance(data, list):
        return out
    for rel in data:
        if not isinstance(rel, dict) or rel.get("draft") or rel.get("prerelease"):
            continue
        assets = [a for a in (rel.get("assets") or []) if isinstance(a, dict)]
        names = {str(a.get("name") or ""): a for a in assets}
        for name, a in names.items():
            m = _updates._APK_RE.fullmatch(name)
            url = str(a.get("browser_download_url") or "")
            if not m or not url.startswith("https://"):
                continue
            sha_asset = names.get(name + ".sha256")
            out.append({
                "source": source,
                "tag": str(rel.get("tag_name") or ""),
                "versionCode": int(m.group(1)),
                "versionName": m.group(2),
                "assetName": name,
                "downloadUrl": url,
                "sha256Url": str(sha_asset.get("browser_download_url") or "") if sha_asset else "",
                "size": int(a.get("size") or 0),
                "changelog": str(rel.get("body") or rel.get("name") or "").strip()[:2000],
                "publishedAt": str(rel.get("published_at") or rel.get("created_at") or ""),
            })
            break  # 每个 Release 只取一个 APK
    return out


def list_remote(source: str = "auto", limit: int = 10) -> dict:
    """查询远端 Release。返回 {releases: [...按 versionCode 降序], errors: [...]}。"""
    if source not in SOURCES:
        raise ApiError(400, "INVALID_SOURCE", "来源只能是 auto / github / gitee")
    order = ["gitee", "github"] if source == "auto" else [source]
    found: dict[int, dict] = {}
    errors: list[str] = []
    for src in order:
        try:
            if src == "github":
                data = _get_json(f"{_updates.GITHUB_API}/releases?per_page={limit}", 15)
            else:
                data = _get_json(f"{_updates.GITEE_API}/releases?per_page={limit}&direction=desc", 15)
            for r in _parse_releases(data, src):
                found.setdefault(r["versionCode"], r)  # auto：同版本先到先得（Gitee 优先）
        except Exception as e:  # noqa: BLE001 - 网络错误记下继续查下一个来源
            errors.append(f"{src}: {str(e)[:160]}")
    if not found and errors:
        raise ApiError(502, "APP_SYNC_SOURCE_ERROR", "查询远端版本失败：" + "；".join(errors)[:300])
    local = {r["versionCode"] for r in _updates.list_releases()}
    rels = sorted(found.values(), key=lambda r: r["versionCode"], reverse=True)[:limit]
    for r in rels:
        r["synced"] = r["versionCode"] in local
    return {"releases": rels, "errors": errors, "checkedAt": _now()}


# --------------------------------------------------------------------------
# 状态
# --------------------------------------------------------------------------

def _write_status(d: dict) -> None:
    _dir().mkdir(parents=True, exist_ok=True)
    d = {**d, "at": _now()}
    tmp = _status_file().with_suffix(".tmp")
    tmp.write_text(json.dumps(d, ensure_ascii=False), encoding="utf-8")
    tmp.replace(_status_file())


def recover_interrupted() -> dict | None:
    """服务启动时调用：把上次进程被杀留下的"下载中"状态收尾。

    同步线程是 daemon，服务重启（发版/重启/崩溃）会把它直接杀掉，
    状态文件就会永远停在某个百分比（现场看到的就是"卡在 45%"）。
    这里把它标成 failed 并清掉半截 .part，前端立刻能重新发起。
    """
    st = get_status()
    if st.get("state") not in _ACTIVE:
        return None
    pct = ""
    try:
        if st.get("total"):
            pct = f"，中断时约 {int(st.get('bytes') or 0) * 100 // int(st['total'])}%"
    except (TypeError, ValueError, ZeroDivisionError):
        pct = ""
    _write_status({**{k: st.get(k) for k in ("source", "tag", "versionCode", "versionName", "actor") if k in st},
                   "state": "failed", "bytes": st.get("bytes") or 0, "total": st.get("total") or 0,
                   "error": f"服务重启导致同步中断{pct}，请重新发起", "finishedAt": _now()})
    for part in _dir().glob(".sync-*.part"):
        part.unlink(missing_ok=True)
    return {"state": "failed", "tag": st.get("tag"), "versionCode": st.get("versionCode")}


def cancel_sync(actor: str = "") -> dict:
    """取消当前同步：有线程在跑就让它自己收尾，没有线程（进程重启过）就直接改状态。"""
    st = get_status()
    if st.get("state") not in _ACTIVE:
        return {"state": st.get("state") or "idle", "cancelled": False}
    _CANCEL.set()
    if _thread_alive():
        # 线程会自己看到信号并在 finally 里收尾，前端轮询状态即可
        return {"state": "cancelling", "cancelled": True, "tag": st.get("tag")}
    # 没有活跃线程（进程重启过）：直接改状态并清半截文件
    _write_status({**{k: st.get(k) for k in ("source", "tag", "versionCode", "versionName") if k in st},
                   "state": "cancelled", "error": "已取消", "actor": actor, "finishedAt": _now()})
    for part in _dir().glob(".sync-*.part"):
        part.unlink(missing_ok=True)
    return {"state": "cancelled", "cancelled": True, "tag": st.get("tag")}


def _thread_alive() -> bool:
    return any(t.name == "app-sync" and t.is_alive() for t in threading.enumerate())


def get_status() -> dict:
    try:
        st = json.loads(_status_file().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"state": "idle"}
    if st.get("state") in _ACTIVE:
        try:
            age = time.time() - _status_file().stat().st_mtime
        except OSError:
            age = 0
        if age > _STALE_SECONDS:
            st["state"] = "failed"
            st["error"] = st.get("error") or "同步长时间无进展（服务可能重启过），可重新发起"
    return st


# --------------------------------------------------------------------------
# 下载与发布
# --------------------------------------------------------------------------

def _candidate_urls(rel: dict, url: str) -> list[str]:
    if rel["source"] == "github" and _github_proxy():
        return [url, _github_proxy() + url]
    return [url]


def _fetch_text(rel: dict, url: str) -> str:
    last: Exception | None = None
    for u in _candidate_urls(rel, url):
        try:
            with urllib.request.urlopen(urllib.request.Request(u, headers=_UA), timeout=30) as resp:
                return resp.read(4096).decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001
            last = e
    raise RuntimeError(f"下载校验文件失败：{last}")


def _download(rel: dict, part: Path, deadline: float, progress) -> tuple[str, str]:
    errors: list[str] = []
    for u in _candidate_urls(rel, rel["downloadUrl"]):
        h = hashlib.sha256()
        got = 0
        try:
            req = urllib.request.Request(u, headers=_UA)
            with urllib.request.urlopen(req, timeout=30) as resp, part.open("wb") as f:
                total = int(resp.headers.get("Content-Length") or rel.get("size") or 0)
                if total > _updates._APK_MAX_BYTES:
                    raise RuntimeError("安装包超过 300MB 上限")
                last_report = 0.0
                last_byte_at = time.time()
                while True:
                    if _CANCEL.is_set():
                        raise RuntimeError("已取消")
                    if time.time() > deadline:
                        raise RuntimeError(f"下载超时（>{_max_seconds()} 秒）")
                    if time.time() - last_byte_at > _STALL_SECONDS:
                        raise RuntimeError(f"下载停滞超过 {_STALL_SECONDS} 秒")
                    chunk = resp.read(256 * 1024)
                    if not chunk:
                        break
                    last_byte_at = time.time()
                    got += len(chunk)
                    if got > _updates._APK_MAX_BYTES:
                        raise RuntimeError("安装包超过 300MB 上限")
                    h.update(chunk)
                    f.write(chunk)
                    if time.time() - last_report > 2:
                        progress(got, total, u)
                        last_report = time.time()
            if total and got != total:
                raise RuntimeError(f"下载不完整（{got}/{total} 字节）")
            progress(got, total, u)
            return h.hexdigest(), u.split("/")[2]
        except Exception as e:  # noqa: BLE001 - 换下一个地址
            errors.append(f"{u.split('/')[2]}: {str(e)[:160]}")
            part.unlink(missing_ok=True)
    raise RuntimeError("下载失败：" + "；".join(errors))


def _run(rel: dict, actor: str, lock_fd: int) -> None:
    part = _dir() / f".sync-{rel['versionCode']}.part"
    base = {"source": rel["source"], "tag": rel["tag"], "versionCode": rel["versionCode"],
            "versionName": rel["versionName"], "actor": actor, "startedAt": _now()}
    _CANCEL.clear()
    try:
        deadline = time.time() + _max_seconds()

        def progress(got: int, total: int, url: str) -> None:
            _write_status({**base, "state": "downloading", "bytes": got, "total": total,
                           "via": url.split("/")[2]})

        progress(0, rel.get("size") or 0, rel["downloadUrl"])
        digest, via = _download(rel, part, deadline, progress)
        base["via"] = via
        _write_status({**base, "state": "verifying"})
        if rel.get("sha256Url"):
            m = _SHA256_RE.search(_fetch_text(rel, rel["sha256Url"]).lower())
            if not m:
                raise RuntimeError("校验文件格式无效")
            if m.group(1) != digest:
                raise RuntimeError("SHA-256 校验不一致，已丢弃下载内容")
        data = part.read_bytes()
        changelog = rel.get("changelog") or f"同步自 {rel['source']} {rel['tag']}"
        meta = _updates.save_apk_upload(data, rel["assetName"], rel["versionCode"], rel["versionName"],
                                        changelog, f"{actor}（{rel['source']} 同步）")
        _write_status({**base, "state": "done", "bytes": len(data), "total": len(data),
                       "sha256": meta["sha256"], "finishedAt": _now()})
    except ApiError as e:
        _write_status({**base, "state": "failed", "error": str(e.detail),
                       "code": getattr(e, "code", "")})
    except Exception as e:  # noqa: BLE001 - 后台线程：记录失败，不抛出
        if str(e) == "已取消":
            _write_status({**base, "state": "cancelled", "error": "已取消", "finishedAt": _now()})
        else:
            _write_status({**base, "state": "failed", "error": str(e)[:400]})
    finally:
        part.unlink(missing_ok=True)
        try:
            fcntl.flock(lock_fd, fcntl.LOCK_UN)
        finally:
            os.close(lock_fd)


def start_sync(source: str, tag: str, actor: str, wait: bool = False) -> dict:
    """发起同步：tag 为空则取远端最新版本。已同步过的版本直接返回 already。

    wait=True 仅供测试：在当前线程跑完。
    """
    if tag and not _TAG_RE.fullmatch(tag):
        raise ApiError(400, "INVALID_TAG", "Release 标签格式无效")
    remote = list_remote(source)
    rels = remote["releases"]
    if tag:
        rels = [r for r in rels if r["tag"] == tag]
    if not rels:
        raise ApiError(404, "APP_SYNC_NOT_FOUND",
                       "远端没有符合命名（app-版本号-版本名.apk）的安装包" + (f"：{tag}" if tag else ""))
    rel = rels[0]
    if rel["synced"]:
        return {"state": "already", "versionCode": rel["versionCode"], "versionName": rel["versionName"]}
    _dir().mkdir(parents=True, exist_ok=True)
    fd = os.open(str(_dir() / ".sync.lock"), os.O_CREAT | os.O_RDWR, 0o640)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        raise ApiError(409, "APP_SYNC_BUSY", "已有同步任务在进行，请稍候")
    _write_status({"state": "queued", "source": rel["source"], "tag": rel["tag"],
                   "versionCode": rel["versionCode"], "versionName": rel["versionName"], "actor": actor})
    if wait:
        _run(rel, actor, fd)
    else:
        threading.Thread(target=_run, args=(rel, actor, fd), name="app-sync", daemon=True).start()
    return {"state": "queued", "source": rel["source"], "tag": rel["tag"],
            "versionCode": rel["versionCode"], "versionName": rel["versionName"]}


# --------------------------------------------------------------------------
# API（仅 ADMIN；同步 def 路由，FastAPI 放线程池执行）
# --------------------------------------------------------------------------

@router.get("/api/v1/admin/app/releases/remote")
def api_remote(source: str = "auto", user: sqlite3.Row = Depends(current_user)) -> dict:
    _admin(user)
    return list_remote(source)


@router.post("/api/v1/admin/app/releases/sync")
def api_sync(body: dict = Body(default={}), user: sqlite3.Row = Depends(current_user)) -> dict:
    _admin(user)
    source = str((body or {}).get("source") or "auto").strip().lower()
    tag = str((body or {}).get("tag") or "").strip()
    return start_sync(source, tag, user["username"])


@router.post("/api/v1/admin/app/releases/sync/cancel")
def api_cancel(user: sqlite3.Row = Depends(current_user)) -> dict:
    _admin(user)
    return cancel_sync(user["username"])


@router.get("/api/v1/admin/app/releases/sync/status")
def api_status(user: sqlite3.Row = Depends(current_user)) -> dict:
    _admin(user)
    return get_status()


if __name__ == "__main__":  # pragma: no cover - 运维入口
    import argparse

    ap = argparse.ArgumentParser(description="APP 更新通道：查看状态 / 触发同步（运维用）")
    ap.add_argument("--tag", default="", help="Release 标签，留空=最新")
    ap.add_argument("--source", default="auto", choices=("auto", "github", "gitee"))
    ap.add_argument("--status", action="store_true", help="只打印当前同步状态")
    ap.add_argument("--actor", default="cli")
    args = ap.parse_args()
    if args.status:
        print(json.dumps(get_status(), ensure_ascii=False, indent=2))
    else:
        print(json.dumps(start_sync(args.source, args.tag, args.actor, wait=True),
                         ensure_ascii=False, indent=2))
        print(json.dumps(get_status(), ensure_ascii=False, indent=2))
