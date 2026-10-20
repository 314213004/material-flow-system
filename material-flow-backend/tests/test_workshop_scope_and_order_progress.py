"""车间主管统计的授权收敛 + 订单维度进度（0.21.6）。

回归目标：
- 主管设置了车间（user_workshops）后，/workshop/summary、/workshop/machine-progress、
  /workshop/order-progress 只统计本车间机台；未设置=全厂（兼容升级前的数据）。
- 订单进度按订单聚合（机台阶段 0..3，完工算满），不再拿全厂值冒充；
  装配工只看到本人可见任务范围内的进度。
- 统计的机台解析要兼容两套 device_id 命名空间（devices.id / order_devices.id）。
"""
from __future__ import annotations
import importlib.util, os, sys, tempfile
from fastapi.testclient import TestClient

ROOT = tempfile.mkdtemp(prefix="mf_workshop_scope_")
os.environ.update(MATERIAL_FLOW_DATA=f"{ROOT}/data", MATERIAL_FLOW_UPLOADS=f"{ROOT}/uploads",
                  INITIAL_ADMIN_PASSWORD="Admin@2026")
APP = os.path.join(os.path.dirname(os.path.dirname(__file__)), "app")
sys.path.insert(0, APP)
spec = importlib.util.spec_from_file_location("workshop_scope_backend", os.path.join(APP, "main.py"))
backend = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = backend
spec.loader.exec_module(backend)
backend.init_db()

H = lambda token: {"Authorization": f"Bearer {token}"}


def login(client, name, password="Worker@2026"):
    r = client.post("/api/v1/auth/login", json={"username": name, "password": password, "deviceId": name})
    assert r.status_code == 200, r.text
    return r.json()["accessToken"]


