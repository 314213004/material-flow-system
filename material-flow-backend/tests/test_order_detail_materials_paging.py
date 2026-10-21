"""订单详情物料分页 + 汇总口径（0.21.13）。

大订单（单订单上万条需求）原来把全部物料行塞进响应，JSON 有几 MB、构造要上百毫秒。
现在支持 `materialsPage`/`materialsPageSize`（0=全量，兼容旧客户端），
而 `materialSummary` 不论分页与否都按全量口径由 SQL 聚合。
"""
from __future__ import annotations

from fastapi.testclient import TestClient

from app import main as backend

H = lambda token: {"Authorization": f"Bearer {token}"}


def login(client, username, password):
    r = client.post("/api/v1/auth/login",
                    json={"username": username, "password": password, "deviceId": "t"})
    assert r.status_code == 200, r.text
    return r.json()["accessToken"]


def seed(rows: int = 6):
    c = backend.db()
    c.execute("PRAGMA foreign_keys=OFF")
    now = backend.now()
    c.execute("DELETE FROM order_material_requirements WHERE order_id='o-pg'")
    c.execute("INSERT OR REPLACE INTO users VALUES(?,?,?,?,?,?,?,?)",
              ("u_admin2", "adm2", "管理员", "ADMIN", backend.hash_password("Pw@2026x"), 0, 1, now))
    c.execute("INSERT OR REPLACE INTO production_orders(id, order_no, product_name, status,"
              " created_at, updated_at) VALUES('o-pg','OM-PG','分页样机','IN_PROGRESS',?,?)", (now, now))
    c.execute("INSERT OR REPLACE INTO order_devices(id, order_id, device_type, device_no,"
              " sequence_no, created_at) VALUES('od-pg','o-pg','T1','DEV-PG',1,?)", (now,))
    for i in range(rows):
        mid, code = f"m-pg{i}", f"MPG-{i:03d}"
        c.execute("INSERT OR REPLACE INTO materials(id, code, name, specification, unit,"
                  " total_quantity, available_quantity, version)"
                  " VALUES(?,?,?,'S','个',100,100,1)", (mid, code, f"分页料{i}"))
        # 数量故意错开：前两行同料号累加，验证汇总按料号聚合
        c.execute("INSERT INTO order_material_requirements(id, order_id, device_id, material_id,"
                  " required_quantity, arrived_quantity, in_stock_quantity, status_code,"
                  " created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                  (f"r-pg{i}", "o-pg", "od-pg", mid, 10 + i, 5, 5,
                   "IN_STOCK" if i % 2 == 0 else "OUT_OF_STOCK", now, now))
    c.commit()
    c.close()


def test_materials_paginate_without_changing_summary():
    seed(rows=6)
    with TestClient(backend.app) as client:
        token = login(client, "adm2", "Pw@2026x")
        full = client.get("/api/v1/orders/OM-PG/detail", headers=H(token)).json()
        assert len(full["materials"]) == 6
        assert full["materialsTotal"] == 6
        assert full["materialsPageSize"] == 0 and full["materialsHasMore"] is False

        page1 = client.get("/api/v1/orders/OM-PG/detail?materialsPage=1&materialsPageSize=4",
                           headers=H(token)).json()
        assert len(page1["materials"]) == 4
        assert page1["materialsTotal"] == 6 and page1["materialsHasMore"] is True
        page2 = client.get("/api/v1/orders/OM-PG/detail?materialsPage=2&materialsPageSize=4",
                           headers=H(token)).json()
        assert len(page2["materials"]) == 2
        assert page2["materialsHasMore"] is False
        ids = [m["requirementId"] for m in page1["materials"] + page2["materials"]]
        assert len(set(ids)) == 6, "两页不能重复、也不能漏"

        # 汇总必须与全量口径完全一致（分页不影响汇总）
        assert page1["materialSummary"] == full["materialSummary"]
        assert page2["materialSummary"] == full["materialSummary"]
        assert full["materialSummary"]["totalMaterialTypes"] == 6
        assert full["materialSummary"]["totalRequiredQuantity"] == sum(range(10, 16))


def test_material_summary_grouped_matches_python_version():
    """SQL 聚合汇总与逐行 Python 汇总必须等价（防止两条实现漂移）。"""
    seed(rows=5)
    with TestClient(backend.app):
        c = backend.db()
        try:
            admin = c.execute("SELECT * FROM users WHERE id='u_admin2'").fetchone()
            rows = backend._workspace_requirements(c, admin, "OM-PG")
            by_python = backend._material_summary(rows)
            by_sql = backend._scoped_material_summary(c, admin, "OM-PG")
        finally:
            c.close()
    assert by_sql == by_python


def test_materials_page_out_of_range_returns_empty():
    seed(rows=3)
    with TestClient(backend.app) as client:
        token = login(client, "adm2", "Pw@2026x")
        body = client.get("/api/v1/orders/OM-PG/detail?materialsPage=9&materialsPageSize=10",
                          headers=H(token)).json()
        assert body["materials"] == [] and body["materialsTotal"] == 3
        assert body["materialsHasMore"] is False
