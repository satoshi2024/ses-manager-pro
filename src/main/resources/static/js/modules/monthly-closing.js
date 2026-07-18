// 月次締めチェックリスト（monthly-closing-checklist / P3）
let closingSummary = null;

document.addEventListener('DOMContentLoaded', () => {
    // 既定=前月
    const now = new Date();
    const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    document.getElementById('closingMonth').value =
        prev.getFullYear() + '-' + String(prev.getMonth() + 1).padStart(2, '0');

    document.getElementById('btnLoadClosing').addEventListener('click', loadClosing);
    document.getElementById('btnConfirmClosing').addEventListener('click', confirmClosing);
    document.getElementById('btnReopenClosing').addEventListener('click', reopenClosing);
    loadClosing();
});

function loadClosing() {
    const month = document.getElementById('closingMonth').value;
    if (!month) return;
    fetch('/api/monthly-closing/summary?month=' + month)
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
    div.innerHTML = `
        <div class="card h-100" style="cursor:pointer" onclick='showClosingDetail("${titleKey}")'>
            <div class="card-body text-center">
                <div class="small">${SES.i18n.t(titleKey)}</div>
                <span class="badge ${color} fs-5">${count}</span>
            </div>
        </div>`;
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

    document.getElementById('btnConfirmClosing').disabled = !s.readyToClose || s.closed;

    const banner = document.getElementById('closedBanner');
    const reopenBtn = document.getElementById('btnReopenClosing');
    if (s.closed) {
        banner.classList.remove('d-none');
        banner.textContent = SES.i18n.t('closing.status.closed', '締め済み（{0} / {1}）')
            .replace('{0}', s.closedBy || '').replace('{1}', (s.closedAt || '').replace('T', ' '));
        reopenBtn.classList.remove('d-none');
    } else {
        banner.classList.add('d-none');
        reopenBtn.classList.add('d-none');
    }

    const diff = document.getElementById('diffWarning');
    const hasRemaining = s.unenteredCount + s.unconfirmedCount + s.unbilledCount + s.unpaidBpCount + s.overdueCount > 0;
    diff.classList.toggle('d-none', !(s.closed && hasRemaining));

    document.getElementById('closingDetail').innerHTML = '';
}

function showClosingDetail(key) {
    const s = closingSummary;
    let rows = [];
    let headers = [];
    if (key === 'closing.item.unentered') {
        headers = ['契約番号', '要員', '案件'];
        rows = s.unenteredWork.map(r => [r.contractNo, r.engineerName, r.projectName]);
    } else if (key === 'closing.item.unconfirmed') {
        headers = ['契約ID', 'ステータス', '工数'];
        rows = s.unconfirmedRecords.map(r => [r.contractId, r.status, r.actualHours]);
    } else if (key === 'closing.item.unbilled') {
        headers = ['要員', '案件', '請求額'];
        rows = s.unbilledConfirmed.map(r => [r.engineerName, r.projectName, r.billingAmount]);
    } else if (key === 'closing.item.unpaidBp') {
        headers = ['要員', '案件', '金額'];
        rows = s.unpaidBp.map(r => [r.engineerName, r.projectName, r.amount]);
    } else if (key === 'closing.item.overdue') {
        headers = ['請求書番号', '顧客', '残高', '期限'];
        rows = s.overdueInvoices.map(r => [r.invoiceNo, r.customerName, r.balance, r.dueDate]);
    }
    let html = `<h5>${SES.i18n.t(key)}</h5><table class="table table-sm table-bordered"><thead><tr>`;
    headers.forEach(h => html += `<th>${h}</th>`);
    html += '</tr></thead><tbody>';
    rows.forEach(r => {
        html += '<tr>' + r.map(c => `<td>${SES.escapeHtml(String(c == null ? '' : c))}</td>`).join('') + '</tr>';
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
        if (data.code === 200) { (SES.toast || alert)(SES.i18n.t('closing.btn.confirm') + ' OK', 'success'); loadClosing(); }
        else alert(data.message);
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
