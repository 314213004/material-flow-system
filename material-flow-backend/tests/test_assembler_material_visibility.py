"""装配工物料可见性：U9/报价导入形态下 LEAD 与协作成员都能看到本人机台的物料。

背景（2026-10-20 现场）：订单详情/工作台的物料清单按 `assembly_tasks.device_id =
order_material_requirements.device_id` 关联，但这两列指向不同的表——
`order_material_requirements.device_id → order_devices(id)`，
`assembly_tasks.device_id → devices(id)`。U9 项目导入与报价导入的订单两套 id 天然不同
（`u9od-…` vs `u9dev-…`），于是装配工（连 LEAD）的物料清单一律为空、齐套恒为 0。
批次一「成员 payload 与 LEAD 完全一致」的断言因为两边都为空而掩盖了它。

本文件锁定三种建单形态 × 三种视角：
- 形态：U9 项目导入（两套 id 不同）/ 报价导入（两套 id 不同）/ 本地建单（两套 id 相同）；
- 视角：LEAD（assigned_assembler_id）、协作成员（assembly_task_members 未移除）、
  非成员与已移除成员（必须仍看不到）。
"""
from __future__ import annotations
import importlib.util, os, sys, tempfile
from fastapi.testclient import TestClient

ROOT = tempfile.mkdtemp(prefix="mf_assembler_materials_")
os.environ.update(MATERIAL_FLOW_DATA=f"{ROOT}/data", MATERIAL_FLOW_UPLOADS=f"{ROOT}/uploads",
                  INITIAL_ADMIN_PASSWORD="Admin@2026")
APP = os.path.join(os.path.dirname(os.path.dirname(__file__)), "app")
sys.path.insert(0, APP)
spec = importlib.util.spec_from_file_location("assembler_materials_backend", os.path.join(APP, "main.py"))
backend = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = backend
spec.loader.exec_module(backend)
backend.init_db()

H = lambda token: {"Authorization": f"Bearer {token}"}


def add_user(uid, role="ASSEMBLER"):
    c = backend.db()
    c.execute("INSERT INTO users VALUES(?,?,?,?,?,?,?,?)",
              (uid, uid, uid, role, backend.hash_password("Worker@2026"), 0, 1, backend.now()))
    c.commit()
    c.close()


def login(client, name):
    r = client.post("/api/v1/auth/login",
                    json={"username": name, "password": "Worker@2026", "deviceId": name})
    assert r.status_code == 200, r.text
    return r.json()["accessToken"]


def seed_order(order_no, *, device_row_id, order_device_id, device_no, lead,
               material_code, qty=10):
    """按指定形态建单：device_row_id=devices.id，order_device_id=order_devices.id。

    本地建单形态两者相同；U9/报价导入形态两者不同。
    """
    c = backend.db()
    now = backend.now()
    order_id = f"ord-{order_no}"
    c.execute("INSERT OR IGNORE INTO production_orders VALUES(?,?,?,?,?,?)",
              (order_id, order_no, f"{order_no}-产品", "IN_PROGRESS", now, now))
    c.execute("INSERT OR IGNORE INTO devices VALUES(?,?,?,?,?,?,?,?,?,?)",
              (device_row_id, device_no, f"{device_no}-机台", "一车间", None, None,
               "ACTIVE", "u_admin", now, now))
    c.execute("INSERT OR IGNORE INTO order_devices(id, order_id, device_type, device_no, sequence_no, created_at)"
              " VALUES(?,?,?,?,?,?)", (order_device_id, order_id, "MACHINE", device_no, 1, now))
    mid = f"mat-{material_code}"
    c.execute("INSERT OR IGNORE INTO materials(id, code, name, specification, unit)"
              " VALUES(?,?,?,?,?)", (mid, material_code, f"{material_code}-料", "S1", "个"))
    c.execute("INSERT OR IGNORE INTO order_material_requirements(id, order_id, device_id, material_id,"
              " required_quantity, arrived_quantity, in_stock_quantity, status_code, created_at, updated_at)"
              " VALUES(?,?,?,?,?,?,?,?,?,?)",
              (f"req-{order_no}", order_id, order_device_id, mid, qty, qty, qty, "IN_STOCK", now, now))
    # 装配任务：device_id 走 devices.id（U9/报价导入的真实写法）
    c.execute("INSERT OR IGNORE INTO assembly_tasks VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
              (f"task-{order_no}", order_no, device_row_id, device_no, lead,
               "WAITING_MATERIAL", 0, 1, None, None, now, now))
    c.commit()
    c.close()


