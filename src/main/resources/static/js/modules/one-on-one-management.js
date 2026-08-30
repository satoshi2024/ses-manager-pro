// 1on1管理（engineer-self-service-portal-v2 B2 / design §6.2）
document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('searchForm');
    if (form) form.addEventListener('submit', (e) => { e.preventDefault(); load(1); });
    load(1);
});

let page = { current: 1, size: 10, total: 0 };

function load(p) {
    page.current = p || 1;
    const params = {
        engineerName: document.getElementById('searchEngineerName').value || '',
        status: document.getElementById('searchStatus').value || '',
        current: page.current, size: page.size
    };
    SES.api.get('/api/one-on-ones', params).then(d => { page = { current: d.current, size: d.size, total: d.total }; render(d.records || []); }).catch(error => {
        console.error(error);
        console.error(error.message || '1on1一覧の取得に失敗しました');
    });
}

function render(rows) {
    const body = document.getElementById('oneonone-body');
    if (!rows.length) { body.innerHTML = '<tr><td colspan="7" class="text-center py-4 text-muted">—</td></tr>'; return; }
    body.innerHTML = rows.map(r => `<tr>
        <td>${SES.escapeHtml(r.engineerName || '')}</td>
        <td>${SES.escapeHtml(r.counterpartName || '')}</td>
        <td>${SES.escapeHtml((r.candidateDates || []).join(', '))}</td>
        <td>${SES.escapeHtml(r.scheduledAt || '—')}</td>
        <td>${SES.escapeHtml(r.status)}</td>
        <td>${r.privateNoteRef ? '<span class="badge bg-danger">HR限定</span>' : '—'}</td>
        <td><button class="btn btn-sm btn-outline-primary" data-id="${r.id}">${SES.escapeHtml(SES.i18n.t('common.detail','詳細'))}</button></td>
    </tr>`).join('');
    body.querySelectorAll('button[data-id]').forEach(b => b.addEventListener('click', () => openDetail(Number(b.dataset.id))));
    const totalPages = Math.max(1, Math.ceil(page.total / page.size));
    document.getElementById('oneonone-pagination').innerHTML =
        `<span>${SES.escapeHtml(SES.i18n.t('common.page.info', [page.total, page.total === 0 ? 0 : (page.current - 1) * page.size + 1, Math.min(page.current * page.size, page.total)]))}</span>
        <span><button class="btn btn-sm btn-outline-secondary" ${page.current <= 1 ? 'disabled' : ''} data-p="${page.current - 1}">${SES.escapeHtml(SES.i18n.t('common.page.prev','前へ'))}</button>
        <button class="btn btn-sm btn-outline-secondary ms-1" ${page.current >= totalPages ? 'disabled' : ''} data-p="${page.current + 1}">${SES.escapeHtml(SES.i18n.t('common.page.next','次へ'))}</button></span>`;
    document.querySelectorAll('#oneonone-pagination button[data-p]').forEach(b => b.addEventListener('click', () => load(Number(b.dataset.p))));
}

