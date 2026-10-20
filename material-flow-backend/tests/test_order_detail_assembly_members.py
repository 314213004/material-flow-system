"""协作装配：assembly_task_members 非 LEAD 成员的订单详情/机台详情可见性。

回归目标（批次一 · 项 1）：
- 非 LEAD 成员能打开订单详情与本人参与的机台任务（此前按 assigned_assembler_id 判权 → 403）；
- 可见范围不放大：成员只看到自己参与的任务，看不到同订单其他机台任务；
- 非成员 / 已被移除的成员仍然 403；
- LEAD（assigned_assembler_id）与管理端行为不回归。
"""
from __future__ import annotations
import importlib.util, os, sys, tempfile
from fastapi.testclient import TestClient

ROOT = tempfile.mkdtemp(prefix="mf_order_detail_members_")
os.environ.update(MATERIAL_FLOW_DATA=f"{ROOT}/data", MATERIAL_FLOW_UPLOADS=f"{ROOT}/uploads", INITIAL_ADMIN_PASSWORD="Admin@2026")
APP = os.path.join(os.path.dirname(os.path.dirname(__file__)), "app")
sys.path.insert(0, APP)
spec = importlib.util.spec_from_file_location("order_detail_members_backend", os.path.join(APP, "main.py"))
backend = importlib.util.module_from_spec(spec); sys.modules[spec.name] = backend; spec.loader.exec_module(backend); backend.init_db()

H = lambda token: {"Authorization": f"Bearer {token}"}


def add(uid, name, role):
    c = backend.db(); c.execute("INSERT INTO users VALUES(?,?,?,?,?,?,?,?)", (uid, name, name, role, backend.hash_password("Worker@2026"), 0, 1, backend.now())); c.commit(); c.close()


def login(client, name, password="Worker@2026"):
    r = client.post("/api/v1/auth/login", json={"username": name, "password": password, "deviceId": name}); assert r.status_code == 200, r.text; return r.json()["accessToken"]


def seed():
    """OM-1 下两台机台：task-m1 由 asm-lead 主责、asm-member 协作、asm-removed 已移除；
    task-m2 由 asm-third 主责。asm-outsider 只在 OM-2 有任务，与 OM-1 无关。"""
    for uid in ("asm-lead", "asm-member", "asm-third", "asm-outsider", "asm-removed", "sup-detail2"):
        add(uid, uid, "WORKSHOP_SUPERVISOR" if uid.startswith("sup") else "ASSEMBLER")
    c = backend.db(); now = backend.now()
    c.execute("INSERT INTO production_orders VALUES(?,?,?,?,?,?)", ("ord-m", "OM-1", "协作产品", "IN_PROGRESS", now, now))
    c.execute("INSERT INTO production_orders VALUES(?,?,?,?,?,?)", ("ord-m2", "OM-2", "他人产品", "IN_PROGRESS", now, now))
    c.execute("INSERT INTO devices VALUES(?,?,?,?,?,?,?,?,?,?)", ("dev-m1", "OM-1-M1", "机台M1", "一车间", None, None, "ACTIVE", "u_admin", now, now))
    c.execute("INSERT INTO devices VALUES(?,?,?,?,?,?,?,?,?,?)", ("dev-m2", "OM-1-M2", "机台M2", "一车间", None, None, "ACTIVE", "u_admin", now, now))
    c.execute("INSERT INTO devices VALUES(?,?,?,?,?,?,?,?,?,?)", ("dev-o1", "OM-2-M1", "机台O1", "一车间", None, None, "ACTIVE", "u_admin", now, now))
    # assembly_tasks 列序：id, order_no, device_id, device_no, assigned_assembler_id, status, progress_stage, task_version, material_accepted_at, completed_at, created_at, updated_at
    c.execute("INSERT INTO assembly_tasks VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", ("task-m1", "OM-1", "dev-m1", "OM-1-M1", "asm-lead", "IN_PROGRESS", 2, 1, None, None, now, now))
    c.execute("INSERT INTO assembly_tasks VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", ("task-m2", "OM-1", "dev-m2", "OM-1-M2", "asm-third", "WAITING_MATERIAL", 0, 1, None, None, now, now))
    c.execute("INSERT INTO assembly_tasks VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", ("task-o1", "OM-2", "dev-o1", "OM-2-M1", "asm-outsider", "IN_PROGRESS", 1, 1, None, None, now, now))
    c.execute("INSERT INTO assembly_task_members(task_id,assembler_id,assignment_role,assigned_by,assigned_at,removed_at) VALUES(?,?,?,?,?,?)",
              ("task-m1", "asm-lead", "LEAD", "u_admin", now, None))
    c.execute("INSERT INTO assembly_task_members(task_id,assembler_id,assignment_role,assigned_by,assigned_at,removed_at) VALUES(?,?,?,?,?,?)",
              ("task-m1", "asm-member", "MEMBER", "u_admin", now, None))
    c.execute("INSERT INTO assembly_task_members(task_id,assembler_id,assignment_role,assigned_by,assigned_at,removed_at) VALUES(?,?,?,?,?,?)",
              ("task-m1", "asm-removed", "MEMBER", "u_admin", now, now))
    c.commit(); c.close()