def seed():
    c = backend.db()
    now = backend.now()
    pwd = backend.hash_password("Worker@2026")
    for uid, role in (("sup-a", "WORKSHOP_SUPERVISOR"), ("sup-b", "WORKSHOP_SUPERVISOR"),
                      ("sup-all", "WORKSHOP_SUPERVISOR"), ("asm-1", "ASSEMBLER")):
        c.execute("INSERT OR IGNORE INTO users VALUES(?,?,?,?,?,?,?,?)", (uid, uid, uid, role, pwd, 0, 1, now))
    # 车间归属：sup-a=一车间，sup-b=二车间，sup-all 不设（全厂）
    c.execute("INSERT OR REPLACE INTO user_workshops(user_id, workshop, updated_at) VALUES('sup-a','一车间',?)", (now,))
    c.execute("INSERT OR REPLACE INTO user_workshops(user_id, workshop, updated_at) VALUES('sup-b','二车间',?)", (now,))
    for did, dno, ws in (("dev-a1", "DA-1", "一车间"), ("dev-a2", "DA-2", "一车间"), ("dev-b1", "DB-1", "二车间")):
        c.execute("INSERT OR IGNORE INTO devices VALUES(?,?,?,?,?,?,?,?,?,?)",
                  (did, dno, dno, ws, None, None, "ACTIVE", "u_admin", now, now))
    for oid, ono in (("ord-1", "OM-1"), ("ord-2", "OM-2")):
        c.execute("INSERT OR IGNORE INTO production_orders VALUES(?,?,?,?,?,?)",
                  (oid, ono, ono, "IN_PROGRESS", now, now))
    # t-a1 进行到阶段 2；t-a2 完工；t-b1 待料。
    # 注意 t-b1 的 device_id 故意给 order_devices 风格的 id（报价导入形态），靠机台号回退解析。
    tasks = (("t-a1", "OM-1", "dev-a1", "DA-1", "IN_PROGRESS", 2),
             ("t-a2", "OM-1", "dev-a2", "DA-2", "COMPLETED", 3),
             ("t-b1", "OM-2", "od-b1", "DB-1", "WAITING_MATERIAL", 0))
    for tid, ono, did, dno, st, stage in tasks:
        c.execute("INSERT OR IGNORE INTO assembly_tasks VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                  (tid, ono, did, dno, None, st, stage, 1, None, None, now, now))
    c.execute("INSERT OR IGNORE INTO labor_records(id, task_id, worker_user_id, type, status, started_at,"
              " ended_at, duration_minutes, client_operation_id, created_at)"
              " VALUES('lab-a1','t-a1','asm-1','ASSEMBLY','COMPLETED',?,?,60,'lab-a1-op',?)", (now, now, now))
    c.execute("INSERT OR IGNORE INTO labor_records(id, task_id, worker_user_id, type, status, started_at,"
              " ended_at, duration_minutes, client_operation_id, created_at)"
              " VALUES('lab-b1','t-b1','asm-1','ASSEMBLY','COMPLETED',?,?,30,'lab-b1-op',?)", (now, now, now))
    c.commit()
    c.close()


def test_supervisor_scoped_to_own_workshop():
    seed()
    with TestClient(backend.app) as client:
        a = login(client, "sup-a")
        body = client.get("/api/v1/workshop/summary", headers=H(a)).json()
        # 一车间：2 台机台，完工 1 台，阶段合计 2+3=5 → 5/(2*3)=83%
        assert body["totalTasks"] == 2 and body["completedTasks"] == 1
        assert body["overallProgressPercent"] == 50
        assert body["scope"] == "WORKSHOP" and body["workshop"] == "一车间"
        assert body["assemblyLaborMinutes"] == 60, body

        b = login(client, "sup-b")
        body_b = client.get("/api/v1/workshop/summary", headers=H(b)).json()
        assert body_b["totalTasks"] == 1 and body_b["completedTasks"] == 0
        assert body_b["overallProgressPercent"] == 0
        assert body_b["assemblyLaborMinutes"] == 30


def test_supervisor_without_workshop_defaults_to_all():
    seed()
    with TestClient(backend.app) as client:
        token = login(client, "sup-all")
        body = client.get("/api/v1/workshop/summary", headers=H(token)).json()
        assert body["totalTasks"] == 3 and body["scope"] == "ALL" and body["workshop"] is None
        assert body["overallProgressPercent"] == round(1 * 100 / 3)

        admin = client.post("/api/v1/auth/login",
                            json={"username": "owlco", "password": "Admin@2026", "deviceId": "x"}).json()["accessToken"]
        assert client.get("/api/v1/workshop/summary", headers=H(admin)).json()["totalTasks"] == 3


def test_machine_progress_scoped_by_workshop():
    seed()
    with TestClient(backend.app) as client:
        a = login(client, "sup-a")
        items = client.get("/api/v1/workshop/machine-progress", headers=H(a)).json()["items"]
        assert sorted(x["deviceNo"] for x in items) == ["DA-1", "DA-2"]
        b = login(client, "sup-b")
        # 报价导入形态（device_id 指向 order_devices）也要能按机台号解析到二车间
        items_b = client.get("/api/v1/workshop/machine-progress", headers=H(b)).json()["items"]
        assert [x["deviceNo"] for x in items_b] == ["DB-1"]


def test_order_progress_endpoint_is_per_order():
    seed()
    with TestClient(backend.app) as client:
        a = login(client, "sup-a")
        items = client.get("/api/v1/workshop/order-progress", headers=H(a)).json()["items"]
        assert [x["orderNo"] for x in items] == ["OM-1"]
        assert items[0]["totalTasks"] == 2 and items[0]["completedTasks"] == 1
        assert items[0]["orderProgressPercent"] == 83
        assert items[0]["inProgressTasks"] == 1
        b = login(client, "sup-b")
        items_b = client.get("/api/v1/workshop/order-progress", headers=H(b)).json()["items"]
        assert [(x["orderNo"], x["orderProgressPercent"]) for x in items_b] == [("OM-2", 0)]
        assert items_b[0]["waitingMaterialTasks"] == 1


def test_order_detail_progress_respects_visibility():
    seed()
    with TestClient(backend.app) as client:
        a = login(client, "sup-a")
        body = client.get("/api/v1/orders/OM-1/detail", headers=H(a)).json()
        assert body["progress"] == {"totalTasks": 2, "completedTasks": 1, "waitingMaterialTasks": 0,
                                    "inProgressTasks": 1, "orderProgressPercent": 83}

        # 装配工只被指派 t-a1（阶段 2）→ 只见 1 台，进度 2/(1*3)=67%
        c = backend.db()
        now = backend.now()
        c.execute("UPDATE assembly_tasks SET assigned_assembler_id='asm-1' WHERE id='t-a1'")
        c.commit()
        c.close()
        asm = login(client, "asm-1")
        body_asm = client.get("/api/v1/orders/OM-1/detail", headers=H(asm)).json()
        assert body_asm["progress"]["totalTasks"] == 1
        assert body_asm["progress"]["completedTasks"] == 0
        assert body_asm["progress"]["orderProgressPercent"] == 67
