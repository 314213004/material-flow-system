"""审计日志保留策略（0.21.15）。

审计表只增不删，跑几年会变成大表。这里提供：预览（只读）→ 按保留期清理，
清理动作本身也写一条审计，保证"谁在什么时候删了多少"可追溯。
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient

from app import main as backend

H = lambda token: {"Authorization": f"Bearer {token}"}


def login(client, username, password):
    r = client.post("/api/v1/auth/login",
                    json={"username": username, "password": password, "deviceId": "t"})
    assert r.status_code == 200, r.text
    return r.json()["accessToken"]


def iso_days_ago(days: int) -> str:
    return (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()


def seed():
    c = backend.db()
    for uid, role in (("u_aud", "ADMIN"), ("u_wh2", "WAREHOUSE_ADMIN")):
        c.execute("INSERT OR REPLACE INTO users VALUES(?,?,?,?,?,?,?,?)",
                  (uid, uid, uid, role, backend.hash_password("Pw@2026x"), 0, 1, backend.now()))
    # 两条老记录（400 天前）+ 两条新记录（1 天前）
    for i, ts in enumerate((iso_days_ago(400), iso_days_ago(401), iso_days_ago(1), iso_days_ago(1))):
        c.execute("INSERT INTO audit_logs(operator_id, role, action, resource_type, resource_id,"
                  " request_id, occurred_at, result) VALUES('u_aud','ADMIN','CREATE','ORDER',?,?,?, 'SUCCESS')",
                  (f"old-{i}", f"rq-{i}", ts))
        c.execute("INSERT INTO audit_events(event_type, entity_type, entity_id, actor_user_id,"
                  " actor_role, request_id, client_operation_id, before_json, after_json,"
                  " server_time, result) VALUES('CREATE','ORDER',?,'u_aud','ADMIN',?,?, '{}','{}',?,'SUCCESS')",
                  (f"ev-{i}", f"rq-{i}", f"op-{uuid.uuid4().hex}", ts))
    c.commit()
    c.close()


def test_preview_is_read_only():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "u_aud", "Pw@2026x")
        r = client.get("/api/v1/admin/audit/retention?days=365", headers=H(token))
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["auditLogs"] == 2 and body["auditEvents"] == 2, body
        assert body["total"] == 4
        # 预览不能删数据
        c = backend.db()
        assert c.execute("SELECT COUNT(*) FROM audit_logs").fetchone()[0] >= 4
        c.close()


def test_purge_deletes_only_old_rows_and_leaves_trace():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "u_aud", "Pw@2026x")
        r = client.post("/api/v1/admin/audit/purge", json={"days": 365, "dryRun": False},
                        headers=H(token))
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["deleted"]["total"] == 4, body
        c = backend.db()
        cutoff = body["cutoff"]
        assert c.execute("SELECT COUNT(*) FROM audit_logs WHERE occurred_at < ?",
                         (cutoff,)).fetchone()[0] == 0
        assert c.execute("SELECT COUNT(*) FROM audit_events WHERE server_time < ?",
                         (cutoff,)).fetchone()[0] == 0
        # 清理动作本身留痕（不会被本次清掉）
        trace = c.execute("SELECT COUNT(*) FROM audit_logs WHERE action='PURGE'").fetchone()[0]
        c.close()
        assert trace == 1


def test_dry_run_does_not_delete():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "u_aud", "Pw@2026x")
        r = client.post("/api/v1/admin/audit/purge", json={"days": 365, "dryRun": True},
                        headers=H(token))
        assert r.status_code == 200
        assert r.json()["dryRun"] is True and r.json()["deleted"] == 0
        c = backend.db()
        assert c.execute("SELECT COUNT(*) FROM audit_logs WHERE occurred_at < ?",
                         (r.json()["cutoff"],)).fetchone()[0] == 2
        c.close()


def test_requires_admin_and_sane_days():
    seed()
    with TestClient(backend.app) as client:
        wh = login(client, "u_wh2", "Pw@2026x")
        assert client.get("/api/v1/admin/audit/retention", headers=H(wh)).status_code == 403
        assert client.post("/api/v1/admin/audit/purge", json={"days": 365, "dryRun": False},
                           headers=H(wh)).status_code == 403
        admin = login(client, "u_aud", "Pw@2026x")
        assert client.get("/api/v1/admin/audit/retention?days=7", headers=H(admin)).status_code == 422
