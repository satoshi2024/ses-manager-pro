// 月次締めチェックリスト（monthly-closing-checklist / P3）
let closingSummary = null;

document.addEventListener('DOMContentLoaded', () => {
    // 既定=前月
    const now = new Date();
    const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    document.getElementById('closingMonth').value =
        prev.getFullYear() + '-' + String(prev.getMonth() + 1).padStart(2, '0');

    document.getElementById('btnLoadClosing').addEventListener('click', loadClosing);
    // 締め完了/解除ボタンは権限のないロール（HR等）には描画されないためnullガードする（R3R-08）。
    const btnConfirm = document.getElementById('btnConfirmClosing');
    if (btnConfirm) btnConfirm.addEventListener('click', confirmClosing);
    const btnReopen = document.getElementById('btnReopenClosing');
    if (btnReopen) btnReopen.addEventListener('click', reopenClosing);
    loadClosing();
});

function loadClosing() {
    const month = document.getElementById('closingMonth').value;
    if (!month) return;
    fetch('/api/monthly-closing/summary?month=' + encodeURIComponent(month))
        .then(res => res.json()).then(data => {
            if (data.code !== 200) { alert(data.message); return; }
            closingSummary = data.data;
            renderCards();
        });
}

function card(titleKey, count, items, linkFn, isGrey) {
    const zero = count === 0;
    const color = isGrey ? 'bg-secondary' : (zero ? 'bg-success' : 'bg-warning text-dark');
    const div = document.createElement('div');
    div.className = 'col-md-4 col-lg-2';
    div.innerHTML =
        `<div class="card h-100" style="cursor:pointer">
            <div class="card-body text-center">
                <div class="small">${SES.escapeHtml(SES.i18n.t(titleKey))}</div>
                <span class="badge ${color} fs-5">${count}</span>
            </div>
        </div>`;
    // titleKeyは既知の定数だが、クリックハンドラはdata属性経由で安全に紐付ける
    div.querySelector('.card').addEventListener('click', () => showClosingDetail(titleKey));
    return div;
}

function renderCards() {
    const s = closingSummary;
    const cards = document.getElementById('closingCards');
    cards.innerHTML = '';
    cards.appendChild(card('closing.item.unentered', s.unenteredCount));
    cards.appendChild(card('closing.item.unconfirmed', s.unconfirmedCount));
    cards.appendChild(card('closing.item.unbilled', s.unbilledCount));
    cards.appendChild(card('closing.item.unpaidBp', s.unpaidBpCount));
    cards.appendChild(card('closing.item.overdue', s.overdueCount, null, null, true));

    const confirmBtn = document.getElementById('btnConfirmClosing');
    if (confirmBtn) confirmBtn.disabled = !s.readyToClose || s.closed;

    const banner = document.getElementById('closedBanner');
    const reopenBtn = document.getElementById('btnReopenClosing');
    if (s.closed) {
        banner.classList.remove('d-none');
        banner.textContent = SES.i18n.t('closing.status.closed', [s.closedByName || '', (s.closedAt || '').replace('T', ' ')]);
        if (reopenBtn) reopenBtn.classList.remove('d-none');
    } else {
        banner.classList.add('d-none');
        if (reopenBtn) reopenBtn.classList.add('d-none');
    }

    const diff = document.getElementById('diffWarning');
    const hasRemaining = s.unenteredCount + s.unconfirmedCount + s.unbilledCount + s.unpaidBpCount > 0;
    diff.classList.toggle('d-none', !(s.closed && hasRemaining));

    document.getElementById('closingDetail').innerHTML = '';
}

function showClosingDetail(key) {
    const s = closingSummary;
    const month = encodeURIComponent(s.month);
    let rows = [];
    let headers = [];
    if (key === 'closing.item.unentered') {
        headers = ['契約番号', '要員', '案件', '操作'];
        rows = s.unenteredWork.map(r => [r.contractNo, r.engineerName, r.projectName, `<a href="/work-record?month=${month}" class="btn btn-sm btn-outline-primary">修正画面</a>`]);
    } else if (key === 'closing.item.unconfirmed') {
        headers = ['契約ID', 'ステータス', '工数', '操作'];
        rows = s.unconfirmedRecords.map(r => [r.contractId, r.status, r.actualHours, `<a href="/work-record?month=${month}" class="btn btn-sm btn-outline-primary">修正画面</a>`]);
    } else if (key === 'closing.item.unbilled') {
        headers = ['顧客名 / 要員', '案件', '金額', '操作'];
        let html = `<h5>${SES.escapeHtml(SES.i18n.t(key))}</h5>`;
        s.unbilledConfirmed.forEach(g => {
            html += `<h6>${SES.escapeHtml(g.customerName)} (小計: ${g.subtotal})</h6>`;
            html += `<table class="table table-sm table-bordered"><thead><tr>`;
            headers.forEach(h => html += `<th>${h}</th>`);
            html += `</tr></thead><tbody>`;
            g.items.forEach(r => {
                html += `<tr>
                    <td>${SES.escapeHtml(r.engineerName)}</td>
                    <td>${SES.escapeHtml(r.projectName)}</td>
                    <td>${r.billingAmount}</td>
                    <td><a href="/invoice?month=${month}&customerId=${encodeURIComponent(g.customerId)}" class="btn btn-sm btn-outline-primary">請求作成</a></td>
                </tr>`;
            });
            html += `</tbody></table>`;
        });
        document.getElementById('closingDetail').innerHTML = html;
        return;
    } else if (key === 'closing.item.unpaidBp') {
        headers = ['要員', '案件', '金額', '操作'];
        rows = s.unpaidBp.map(r => [r.engineerName, r.projectName, r.amount, `<a href="/invoice?tab=bp-payment&month=${month}" class="btn btn-sm btn-outline-primary">支払画面</a>`]);
    } else if (key === 'closing.item.overdue') {
        headers = ['請求書番号', '顧客', '残高', '期限', '操作'];
        rows = s.overdueInvoices.map(r => [r.invoiceNo, r.customerName, r.balance, r.dueDate, `<a href="/invoice?invoiceId=${encodeURIComponent(r.invoiceId)}" class="btn btn-sm btn-outline-primary">督促</a>`]);
    }
    let html = `<h5>${SES.escapeHtml(SES.i18n.t(key))}</h5><table class="table table-sm table-bordered"><thead><tr>`;
    headers.forEach(h => html += `<th>${h}</th>`);
    html += '</tr></thead><tbody>';
    rows.forEach(r => {
        html += '<tr>';
        for (let i = 0; i < r.length - 1; i++) {
            html += `<td>${SES.escapeHtml(String(r[i] == null ? '' : r[i]))}</td>`;
        }
        html += `<td>${r[r.length - 1]}</td></tr>`;
    });
    html += '</tbody></table>';
    document.getElementById('closingDetail').innerHTML = html;
}

function confirmClosing() {
    const month = document.getElementById('closingMonth').value;
    fetch('/api/monthly-closing/confirm', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify({ month })
    }).then(res => res.json()).then(data => {
        if (data.code === 200) { SES.toast.success(SES.i18n.t('closing.btn.confirm') + ' OK'); loadClosing(); }
        else alert(data.message);
    }).catch(e => {
        SES.toast.error("通信エラーが発生しました");
    });
}

function reopenClosing() {
    const month = document.getElementById('closingMonth').value;
    fetch('/api/monthly-closing/reopen', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify({ month })
    }).then(res => res.json()).then(data => {
        if (data.code === 200) loadClosing();
        else alert(data.message);
    });
}
