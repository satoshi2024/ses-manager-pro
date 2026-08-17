// 1on1（engineer-self-service-portal-v2 B2）
document.addEventListener('DOMContentLoaded', () => {
    loadSalesUsers();
    load();
    document.getElementById('oneonone-submit').addEventListener('click', create);
});

function loadSalesUsers() {
    // 相手候補（担当営業・上長）: 本人の担当営業（my profile.primarySalesUserId）を既定にする。
    // 相手IDの有効性（営業/マネージャー/HR・有効）はサーバー側で検証する。
    SES.api.get('/api/my/profile').then(p => {
        const sel = document.getElementById('oneonone-counterpart');
        sel.innerHTML = '';
        if (p.primarySalesUserId) {
            const opt = document.createElement('option');
            opt.value = p.primarySalesUserId;
            opt.textContent = p.primarySalesUserName || p.primarySalesUserId;
            sel.appendChild(opt);
        } else {
            const opt = document.createElement('option');
            opt.value = '';
            opt.textContent = SES.i18n.t('my.oneOnOne.noCounterpart', '担当営業が未設定です');
            sel.appendChild(opt);
        }
    }).catch(() => {});
}

function load() {
    SES.api.get('/api/my/one-on-ones', { current: 1, size: 50 })
        .then(data => render(data.records || []))
        .catch(() => {});
}

function render(rows) {
    const body = document.getElementById('oneonone-body');
    if (!rows.length) { body.innerHTML = '<tr><td colspan="6" class="text-center py-4 text-muted">' + SES.escapeHtml(SES.i18n.t('my.oneOnOne.empty', '1on1申請はありません')) + '</td></tr>'; return; }
    body.innerHTML = rows.map(r => {
        const canCancel = r.status === '申請';
        const ops = canCancel ? `<button class="btn btn-sm btn-outline-secondary" data-id="${r.id}">${SES.escapeHtml(SES.i18n.t('my.oneOnOne.cancel','取消'))}</button>` : '&nbsp;';
        return `<tr>
            <td>${SES.escapeHtml(r.counterpartName || '')}</td>
            <td>${SES.escapeHtml((r.candidateDates || []).join(', '))}</td>
            <td>${SES.escapeHtml(r.scheduledAt || '—')}</td>
            <td>${SES.escapeHtml(r.status)}</td>
            <td>${SES.escapeHtml(r.employeeVisibleNote || '—')}</td>
            <td>${ops}</td>
        </tr>`;
    }).join('');
    body.querySelectorAll('button[data-id]').forEach(btn => btn.addEventListener('click', () => cancel(btn.dataset.id)));
}

async function cancel(id) {
    try {
        await SES.api.post('/api/my/one-on-ones/' + id + '/cancel', {});
        load();
    } catch (e) { /* toasts */ }
}

async function create() {
    const counterpartUserId = document.getElementById('oneonone-counterpart').value;
    const dates = [1, 2, 3].map(n => document.getElementById('candidate-' + n).value).filter(v => v);
    if (!counterpartUserId || !dates.length) {
        Toast.error(SES.i18n.t('my.oneOnOne.required', '相手と候補日を指定してください'));
        return;
    }
    try {
        await SES.api.post('/api/my/one-on-ones', { counterpartUserId: Number(counterpartUserId), candidateDates: dates });
        load();
        const modal = bootstrap.Modal.getInstance(document.getElementById('oneOnOneModal'));
        if (modal) modal.hide();
    } catch (e) { /* toasts */ }
}
