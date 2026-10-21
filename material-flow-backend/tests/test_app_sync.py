"""从 GitHub / Gitee Release 同步 APK 到 APP 更新通道。"""
from __future__ import annotations

import hashlib
import io
import json
import re

import pytest
from fastapi.testclient import TestClient

from app import main as backend  # 先导入 main（它注册 app_sync 路由）
from app import app_sync, updates
from tests.test_admin_web_nav import seed, web_login

APK = b"PK\x03\x04" + b"x" * 300_000
SHA = hashlib.sha256(APK).hexdigest()


class _Resp(io.BytesIO):
    def __init__(self, data: bytes):
        super().__init__(data)
        self.headers = {"Content-Length": str(len(data))}

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def _gh_release(code=46, name="0.5.39", with_sha=True, sha=SHA):
    base = f"https://github.com/o/r/releases/download/v{name}/"
    assets = [{"name": f"app-{code}-{name}.apk", "size": len(APK),
               "browser_download_url": base + f"app-{code}-{name}.apk"}]
    if with_sha:
        assets.append({"name": f"app-{code}-{name}.apk.sha256", "size": 90,
                       "browser_download_url": base + f"app-{code}-{name}.apk.sha256",
                       "_body": f"{sha}  app-{code}-{name}.apk\n"})
    return {"tag_name": f"v{name}", "body": "批量勾选操作", "published_at": "2026-10-16T01:00:00Z",
            "assets": assets}


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("APP_RELEASES_DIR", str(tmp_path / "rel"))
    monkeypatch.setenv("APP_SYNC_GITHUB_PROXY", "https://proxy.test/")
    state = {"github": [_gh_release(), {"tag_name": "v0.5.6", "assets": [{"name": "logistics-debug.apk",
                                                                           "browser_download_url": "https://x/y"}]}],
             "gitee": [], "fail_direct": False, "calls": []}

    def fake_urlopen(req, timeout=0):
        url = req.full_url if hasattr(req, "full_url") else req
        state["calls"].append(url)
        if url.startswith(updates.GITHUB_API + "/releases"):
            return _Resp(json.dumps(state["github"], default=str).encode())
        if url.startswith(updates.GITEE_API + "/releases"):
            if state["gitee"] is None:
                raise OSError("gitee down")
            return _Resp(json.dumps(state["gitee"]).encode())
        if state["fail_direct"] and url.startswith("https://github.com/"):
            raise OSError("timed out")
        target = url.removeprefix("https://proxy.test/")
        for rel in state["github"] + state["gitee"]:
            for a in rel["assets"]:
                if a["browser_download_url"] == target:
                    return _Resp(a["_body"].encode() if "_body" in a else APK)
        raise OSError("404 " + url)

    monkeypatch.setattr(app_sync.urllib.request, "urlopen", fake_urlopen)
    return state


def test_list_remote_filters_named_apks(env):
    r = app_sync.list_remote("github")
    assert [x["versionCode"] for x in r["releases"]] == [46]
    assert r["releases"][0]["sha256Url"].endswith(".sha256") and r["releases"][0]["synced"] is False


def test_sync_downloads_verifies_and_publishes(env):
    res = app_sync.start_sync("github", "", "owlco", wait=True)
    assert res["state"] == "queued"
    st = app_sync.get_status()
    assert st["state"] == "done", st
    latest = updates.get_latest_release()
    assert latest["versionCode"] == 46 and latest["sha256"] == SHA
    assert latest["changelog"] == "批量勾选操作" and "github" in latest["uploadedBy"]
    # 再同步同一版本：直接返回 already，不重复下载
    n = len(env["calls"])
    assert app_sync.start_sync("github", "", "owlco", wait=True)["state"] == "already"
    assert not any("download" in u for u in env["calls"][n:])


def test_sync_falls_back_to_proxy_when_github_direct_fails(env):
    env["fail_direct"] = True
    app_sync.start_sync("github", "v0.5.39", "owlco", wait=True)
    st = app_sync.get_status()
    assert st["state"] == "done" and st.get("via") == "proxy.test"


def test_sync_rejects_sha_mismatch(env):
    env["github"] = [_gh_release(sha="0" * 64)]
    app_sync.start_sync("github", "", "owlco", wait=True)
    st = app_sync.get_status()
    assert st["state"] == "failed" and "SHA-256" in st["error"]
    assert updates.list_releases() == []
    assert not list((updates._releases_dir()).glob("*.part"))


def test_auto_prefers_gitee_and_tolerates_one_source_down(env):
    gitee = _gh_release(with_sha=False)
    for a in gitee["assets"]:
        a["browser_download_url"] = a["browser_download_url"].replace("https://github.com/", "https://gitee.com/")
    env["gitee"] = [gitee]
    r = app_sync.list_remote("auto")
    assert r["releases"][0]["source"] == "gitee"
    env["gitee"] = None
    r = app_sync.list_remote("auto")
    assert r["releases"][0]["source"] == "github" and r["errors"]


