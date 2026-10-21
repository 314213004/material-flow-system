"""库存一致性巡检（0.21.10，只读）。

对比 materials.total_quantity 与 sum(inventory.quantity)，列出漂移料号 ——
历史上有流转执行 / 盘点确认 / 异常审批 / U9 同步多条写入路径，需要能随时看清差多少。
"""
from __future__ import annotations
import importlib.util, os, sys, tempfile
from fastapi.testclient import TestClient

ROOT = tempfile.mkdtemp(prefix="mf_consistency_")
os.environ.update(MATERIAL_FLOW_DATA=f"{ROOT}/data", MATERIAL_FLOW_UPLOADS=f"{ROOT}/uploads",
                  INITIAL_ADMIN_PASSWORD="Admin@2026")
APP = os.path.join(os.path.dirname(os.path.dirname(__file__)), "app")
sys.path.insert(0, APP)
spec = importlib.util.spec_from_file_location("consistency_backend", os.path.join(APP, "main.py"))
backend = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = backend
spec.loader.exec_module(backend)
backend.init_db()

H = lambda token: {"Authorization": f"Bearer {token}"}


def login(client, name, password):
    r = client.post("/api/v1/auth/login", json={"username": name, "password": password, "deviceId": "t"})
    assert r.status_code == 200, r.text
    return r.json()["accessToken"]


def seed():
    c = backend.db()
    c.execute("PRAGMA foreign_keys=OFF")
    # init_db 会播演示数据，这里清空以保证计数可断言
    c.execute("DELETE FROM inventory")
    c.execute("DELETE FROM materials")
    now = backend.now()
    for uid, role in (("u_wh", "WAREHOUSE_ADMIN"), ("u_asm", "ASSEMBLER")):
        c.execute("INSERT OR REPLACE INTO users VALUES(?,?,?,?,?,?,?,?)",
                  (uid, uid, uid, role, backend.hash_password("Pw@2026x"), 0, 1, now))
    c.execute("INSERT OR IGNORE INTO locations(id, code, name) VALUES('loc-a','A-01','一号库位')")
    c.execute("INSERT OR IGNORE INTO locations(id, code, name) VALUES('loc-b','A-02','二号库位')")
    # 漂移：汇总 100，明细 60+30=90
    c.execute("INSERT OR REPLACE INTO materials(id, code, name, specification, unit,"
              " total_quantity, available_quantity, version) VALUES('m1','M-1','轴承','6204','个',100,100,1)")
    c.execute("INSERT OR REPLACE INTO inventory(id, material_id, location_id, quantity)"
              " VALUES('inv-a','m1','loc-a',60)")
    c.execute("INSERT OR REPLACE INTO inventory(id, material_id, location_id, quantity)"
              " VALUES('inv-b','m1','loc-b',30)")
    # 一致：汇总 40，明细 40
    c.execute("INSERT OR REPLACE INTO materials(id, code, name, specification, unit,"
              " total_quantity, available_quantity, version) VALUES('m2','M-2','齿轮','M2','个',40,40,1)")
    c.execute("INSERT OR REPLACE INTO inventory(id, material_id, location_id, quantity)"
              " VALUES('inv-c','m2','loc-a',40)")
    # 只有汇总没有明细
    c.execute("INSERT OR REPLACE INTO materials(id, code, name, specification, unit,"
              " total_quantity, available_quantity, version) VALUES('m3','M-3','垫片','M3','个',7,7,1)")
    c.commit()
    c.close()


def test_lists_only_mismatched_materials():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "u_wh", "Pw@2026x")
        r = client.get("/api/v1/inventory/consistency", headers=H(token))
        assert r.status_code == 200, r.text
        body = r.json()
        # TestClient 启动事件会再跑一次 init_db（补演示数据），所以只断言"至少包含我们造的 3 个"
        assert body["summary"]["materials"] >= 3
        assert body["summary"]["mismatched"] == 2
        codes = [i["materialCode"] for i in body["items"]]
        assert codes == ["M-1", "M-3"], "按差异绝对值倒序，一致料号不出现"
        m1 = body["items"][0]
        assert m1["totalQuantity"] == 100 and m1["inventorySum"] == 90 and m1["difference"] == 10
        assert {x["locationCode"]: x["quantity"] for x in m1["locations"]} == {"A-01": 60, "A-02": 30}
        m3 = body["items"][1]
        assert m3["difference"] == 7 and m3["locations"] == []


def test_limit_keeps_summary_counts():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "u_wh", "Pw@2026x")
        body = client.get("/api/v1/inventory/consistency?limit=1", headers=H(token)).json()
        assert body["summary"]["mismatched"] == 2 and body["summary"]["shown"] == 1


def test_requires_warehouse_or_admin():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "u_asm", "Pw@2026x")
        assert client.get("/api/v1/inventory/consistency", headers=H(token)).status_code == 403