def test_member_can_open_order_detail_and_own_task():
    seed()
    with TestClient(backend.app) as client:
        admin = login(client, "owlco", "Admin@2026")
        lead = login(client, "asm-lead")
        member = login(client, "asm-member")
        r = client.get("/api/v1/orders/OM-1/detail", headers=H(member))
        assert r.status_code == 200, r.text
        body = r.json()
        # 成员只看到本人参与的机台任务（可见范围不放大）
        assert body["total"] == 1 and [t["taskId"] for t in body["assemblyTasks"]] == ["task-m1"]
        # 与 LEAD 的 payload 完全一致
        lead_body = client.get("/api/v1/orders/OM-1/detail", headers=H(lead))
        assert lead_body.status_code == 200, lead_body.text
        assert body == lead_body.json()
        # 机台任务详情同样放行
        assert client.get("/api/v1/orders/OM-1/tasks/task-m1", headers=H(member)).status_code == 200
        # 但看不到同订单其他人主责的机台
        assert client.get("/api/v1/orders/OM-1/tasks/task-m2", headers=H(member)).status_code == 403
        # 管理端范围不变：两台机台都可见
        admin_body = client.get("/api/v1/orders/OM-1/detail", headers=H(admin))
        assert admin_body.status_code == 200, admin_body.text
        assert admin_body.json()["total"] == 2


def test_member_can_open_own_task_detail():
    """项 1 的第二个端点：/orders/{no}/tasks/{id} 对协作成员放行（此前 403）。"""
    seed()
    with TestClient(backend.app) as client:
        member = login(client, "asm-member")
        r = client.get("/api/v1/orders/OM-1/tasks/task-m1", headers=H(member))
        assert r.status_code == 200, r.text
        assert r.json()["deviceNo"] == "OM-1-M1"
        # 同订单他人主责的机台仍不可见
        assert client.get("/api/v1/orders/OM-1/tasks/task-m2", headers=H(member)).status_code == 403


def test_lead_path_not_regressed():
    seed()
    with TestClient(backend.app) as client:
        lead = login(client, "asm-lead")
        r = client.get("/api/v1/orders/OM-1/detail", headers=H(lead))
        assert r.status_code == 200, r.text
        assert r.json()["total"] == 1
        assert client.get("/api/v1/orders/OM-1/tasks/task-m1", headers=H(lead)).status_code == 200
        # LEAD 也只能看自己主责的机台
        assert client.get("/api/v1/orders/OM-1/tasks/task-m2", headers=H(lead)).status_code == 403


def test_non_member_and_removed_member_still_forbidden():
    seed()
    with TestClient(backend.app) as client:
        outsider = login(client, "asm-outsider")
        removed = login(client, "asm-removed")
        for token in (outsider, removed):
            assert client.get("/api/v1/orders/OM-1/detail", headers=H(token)).status_code == 403
            assert client.get("/api/v1/orders/OM-1/tasks/task-m1", headers=H(token)).status_code == 403
        # 未受影响的既有范围：outsider 在自己主责的订单里仍然正常
        assert client.get("/api/v1/orders/OM-2/detail", headers=H(outsider)).status_code == 200
        assert client.get("/api/v1/orders/OM-2/tasks/task-o1", headers=H(outsider)).status_code == 200


def test_member_cross_order_detail_forbidden():
    """P2-4-a：OM-1 的合法成员请求 OM-2 的订单详情 → 403（跨订单不因协作成员身份放行）。

    这是改谓词时最容易写漏的方向：只要把 `order_no` 条件写丢，其它用例会全绿。
    """
    seed()
    with TestClient(backend.app) as client:
        member = login(client, "asm-member")
        # 先确认该 token 本身有效，避免"全是 403"式的假通过
        assert client.get("/api/v1/orders/OM-1/detail", headers=H(member)).status_code == 200
        r = client.get("/api/v1/orders/OM-2/detail", headers=H(member))
        assert r.status_code == 403, r.text
        assert r.json()["error"]["code"] == "FORBIDDEN"


def test_member_cross_order_task_detail_forbidden():
    """P2-4-b：OM-1 的合法成员请求 OM-2 的机台任务详情 → 403（task-o1 主责 asm-outsider）。"""
    seed()
    with TestClient(backend.app) as client:
        member = login(client, "asm-member")
        assert client.get("/api/v1/orders/OM-1/tasks/task-m1", headers=H(member)).status_code == 200
        t = client.get("/api/v1/orders/OM-2/tasks/task-o1", headers=H(member))
        assert t.status_code == 403, t.text
        assert t.json()["error"]["code"] == "FORBIDDEN"


def test_supervisor_scope_unchanged():
    seed()
    with TestClient(backend.app) as client:
        sup = login(client, "sup-detail2")
        r = client.get("/api/v1/orders/OM-1/detail", headers=H(sup))
        assert r.status_code == 200, r.text
        assert r.json()["total"] == 2
        assert client.get("/api/v1/orders/OM-1/tasks/task-m2", headers=H(sup)).status_code == 200
