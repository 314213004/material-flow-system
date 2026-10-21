"""异常审批通过要校正库存（0.21.9）。

修复前：review_exception 只把状态改成 APPROVED，库存完全不动 —— 异常单（账面 vs 实盘差异）
审批通过后账实依旧不符，与修复前的盘点确认是同一个问题。

口径：
- APPROVE：按「实盘 - 账面」的差异校正库存；REJECT 不动；
- 该料号只有一条库位库存 → 调那条，并按库存表重算汇总（保证 汇总 = 明细合计）；
- 多条库位（异常单无库位字段）→ 调数量最大的那条并回报 locationId；
- 无差异/无物料 → adjusted=False 且不改数据；
- 幂等重放（同 clientOperationId）不得二次调整。
"""
from __future__ import annotations
import importlib.util, os, sys, tempfile, uuid
from fastapi.testclient import TestClient

ROOT = tempfile.mkdtemp(prefix="mf_exception_adjust_")
os.environ.update(MATERIAL_FLOW_DATA=f"{ROOT}/data", MATERIAL_FLOW_UPLOADS=f"{ROOT}/uploads",
                  INITIAL_ADMIN_PASSWORD="Admin@2026")
APP = os.path.join(os.path.dirname(os.path.dirname(__file__)), "app")
sys.path.insert(0, APP)
spec = importlib.util.spec_from_file_location("exception_adjust_backend", os.path.join(APP, "main.py"))
backend = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = backend
spec.loader.exec_module(backend)
backend.init_db()

H = lambda token: {"Authorization": f"Bearer {token}"}


def login(client, name, password):
    r = client.post("/api/v1/auth/login", json={"username": name, "password": password, "deviceId": "t"})
    assert r.status_code == 200, r.text
    return r.json()["accessToken"]


def seed(material_qty=100, locations=(("loc-a", "A-01", 100),)):
    c = backend.db()
    c.execute("PRAGMA foreign_keys=OFF")
    now = backend.now()
    c.execute("INSERT OR REPLACE INTO users VALUES(?,?,?,?,?,?,?,?)",
              ("u_wh", "wh-admin", "仓库管理员", "WAREHOUSE_ADMIN",
               backend.hash_password("Wh@2026x"), 0, 1, now))
    c.execute("INSERT OR IGNORE INTO production_orders(id, order_no, product_name, status, created_at, updated_at)"
              " VALUES('ord-1','OM-1','产品','IN_PROGRESS',?,?)", (now, now))
    c.execute("INSERT OR IGNORE INTO devices(id, device_no, device_name, workshop, created_by, created_at, updated_at)"
              " VALUES('dev-1','DEV-1','机台','一车间','u_wh',?,?)", (now, now))
    c.execute("INSERT OR REPLACE INTO materials(id, code, name, specification, unit,"
              " total_quantity, available_quantity, version) VALUES('m1','M-1','轴承','6204','个',?,?,1)",
              (material_qty, material_qty))
    for loc_id, code, qty in locations:
        c.execute("INSERT OR REPLACE INTO locations(id, code, name) VALUES(?,?,?)", (loc_id, code, code))
        c.execute("INSERT OR REPLACE INTO inventory(id, material_id, location_id, quantity)"
                  " VALUES(?,?,?,?)", (f"inv-{loc_id}", "m1", loc_id, qty))
    c.commit()
    c.close()


def create_exception(client, token, book, actual):
    r = client.post("/api/v1/exceptions", json={
        "orderNo": "OM-1", "deviceId": "dev-1", "materialId": "m1", "type": "SHORTAGE",
        "bookQuantity": book, "actualQuantity": actual, "description": "盘点差异",
    }, headers=H(token))
    assert r.status_code in (200, 201), r.text
    return r.json()["exceptionId"] if "exceptionId" in r.json() else r.json()["id"]


def review(client, token, eid, decision="APPROVE", op=None):
    return client.post(f"/api/v1/exceptions/{eid}/review",
                       json={"decision": decision, "clientOperationId": op or str(uuid.uuid4())},
                       headers=H(token))


def test_approve_adjusts_single_location_inventory():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "wh-admin", "Wh@2026x")
        eid = create_exception(client, token, 10, 8)   # 差 -2
        r = review(client, token, eid)
        assert r.status_code == 200, r.text
        adj = r.json()["adjusted"]
        assert adj["adjusted"] is True and adj["difference"] == -2
        assert adj["before"] == 100 and adj["after"] == 98
        c = backend.db()
        qty = c.execute("SELECT quantity FROM inventory WHERE material_id='m1'").fetchone()[0]
        total = c.execute("SELECT total_quantity FROM materials WHERE id='m1'").fetchone()[0]
        c.close()
        assert qty == 98 and total == 98, "单库位时明细与汇总都要校正"


def test_approve_adjusts_largest_location_when_multiple():
    seed(material_qty=100, locations=(("loc-a", "A-01", 60), ("loc-b", "A-02", 40)))
    with TestClient(backend.app) as client:
        token = login(client, "wh-admin", "Wh@2026x")
        eid = create_exception(client, token, 10, 5)   # 差 -5
        r = review(client, token, eid)
        adj = r.json()["adjusted"]
        assert adj["adjusted"] is True and adj["locationAdjusted"] is True
        assert adj["locationId"] == "loc-a", "多库位时调整数量最大的那条"
        c = backend.db()
        rows = dict(c.execute("SELECT location_id, quantity FROM inventory WHERE material_id='m1'").fetchall())
        total = c.execute("SELECT total_quantity FROM materials WHERE id='m1'").fetchone()[0]
        c.close()
        assert rows == {"loc-a": 55, "loc-b": 40}
        assert total == 95 == sum(rows.values()), "汇总必须等于明细合计"


def test_reject_does_not_touch_inventory():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "wh-admin", "Wh@2026x")
        eid = create_exception(client, token, 10, 3)
        r = review(client, token, eid, decision="REJECT")
        assert r.status_code == 200, r.text
        c = backend.db()
        total = c.execute("SELECT total_quantity FROM materials WHERE id='m1'").fetchone()[0]
        c.close()
        assert total == 100


def test_zero_difference_reports_no_adjustment():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "wh-admin", "Wh@2026x")
        eid = create_exception(client, token, 10, 10)
        adj = review(client, token, eid).json()["adjusted"]
        assert adj["adjusted"] is False and adj["reason"] == "账实无差异"


def test_idempotent_replay_does_not_double_adjust():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "wh-admin", "Wh@2026x")
        eid = create_exception(client, token, 10, 8)
        op = str(uuid.uuid4())
        first = review(client, token, eid, op=op)
        assert first.status_code == 200, first.text
        second = review(client, token, eid, op=op)
        assert second.status_code == 200, second.text
        assert second.json().get("idempotent") is True
        c = backend.db()
        total = c.execute("SELECT total_quantity FROM materials WHERE id='m1'").fetchone()[0]
        c.close()
        assert total == 98, "幂等重放不能把差异再调一次"
