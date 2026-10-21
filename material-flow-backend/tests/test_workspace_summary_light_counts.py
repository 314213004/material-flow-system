"""工作台汇总轻量化（0.21.12）：计数必须与列表逐行派生完全一致。

`/workspace/summary` 原先把全部工作项构造成完整 item dict 再数状态；单订单 2 万行时
实测 460ms 以上。现在改为复用同一份状态派生逻辑、只累加计数。
本文件锁两件事：
1. 轻量计数 == 由列表 items 统计出来的计数（多状态、多角色）；
2. HTTP 汇总接口的数字 == 分页列表里实际看到的状态分布（防止两处逻辑分叉）。
"""
from __future__ import annotations

import uuid

from fastapi.testclient import TestClient

from app import main as backend

H = lambda token: {"Authorization": f"Bearer {token}"}


def login(client, username, password):
    r = client.post("/api/v1/auth/login",
                    json={"username": username, "password": password, "deviceId": "t"})
    assert r.status_code == 200, r.text
    return r.json()["accessToken"]


def seed():
    c = backend.db()
    c.execute("PRAGMA foreign_keys=OFF")
    now = backend.now()
    c.execute("DELETE FROM material_handovers")
    c.execute("DELETE FROM material_work_items")
    c.execute("DELETE FROM transfer_requests")
    c.execute("DELETE FROM order_material_requirements")
    for uid, role in (("u_admin", "ADMIN"), ("u_wh", "WAREHOUSE_ADMIN")):
        c.execute("INSERT OR REPLACE INTO users VALUES(?,?,?,?,?,?,?,?)",
                  (uid, uid, uid, role, backend.hash_password("Pw@2026x"), 0, 1, now))
    c.execute("INSERT OR REPLACE INTO production_orders(id, order_no, product_name, status,"
              " created_at, updated_at) VALUES('o1','OM-1','机台',  'IN_PROGRESS',?,?)", (now, now))
    c.execute("INSERT OR REPLACE INTO order_devices(id, order_id, device_type, device_no,"
              " sequence_no, created_at) VALUES('od1','o1','T1','DEV-1',1,?)", (now,))
    c.execute("INSERT OR REPLACE INTO order_devices(id, order_id, device_type, device_no,"
              " sequence_no, created_at) VALUES('od2','o1','T1','DEV-2',2,?)", (now,))
    for i, (code, status) in enumerate((
        ("M-1", "IN_STOCK"), ("M-2", "IN_STOCK"), ("M-3", "OUT_OF_STOCK"),
        ("M-4", "ARRIVED"), ("M-5", "IN_STOCK"), ("M-6", "IN_STOCK"),
    )):
        c.execute("INSERT OR REPLACE INTO materials(id, code, name, specification, unit,"
                  " total_quantity, available_quantity, version)"
                  " VALUES(?,?,?,'S','个',100,100,1)", (f"m{i+1}", code, f"料{i+1}"))
        c.execute("INSERT INTO order_material_requirements(id, order_id, device_id, material_id,"
                  " required_quantity, arrived_quantity, in_stock_quantity, status_code,"
                  " created_at, updated_at) VALUES(?,?,?,?,5,5,5,?,?,?)",
                  (f"r{i+1}", "o1", "od1" if i % 2 == 0 else "od2", f"m{i+1}", status, now, now))
    # 流转单：执行状态会映射成 OUTBOUND_*（r1 无交接，取流转单状态）
    for tid, status in (("tr1", "EXECUTED"), ("tr2", "APPROVED"), ("tr3", "REJECTED")):
        c.execute("INSERT INTO transfer_requests(id, client_operation_id, type, status,"
                  " payload_json, created_by, created_at) VALUES(?,?,?,?,?,?,?)",
                  (tid, f"op-{tid}", "OUTBOUND", status, '{"items": []}', "u_wh", now))
    handovers = [
        # r2：待交接 → PENDING
        ("h1", "r2", "tr1", "PENDING", None, now),
        # r3：确认且机台一致 → AT_STATION
        ("h2", "r3", "tr1", "CONFIRMED", "od1", now),
        # r4：确认但机台不一致 → PICKED_UP
        ("h3", "r4", "tr2", "CONFIRMED", "od1", now),
        # r5：驳回 → REJECTED
        ("h4", "r5", "tr3", "REJECTED", None, now),
    ]
    for hid, wid, tid, status, device, ts in handovers:
        c.execute("INSERT INTO material_handovers(id, work_item_id, transfer_request_id, quantity,"
                  " from_location, device_id, client_operation_id, status, created_by, created_at,"
                  " confirmed_at) VALUES(?,?,?,1,'A-01',?,?,?,?,?,?)",
                  (hid, wid, tid, device, f"op-{hid}", status, "u_wh", ts, ts))
    c.commit()
    c.close()


def test_light_counts_match_full_items():
    seed()
    c = backend.db()
    try:
        admin = c.execute("SELECT * FROM users WHERE id='u_admin'").fetchone()
        items = backend._workspace_items(c, admin)
        expected_status = backend._workspace_status_counts(items)
        expected_handover = backend._workspace_handover_status_counts(items)
        status_counts, handover_counts = backend._workspace_light_counts(c, admin)
    finally:
        c.close()
    assert status_counts == expected_status
    assert handover_counts == expected_handover
    # 覆盖到多种状态，避免"全 0 相等"的假通过
    assert expected_status["AT_STATION"] == 1
    assert expected_status["PICKED_UP"] == 1
    assert expected_status["PENDING"] == 1
    assert expected_status["REJECTED"] == 1
    assert expected_handover["CONFIRMED"] == 2


def test_summary_endpoint_matches_item_list_for_every_role():
    seed()
    with TestClient(backend.app) as client:
        for username in ("u_admin", "u_wh"):
            token = login(client, username, "Pw@2026x")
            summary = client.get("/api/v1/workspace/summary", headers=H(token))
            assert summary.status_code == 200, summary.text
            body = summary.json()
            # TestClient 启动会重播演示数据，所以按页取全（不能只看第一页）
            items, page, total = [], 1, None
            while True:
                listed = client.get(
                    f"/api/v1/workspace/material-items?page={page}&pageSize=50",
                    headers=H(token)).json()
                items.extend(listed["items"])
                total = listed["total"]
                if len(items) >= total or not listed["items"]:
                    break
                page += 1
            counted = {}
            for item in items:
                counted[item["statusCode"]] = counted.get(item["statusCode"], 0) + 1
            for code, n in counted.items():
                assert body["statusCounts"].get(code, 0) == n, (
                    f"{username} 视角 {code} 计数与列表不一致：{body['statusCounts']} vs {counted}")
            assert sum(body["statusCounts"].values()) == total, (
                f"{username} 视角状态计数总和应等于工作项总数")
            assert total >= 6, "至少要包含本用例造的 6 行"