function openDetail(id) {
    SES.api.get('/api/one-on-ones/' + id).then(r => {
        const isScheduled = r.status === '日程確定';
        const canSchedule = r.status === '申請';
        const canComplete = isScheduled;
        const canCancel = r.status !== '実施済';
        let html = `<dl class="row mb-2">
            <dt class="col-3">要員</dt><dd class="col-9">${SES.escapeHtml(r.engineerName || '')}</dd>
            <dt class="col-3">相手</dt><dd class="col-9">${SES.escapeHtml(r.counterpartName || '')}</dd>
            <dt class="col-3">候補日</dt><dd class="col-9">${SES.escapeHtml((r.candidateDates || []).join(', '))}</dd>
            <dt class="col-3">状態</dt><dd class="col-9">${SES.escapeHtml(r.status)}</dd>
            <dt class="col-3">実施記録</dt><dd class="col-9">${SES.escapeHtml(r.employeeVisibleNote || '—')}</dd>
        </dl>`;
        if (canSchedule) {
            html += `<div class="mb-2"><label class="form-label text-muted small">${SES.escapeHtml(SES.i18n.t('oneOnOne.scheduleDate','確定日程'))}</label>
                <input type="date" class="form-control form-control-sm bg-dark border-secondary text-light" id="schedule-date"></div>
                <button class="btn btn-sm btn-outline-primary mb-2" id="btn-schedule">${SES.escapeHtml(SES.i18n.t('oneOnOne.schedule','日程確定'))}</button>`;
        }
        if (canComplete) {
            html += `<div class="mb-2"><label class="form-label text-muted small">${SES.escapeHtml(SES.i18n.t('oneOnOne.note','実施記録（本人公開）'))}</label>
                <textarea class="form-control form-control-sm bg-dark border-secondary text-light" id="done-note" rows="2">${SES.escapeHtml(r.employeeVisibleNote || '')}</textarea></div>
                <button class="btn btn-sm btn-outline-success mb-2" id="btn-complete">${SES.escapeHtml(SES.i18n.t('oneOnOne.complete','実施済にする'))}</button>`;
        }
        if (canCancel) {
            html += `<button class="btn btn-sm btn-outline-danger mb-2" id="btn-cancel">${SES.escapeHtml(SES.i18n.t('oneOnOne.cancel','取消'))}</button>`;
        }
        if (r.privateNoteRef) {
            html += `<div class="mb-2"><label class="form-label text-muted small">${SES.escapeHtml(SES.i18n.t('oneOnOne.privateNote','confidential相談（HR限定）'))}</label>
                <textarea class="form-control form-control-sm bg-dark border-secondary text-light" id="private-note" rows="3">${SES.escapeHtml(SES.i18n.t('oneOnOne.privateNoteHint','保存済み。更新する場合は入力して保存'))}</textarea></div>
                <button class="btn btn-sm btn-outline-warning mb-2" id="btn-private">${SES.escapeHtml(SES.i18n.t('oneOnOne.privateSave','confidential保存'))}</button>`;
        } else {
            html += `<div class="mb-2"><label class="form-label text-muted small">${SES.escapeHtml(SES.i18n.t('oneOnOne.privateNote','confidential相談（HR限定）'))}</label>
                <textarea class="form-control form-control-sm bg-dark border-secondary text-light" id="private-note" rows="3"></textarea></div>
                <button class="btn btn-sm btn-outline-warning mb-2" id="btn-private">${SES.escapeHtml(SES.i18n.t('oneOnOne.privateSave','confidential保存'))}</button>`;
        }
        document.getElementById('detail-body').innerHTML = html;
        const btn = (id, fn) => { const el = document.getElementById(id); if (el) el.addEventListener('click', fn); };
        btn('btn-schedule', () => {
            const date = document.getElementById('schedule-date').value;
            runOneOnOneAction(r.id, 'schedule', { scheduledAt: date });
        });
        btn('btn-complete', () => {
            const note = document.getElementById('done-note').value;
            runOneOnOneAction(r.id, 'complete', { employeeVisibleNote: note });
        });
        btn('btn-cancel', () => {
            runOneOnOneAction(r.id, 'cancel', { reason: '管理側取消' });
        });
        btn('btn-private', () => {
            const note = document.getElementById('private-note').value;
            runOneOnOneAction(r.id, 'private-note', { note });
        });
    }).catch(error => {
        console.error(error);
        console.error(error.message || '1on1詳細の取得に失敗しました');
    });
}

function runOneOnOneAction(id, action, payload) {
    SES.api.post('/api/one-on-ones/' + encodeURIComponent(id) + '/' + action, payload)
        .then(() => {
            load(page.current);
            openDetail(id);
        })
        .catch(error => {
            console.error(error);
            console.error(error.message || '1on1の更新に失敗しました');
        });
}
