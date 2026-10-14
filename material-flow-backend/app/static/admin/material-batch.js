/* 0.14.0 订单页 / 机台页物料勾选批量操作（出库申请 / 退料 / 厂内调拨 / 异常提报）。
 * - 勾选状态按订单号保存在内存，物料列表局部刷新（分页 / 搜索）后自动回填，可跨页勾选；
 * - 提交走 POST /admin/orders/{orderNo}/materials/batch（会话 + CSRF），服务端逐条调用既有 API；
 * - 结果逐条回显：成功项自动取消勾选，失败项保留并展示原因，可修正后再次提交。
 */
(function () {
  if (window.__materialBatchBound) return;
  window.__materialBatchBound = true;

  var stores = {};
  var MAX_ITEMS = 100;

  function rootOf(el) { return el && el.closest ? el.closest('[data-mb-root]') : null; }
  function storeOf(root) {
    var key = root.getAttribute('data-order') || '';
    if (!stores[key]) stores[key] = new Map();
    return stores[key];
  }
  function rowData(box) {
    return {
      id: box.value,
      code: box.getAttribute('data-code') || box.value,
      name: box.getAttribute('data-name') || '',
      req: parseInt(box.getAttribute('data-req'), 10) || 0,
      stock: parseInt(box.getAttribute('data-stock'), 10) || 0,
      device: box.getAttribute('data-device') || ''
    };
  }
  function sync(root) {
    var store = storeOf(root);
    var boxes = root.querySelectorAll('.mb-row');
    var allChecked = boxes.length > 0;
    boxes.forEach(function (b) {
      b.checked = store.has(b.value);
      if (!b.checked) allChecked = false;
    });
    var all = root.querySelector('.mb-all');
    if (all) { all.checked = allChecked; all.disabled = boxes.length === 0; }
    var count = root.querySelector('.mb-count');
    if (count) count.textContent = String(store.size);
    root.querySelectorAll('.mb-act').forEach(function (b) { b.disabled = store.size === 0; });
  }
  function syncAll() { document.querySelectorAll('[data-mb-root]').forEach(sync); }

  var pending = false;
  new MutationObserver(function () {
    if (pending) return;
    pending = true;
    requestAnimationFrame(function () { pending = false; syncAll(); });
  }).observe(document.documentElement, { childList: true, subtree: true });
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', syncAll);
  else syncAll();

  document.addEventListener('change', function (e) {
    var t = e.target;
    if (!t || !t.classList) return;
    var root = rootOf(t);
    if (!root) return;
    var store = storeOf(root);
    if (t.classList.contains('mb-row')) {
      if (t.checked) store.set(t.value, rowData(t)); else store.delete(t.value);
      sync(root);
    } else if (t.classList.contains('mb-all')) {
      root.querySelectorAll('.mb-row').forEach(function (b) {
        if (t.checked) store.set(b.value, rowData(b)); else store.delete(b.value);
      });
      sync(root);
    }
  });

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function openDialog(root, action, label) {
    var store = storeOf(root);
    if (!store.size) return;
    var mask = root.querySelector('.mb-mask');
    var form = mask.querySelector('.mb-dialog');
    form.reset();
    form.setAttribute('data-action', action);
    form.querySelector('.mb-title').textContent = '批量' + label;
    var desc = {
      OUTBOUND: '将为每条物料分别创建出库流转申请（待审批），数量默认取需求数量。',
      RETURN: '将为每条物料分别创建退料申请（按入库审批规则处理），数量默认取需求数量。',
      TRANSFER: '将为每条物料分别创建厂内调拨申请：来源库位扣减、目标库位增加，审批执行后生效。',
      EXCEPTION: '将为每条物料分别提报异常，数量填写实际清点数量（默认等于账面在库数）。'
    }[action] || '';
    var over = store.size > MAX_ITEMS ? '（单次最多 ' + MAX_ITEMS + ' 条，请减少勾选）' : '';
    form.querySelector('.mb-desc').textContent = '已勾选 ' + store.size + ' 项物料。' + desc + over;
    form.querySelectorAll('.mb-field').forEach(function (f) {
      var show = (f.getAttribute('data-for') || '').split(' ').indexOf(action) >= 0;
      f.style.display = show ? '' : 'none';
      f.querySelectorAll('input, select').forEach(function (i) { i.disabled = !show; });
    });
    var targetLabel = form.querySelector('.mb-target-label');
    if (targetLabel && targetLabel.firstChild) {
      targetLabel.firstChild.textContent = action === 'TRANSFER' ? '目标库位（必填）' : '目标库位（可选，留空沿用主库位）';
    }
    form.querySelector('.mb-remark-text').textContent = action === 'EXCEPTION' ? '异常描述（必填）' : '备注（可选）';
    var box = form.querySelector('.mb-items');
    box.innerHTML = '';
    var table = el('table', 'mb-table');
    var head = el('tr');
    ['物料编码', '名称', '机台', action === 'EXCEPTION' ? '实际数量' : '数量'].forEach(function (h) { head.appendChild(el('th', null, h)); });
    table.appendChild(head);
    store.forEach(function (item) {
      var tr = el('tr');
      tr.setAttribute('data-id', item.id);
      tr.appendChild(el('td', 'mat-code', item.code));
      tr.appendChild(el('td', null, item.name));
      tr.appendChild(el('td', null, item.device || '—'));
      var td = el('td');
      var input = el('input');
      input.type = 'number';
      input.name = 'qty_' + item.id;
      input.min = action === 'EXCEPTION' ? '0' : '1';
      input.step = '1';
      input.value = String(action === 'EXCEPTION' ? item.stock : Math.max(item.req, 1));
      input.className = 'mb-qty';
      td.appendChild(input);
      if (action === 'EXCEPTION') td.appendChild(el('span', 'muted', ' 账面 ' + item.stock));
      tr.appendChild(td);
      table.appendChild(tr);
    });
    box.appendChild(table);
    box.style.display = '';
    form.querySelector('.mb-result').innerHTML = '';
    var ok = form.querySelector('.mb-ok');
    ok.style.display = '';
    ok.disabled = store.size > MAX_ITEMS;
    ok.textContent = '提交' + label;
    form.querySelector('.mb-cancel').textContent = '取消';
    mask.classList.add('show');
  }

  function closeDialog(root) {
    var mask = root.querySelector('.mb-mask');
    if (mask) mask.classList.remove('show');
  }

  function showError(form, text) {
    var res = form.querySelector('.mb-result');
    res.innerHTML = '';
    res.appendChild(el('p', 'mb-err', text));
  }

  function submit(root, form) {
    var store = storeOf(root);
    var action = form.getAttribute('data-action');
    var fd = new FormData(form);
    if (action === 'EXCEPTION' && !String(fd.get('remark') || '').trim()) { showError(form, '异常提报必须填写描述'); return; }
    if (action === 'TRANSFER' && !String(fd.get('targetLocation') || '').trim()) { showError(form, '厂内调拨必须填写目标库位'); return; }
    var bad = false;
    form.querySelectorAll('.mb-qty').forEach(function (i) {
      var v = String(i.value).trim();
      var min = parseInt(i.min, 10) || 0;
      if (!/^\d+$/.test(v) || parseInt(v, 10) < min) bad = true;
    });
    if (bad) { showError(form, action === 'EXCEPTION' ? '实际数量必须是非负整数' : '数量必须是正整数'); return; }
    fd.append('csrf_token', root.getAttribute('data-csrf') || '');
    fd.append('action', action);
    store.forEach(function (item) { fd.append('requirementId', item.id); });
    var ok = form.querySelector('.mb-ok');
    ok.disabled = true;
    ok.textContent = '提交中…';
    var url = '/admin/orders/' + encodeURIComponent(root.getAttribute('data-order') || '') + '/materials/batch';
    fetch(url, { method: 'POST', body: fd, credentials: 'same-origin', headers: { 'X-Requested-With': 'XMLHttpRequest' } })
      .then(function (r) { return r.json().catch(function () { return { ok: false, error: 'HTTP ' + r.status }; }); })
      .then(function (body) {
        var res = form.querySelector('.mb-result');
        res.innerHTML = '';
        if (!body || (!body.message && body.error)) {
          res.appendChild(el('p', 'mb-err', '提交失败：' + ((body && body.error) || '未知错误')));
          ok.disabled = false;
          ok.textContent = '重新提交';
          return;
        }
        (body.succeeded || []).forEach(function (s) { store.delete(s.requirementId); });
        res.appendChild(el('p', (body.failed || []).length ? 'mb-warn' : 'mb-ok-text', body.message));
        if ((body.failed || []).length) {
          var ul = el('ul', 'mb-fail-list');
          body.failed.forEach(function (f) { ul.appendChild(el('li', null, f.materialCode + '：' + f.error)); });
          res.appendChild(ul);
          res.appendChild(el('p', 'muted', '失败项仍保持勾选，可修正后再次提交。'));
        }
        form.querySelector('.mb-items').style.display = 'none';
        ok.style.display = 'none';
        form.querySelector('.mb-cancel').textContent = '关闭';
        sync(root);
      })
      .catch(function () {
        showError(form, '网络错误，请稍后重试');
        ok.disabled = false;
        ok.textContent = '重新提交';
      });
  }

  document.addEventListener('click', function (e) {
    var t = e.target;
    if (!t || !t.closest) return;
    var root = rootOf(t);
    if (!root) return;
    if (t.closest('.mb-clear')) { storeOf(root).clear(); sync(root); return; }
    var act = t.closest('.mb-act');
    if (act) { openDialog(root, act.getAttribute('data-action'), act.getAttribute('data-label') || act.textContent); return; }
    if (t.closest('.mb-cancel')) { closeDialog(root); return; }
    if (t.classList && t.classList.contains('mb-mask')) closeDialog(root);
  });

  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || !form.classList || !form.classList.contains('mb-dialog')) return;
    e.preventDefault();
    var root = rootOf(form);
    if (root) submit(root, form);
  });

  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape') return;
    document.querySelectorAll('.mb-mask.show').forEach(function (m) { m.classList.remove('show'); });
  });
})();
