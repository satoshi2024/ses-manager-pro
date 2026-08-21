// 月次締めチェックリスト（monthly-closing-checklist / P3）
let closingSummary = null;

document.addEventListener('DOMContentLoaded', () => {
    // 既定=前月
    const now = new Date();
    const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    document.getElementById('closingMonth').value = SES.util.getLocalDateString(prev).slice(0, 7);

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
            if (data.code !== 200) {
                (window.Toast || SES.toast).error(data.message);
                return;
            }
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
    // (g) 未検収件数（R4.2）: 締めは妨げない（overdueと同じ扱い）
    cards.appendChild(card('closing.item.unaccepted', s.unacceptedCount, null, null, true));
    cards.appendChild(card('closing.item.compliance', s.complianceCount, null, null, true));

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
    const h = (k) => SES.i18n.t(k);
    if (key === 'closing.item.unentered') {
        headers = [h('closing.detail.contractNo'), h('closing.detail.engineer'), h('closing.detail.project'), h('closing.detail.action')];
        rows = s.unenteredWork.map(r => [r.contractNo, r.engineerName, r.projectName, `<a href="/work-record?month=${month}" class="btn btn-sm btn-outline-primary">${SES.escapeHtml(h('closing.detail.openWorkRecord'))}</a>`]);
    } else if (key === 'closing.item.unconfirmed') {
        headers = [h('closing.detail.contractId'), h('closing.detail.status'), h('closing.detail.hours'), h('closing.detail.action')];
        rows = s.unconfirmedRecords.map(r => [r.contractId, r.status, r.actualHours, `<a href="/work-record?month=${month}" class="btn btn-sm btn-outline-primary">${SES.escapeHtml(h('closing.detail.openWorkRecord'))}</a>`]);
    } else if (key === 'closing.item.unbilled') {
        headers = [h('closing.detail.customerEngineer'), h('closing.detail.project'), h('closing.detail.amount'), h('closing.detail.action')];
        let html = `<h5>${SES.escapeHtml(SES.i18n.t(key))}</h5>`;
        s.unbilledConfirmed.forEach(g => {
            html += `<h6>${SES.escapeHtml(g.customerName)} (${SES.escapeHtml(h('closing.detail.subtotal'))}: ¥${Number(g.subtotal || 0).toLocaleString()})</h6>`;
            html += `<div class="table-responsive"><table class="table table-sm table-bordered"><thead><tr>`;
            headers.forEach(hdr => html += `<th>${SES.escapeHtml(hdr)}</th>`);
            html += `</tr></thead><tbody>`;
            g.items.forEach(r => {
                html += `<tr>
                    <td>${SES.escapeHtml(r.engineerName)}</td>
                    <td>${SES.escapeHtml(r.projectName)}</td>
                    <td>¥${Number(r.billingAmount || 0).toLocaleString()}</td>
                    <td><a href="/invoice?month=${month}&customerId=${encodeURIComponent(g.customerId)}" class="btn btn-sm btn-outline-primary">${SES.escapeHtml(h('closing.detail.createInvoice'))}</a></td>
                </tr>`;
            });
            html += `</tbody></table></div>`;
        });
        document.getElementById('closingDetail').innerHTML = html;
        return;
    } else if (key === 'closing.item.unpaidBp') {
        headers = [h('closing.detail.engineer'), h('closing.detail.project'), h('closing.detail.amount'), h('closing.detail.action')];
        rows = s.unpaidBp.map(r => [r.engineerName, r.projectName, '¥' + Number(r.amount || 0).toLocaleString(), `<a href="/invoice?tab=bp-payment&month=${month}" class="btn btn-sm btn-outline-primary">${SES.escapeHtml(h('closing.detail.openPayment'))}</a>`]);
    } else if (key === 'closing.item.overdue') {
        headers = [h('closing.detail.invoiceNo'), h('closing.detail.customer'), h('closing.detail.balance'), h('closing.detail.dueDate'), h('closing.detail.action')];
        rows = s.overdueInvoices.map(r => [r.invoiceNo, r.customerName, '¥' + Number(r.balance || 0).toLocaleString(), r.dueDate, `<a href="/invoice?invoiceId=${encodeURIComponent(r.invoiceId)}" class="btn btn-sm btn-outline-primary">${SES.escapeHtml(h('closing.detail.remind'))}</a>`]);
    } else if (key === 'closing.item.compliance') {
        // 該当リスク列は最終列(操作)より前のため showClosingDetail の共通ループでエスケープされる。
        // 生HTML(<br>等)は差し込まずプレーンテキストで連結する。
        headers = [h('closing.detail.contractNo'), h('closing.detail.engineer'), h('closing.detail.project'), h('closing.detail.risk'), h('closing.detail.action')];
        rows = s.complianceFindings.map(r => [r.contractNo, r.engineerName, r.projectName,
            (r.findings || []).map(f => f.message).join(' / '),
            `<a href="/compliance" class="btn btn-sm btn-outline-primary">${SES.escapeHtml(h('closing.detail.openDetail'))}</a>`]);
    }
    let html = `<h5>${SES.escapeHtml(SES.i18n.t(key))}</h5><div class="table-responsive"><table class="table table-sm table-bordered"><thead><tr>`;
    headers.forEach(hdr => html += `<th>${SES.escapeHtml(hdr)}</th>`);
    html += '</tr></thead><tbody>';
    rows.forEach(r => {
        html += '<tr>';
        for (let i = 0; i < r.length - 1; i++) {
            html += `<td>${SES.escapeHtml(String(r[i] == null ? '' : r[i]))}</td>`;
        }
        html += `<td>${r[r.length - 1]}</td></tr>`;
    });
    html += '</tbody></table></div>';
    document.getElementById('closingDetail').innerHTML = html;
}

function confirmClosing() {
    const month = document.getElementById('closingMonth').value;
    const button = document.getElementById('btnConfirmClosing');
    if (button) button.disabled = true;
    fetch('/api/monthly-closing/confirm', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify({ month })
    }).then(res => res.json()).then(data => {
        if (data.code === 200) { SES.toast.success(SES.i18n.t('approval.requestSubmitted', '申請を受け付けました。承認完了後に反映されます。')); loadClosing(); }
        else (window.Toast || SES.toast).error(data.message);
    }).catch(e => {
        SES.toast.error("通信エラーが発生しました");
    }).finally(() => { if (button) button.disabled = false; });
}

function reopenClosing() {
    const month = document.getElementById('closingMonth').value;
    const button = document.getElementById('btnReopenClosing');
    if (button) button.disabled = true;
    fetch('/api/monthly-closing/reopen', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify({ month })
    }).then(res => res.json()).then(data => {
        if (data.code === 200) loadClosing();
        else (window.Toast || SES.toast).error(data.message);
    }).finally(() => { if (button) button.disabled = false; });
}