def seed_all():
    for uid in ("asm-lead", "asm-member", "asm-other", "asm-removed"):
        add_user(uid)
    # 1) U9 项目导入形态
    seed_order("OM-U9", device_row_id="u9dev-MO001", order_device_id="u9od-MO001",
               device_no="26B-001-P1", lead="asm-lead", material_code="M-U9")
    # 2) 报价导入形态
    seed_order("OM-QUOTE", device_row_id="dev-q1", order_device_id="od-q1",
               device_no="Q-001", lead="asm-lead", material_code="M-QUOTE")
    # 3) 本地建单形态（两套 id 相同）
    seed_order("OM-LOCAL", device_row_id="dev-l1", order_device_id="dev-l1",
               device_no="L-001", lead="asm-lead", material_code="M-LOCAL")
    # 协作成员 / 已移除成员只挂在 U9 形态的任务上
    c = backend.db()
    now = backend.now()
    c.execute("INSERT INTO assembly_task_members(task_id,assembler_id,assignment_role,assigned_by,"
              "assigned_at,removed_at) VALUES(?,?,?,?,?,?)",
              ("task-OM-U9", "asm-member", "MEMBER", "u_admin", now, None))
    c.execute("INSERT INTO assembly_task_members(task_id,assembler_id,assignment_role,assigned_by,"
              "assigned_at,removed_at) VALUES(?,?,?,?,?,?)",
              ("task-OM-U9", "asm-removed", "MEMBER", "u_admin", now, now))
    c.commit()
    c.close()


ORDERS = ("OM-U9", "OM-QUOTE", "OM-LOCAL")


def detail(client, token, order_no):
    return client.get(f"/api/v1/orders/{order_no}/detail", headers=H(token))


def test_lead_sees_materials_for_all_three_order_shapes():
    """LEAD 在三种建单形态下都必须看到物料（U9/报价导入曾恒为空）。"""
    seed_all()
    with TestClient(backend.app) as client:
        lead = login(client, "asm-lead")
        for order_no in ORDERS:
            r = detail(client, lead, order_no)
            assert r.status_code == 200, r.text
            body = r.json()
            assert [m["materialCode"] for m in body["materials"]] == [f"M-{order_no[3:].upper()}"], \
                f"{order_no} 物料清单为空或不符：{body['materials']}"
            assert body["materialSummary"]["totalMaterialTypes"] == 1
            assert body["materialSummary"]["totalInStockQuantity"] > 0


def test_member_sees_materials_of_joined_task():
    """协作成员（assembly_task_members 未移除）看得到本人参与机台的物料。"""
    seed_all()
    with TestClient(backend.app) as client:
        member = login(client, "asm-member")
        r = detail(client, member, "OM-U9")
        assert r.status_code == 200, r.text
        body = r.json()
        assert [m["materialCode"] for m in body["materials"]] == ["M-U9"]
        assert body["materialSummary"]["totalMaterialTypes"] == 1
        # 归属校验：成员只在 OM-U9 有任务，跨订单仍 403
        assert detail(client, member, "OM-QUOTE").status_code == 403
        assert detail(client, member, "OM-LOCAL").status_code == 403


def test_non_member_and_removed_member_see_nothing():
    seed_all()
    with TestClient(backend.app) as client:
        for name in ("asm-other", "asm-removed"):
            token = login(client, name)
            for order_no in ORDERS:
                assert detail(client, token, order_no).status_code == 403, f"{name} / {order_no}"


def test_admin_scope_unchanged():
    seed_all()
    with TestClient(backend.app) as client:
        admin = client.post("/api/v1/auth/login",
                            json={"username": "owlco", "password": "Admin@2026", "deviceId": "x"})
        assert admin.status_code == 200, admin.text
        token = admin.json()["accessToken"]
        for order_no in ORDERS:
            r = detail(client, token, order_no)
            assert r.status_code == 200, r.text
            assert len(r.json()["materials"]) == 1


def test_workspace_requirements_also_scoped_by_device_no():
    """工作台需求列表与订单详情同一谓词：装配工在 U9 形态也能拿到行。"""
    seed_all()
    c = backend.db()
    lead = c.execute("SELECT * FROM users WHERE id='asm-lead'").fetchone()
    rows = backend._workspace_requirements(c, lead, "OM-U9")
    assert [r["material_code"] for r in rows] == ["M-U9"]
    other = c.execute("SELECT * FROM users WHERE id='asm-other'").fetchone()
    assert backend._workspace_requirements(c, other, "OM-U9") == []
    c.close()
