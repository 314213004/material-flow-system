"""盘点确认要真正调整库存（0.21.8）。

修复前：confirm 只把状态改成 CONFIRMED，库存与物料汇总都不动 —— 盘出差异确认后账实依旧不符；
创建工作台还把 library 编码写进了 location_id（列存 id），导致列表 JOIN 取不到库位。
本文件锁定：
- 带库位盘点：账面取该库位数量，确认后该库位库存 = 实盘数，物料总量按库存表重算；
- 物料级盘点（无库位）：账面取物料汇总，确认后物料总量 = 实盘数；
- 库位编码不存在 → 404，不落脏数据。
"""
from __future__ import annotations
import importlib.util, os, sys, tempfile
from fastapi.testclient import TestClient

ROOT = tempfile.mkdtemp(prefix="mf_stocktake_adjust_")
os.environ.update(MATERIAL_FLOW_DATA=f"{ROOT}/data", MATERIAL_FLOW_UPLOADS=f"{ROOT}/uploads",
                  INITIAL_ADMIN_PASSWORD="Admin@2026")
APP = os.path.join(os.path.dirname(os.path.dirname(__file__)), "app")
sys.path.insert(0, APP)
spec = importlib.util.spec_from_file_location("stocktake_adjust_backend", os.path.join(APP, "main.py"))
backend = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = backend
spec.loader.exec_module(backend)
backend.init_db()

H = lambda token: {"Authorization": f"Bearer {token}"}


def login(client, name, password="Wh@2026x"):
    r = client.post("/api/v1/auth/login", json={"username": name, "password": password, "deviceId": "t"})
    assert r.status_code == 200, r.text
    return r.json()["accessToken"]


def seed():
    c = backend.db()
    c.execute("PRAGMA foreign_keys=OFF")
    now = backend.now()
    c.execute("INSERT OR REPLACE INTO users VALUES(?,?,?,?,?,?,?,?)",
              ("u_wh", "wh-admin", "仓库管理员", "WAREHOUSE_ADMIN",
               backend.hash_password("Wh@2026x"), 0, 1, now))
    c.execute("INSERT OR IGNORE INTO materials(id, code, name, specification, unit,"
              " total_quantity, available_quantity, version)"
              " VALUES('m1','M-1','轴承','6204','个',100,100,1)")
    c.execute("INSERT OR IGNORE INTO locations(id, code, name) VALUES('loc-a','A-01','一号库位')")
    c.execute("INSERT OR IGNORE INTO locations(id, code, name) VALUES('loc-b','A-02','二号库位')")
    c.execute("INSERT OR IGNORE INTO inventory(id, material_id, location_id, quantity)"
              " VALUES('inv-a','m1','loc-a',60)")
    c.execute("INSERT OR IGNORE INTO inventory(id, material_id, location_id, quantity)"
              " VALUES('inv-b','m1','loc-b',40)")
    c.commit()
    c.close()


def create(client, token, code, location, actual):
    body = {"materialCode": code, "actualQuantity": actual, "clientOperationId": None}
    if location is not None:
        body["locationCode"] = location
    return client.post("/api/v1/stocktakes", json=body, headers=H(token))


def test_location_stocktake_adjusts_inventory_on_confirm():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "wh-admin")
        r = create(client, token, "M-1", "A-01", 55)
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["difference"] == -5, body  # 账面 60 → 实盘 55
        sid = body["stocktakeId"]

        # 落库的是库位 id，不是编码
        c = backend.db()
        row = c.execute("SELECT location_id, book_quantity FROM stocktakes WHERE id=?", (sid,)).fetchone()
        assert row["location_id"] == "loc-a" and row["book_quantity"] == 60
        c.close()

        # 列表能带出库位编码
        items = client.get("/api/v1/stocktakes", headers=H(token)).json()["items"]
        assert items[0]["location_code"] == "A-01"

        conf = client.post(f"/api/v1/stocktakes/{sid}/confirm",
                           json={"decision": "CONFIRM", "clientOperationId": None}, headers=H(token))
        assert conf.status_code == 200, conf.text
        assert conf.json()["adjusted"]["difference"] == -5

        c = backend.db()
        qty = c.execute("SELECT quantity FROM inventory WHERE material_id='m1' AND location_id='loc-a'").fetchone()[0]
        total = c.execute("SELECT total_quantity, available_quantity FROM materials WHERE id='m1'").fetchone()[:]
        c.close()
        assert qty == 55, "确认后库位库存必须等于实盘数"
        assert total[0] == 95, "物料总量应按库存表重算（55+40）"
        assert total[1] == 95, "可用量同步扣减"


def test_material_level_stocktake_sets_total_on_confirm():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "wh-admin")
        r = create(client, token, "M-1", None, 90)
        assert r.status_code == 200, r.text
        sid = r.json()["stocktakeId"]
        assert r.json()["difference"] == -10  # 账面取物料汇总 100

        conf = client.post(f"/api/v1/stocktakes/{sid}/confirm",
                           json={"decision": "CONFIRM", "clientOperationId": None}, headers=H(token))
        assert conf.status_code == 200, conf.text
        c = backend.db()
        total = c.execute("SELECT total_quantity FROM materials WHERE id='m1'").fetchone()[0]
        c.close()
        assert total == 90


def test_unknown_location_is_rejected():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "wh-admin")
        r = create(client, token, "M-1", "NOPE-9", 10)
        assert r.status_code == 404, r.text
        c = backend.db()
        assert c.execute("SELECT COUNT(*) FROM stocktakes").fetchone()[0] == 0
        c.close()