def test_busy_lock_and_invalid_input(env):
    import fcntl, os
    updates._releases_dir().mkdir(parents=True, exist_ok=True)
    fd = os.open(str(updates._releases_dir() / ".sync.lock"), os.O_CREAT | os.O_RDWR)
    fcntl.flock(fd, fcntl.LOCK_EX)
    try:
        with pytest.raises(backend.ApiError) as e:
            app_sync.start_sync("github", "", "owlco", wait=True)
        assert e.value.code == "APP_SYNC_BUSY"
    finally:
        os.close(fd)
    with pytest.raises(backend.ApiError):
        app_sync.start_sync("svn", "", "owlco")
    with pytest.raises(backend.ApiError):
        app_sync.start_sync("github", "v1;rm", "owlco")
    with pytest.raises(backend.ApiError) as e:
        app_sync.start_sync("github", "v9.9.9", "owlco")
    assert e.value.code == "APP_SYNC_NOT_FOUND"


def test_admin_page_check_and_sync(env, monkeypatch):
    seed()
    real_start = app_sync.start_sync
    monkeypatch.setattr(app_sync, "start_sync", lambda s, t, a: real_start(s, t, a, wait=True))
    with TestClient(backend.app) as client:
        assert web_login(client, "owlco", "Admin@2026").status_code == 303
        page = client.get("/admin/releases").text
        assert "从 GitHub / Gitee 同步" in page
        csrf = re.search(r'name="csrf_token" value="([^"]+)"', page).group(1)
        chk = client.post("/admin/releases/sync/check", data={"csrf_token": csrf, "source": "github"})
        assert chk.status_code == 200 and "v0.5.39" in chk.text and 'name="tag" value="v0.5.39"' in chk.text
        r = client.post("/admin/releases/sync", data={"csrf_token": csrf, "source": "github", "tag": "v0.5.39"},
                        follow_redirects=False)
        assert r.status_code == 303 and "sync_started" in r.headers["location"]
        page = client.get("/admin/releases").text
        assert "已同步" in page and "0.5.39" in page
        assert client.get("/api/v1/app/updates/latest").json()["versionCode"] == 46
        bad = client.post("/admin/releases/sync", data={"csrf_token": "x", "source": "github"})
        assert bad.status_code == 403


def test_recover_interrupted_marks_failed_and_drops_part(env, tmp_path):
    """服务重启把 daemon 线程杀掉后，状态不能永远停在"下载中 45%"。"""
    rel = tmp_path / "rel"
    rel.mkdir(parents=True, exist_ok=True)
    (rel / ".sync-54.part").write_bytes(b"x" * 1024)
    (rel / ".sync-status.json").write_text(json.dumps({
        "source": "github", "tag": "v0.5.47", "versionCode": 54, "versionName": "0.5.47",
        "actor": "owlco", "state": "downloading", "bytes": 11010048, "total": 24389193,
        "at": "2026-10-21T15:40:29+00:00"}, ensure_ascii=False), encoding="utf-8")
    out = app_sync.recover_interrupted()
    assert out["state"] == "failed"
    st = app_sync.get_status()
    assert st["state"] == "failed"
    assert "服务重启导致同步中断" in st["error"]
    assert "45%" in st["error"], st["error"]
    assert not list(rel.glob(".sync-*.part")), "半截 part 必须删掉"


def test_recover_interrupted_noop_when_idle(env):
    assert app_sync.recover_interrupted() is None


def test_cancel_marks_cancelled_when_no_thread(env, tmp_path):
    rel = tmp_path / "rel"
    rel.mkdir(parents=True, exist_ok=True)
    (rel / ".sync-54.part").write_bytes(b"x" * 10)
    (rel / ".sync-status.json").write_text(json.dumps({
        "source": "gitee", "tag": "v0.5.47", "versionCode": 54, "versionName": "0.5.47",
        "state": "downloading", "bytes": 10, "total": 100}), encoding="utf-8")
    out = app_sync.cancel_sync("tester")
    assert out["cancelled"] is True
    assert app_sync.get_status()["state"] == "cancelled"
    assert not list(rel.glob(".sync-*.part"))


def test_sync_cancel_api(env):
    from tests.test_admin_web_nav import seed as seed_users
    seed_users()
    with TestClient(backend.app) as client:
        r = client.post("/api/v1/auth/login", json={"username": "owlco", "password": "Admin@2026",
                                                   "deviceId": "t"})
        assert r.status_code == 200, r.text
        token = r.json()["accessToken"]
        h = {"Authorization": f"Bearer {token}"}
        idle = client.post("/api/v1/admin/app/releases/sync/cancel", headers=h).json()
        assert idle["cancelled"] is False, "空闲时取消不应报错"
    with TestClient(backend.app) as client:
        r = client.post("/api/v1/auth/login", json={"username": "wh-admin", "password": "Wh@2026x",
                                                   "deviceId": "t"})
        if r.status_code == 200:
            h2 = {"Authorization": f"Bearer {r.json()['accessToken']}"}
            assert client.post("/api/v1/admin/app/releases/sync/cancel", headers=h2).status_code == 403
