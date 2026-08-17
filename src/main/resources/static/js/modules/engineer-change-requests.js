// 変更申請管理（engineer-self-service-portal-v2 A1 / design §6.2）
document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('searchForm');
    if (form) form.addEventListener('submit', (e) => { e.preventDefault(); load(1); });
    load(1);
});

let crPage = { current: 1, size: 10, total: 0 };

function load(page) {
    crPage.current = page || 1;
    const params = {
        engineerName: document.getElementById('searchEngineerName').value || '',
        requestType: document.getElementById('searchType').value || '',
        status: document.getElementById('searchStatus').value || '',
        current: crPage.current,
        size: crPage.size
    };
    SES.api.get('/api/engineer-change-requests', params)
        .then(data => { crPage = { current: data.current, size: data.size, total: data.total }; render(data.records || []); })
        .catch(() => {});
}

function render(rows) {
    const body = document.getElementById('requests-body');
    if (!rows.length) { body.innerHTML = '<tr><td colspan="7" class="text-center py-4 text-muted">' + SES.escapeHtml(SES.i18n.t('changeRequest.empty', '変更申請はありません')) + '</td></tr>'; return; }
    body.innerHTML = rows.map(r => `
        <tr>
            <td>${SES.escapeHtml(String(r.id))}</td>
            <td>${SES.escapeHtml(r.engineerName || '')}</td>
            <td>${SES.escapeHtml(SES.i18n.t('changeRequest.type.' + r.requestType, r.requestType))}</td>
            <td>${SES.escapeHtml(r.status)}</td>
            <td>${SES.escapeHtml(r.approvalStatus || '—')}</td>
            <td>${r.unappliedApproved ? '<span class="badge bg-warning text-dark">' + SES.escapeHtml(SES.i18n.t('changeRequest.unapplied', '未反映')) + '</span>' : (r.appliedAt || '—')}</td>
            <td><button class="btn btn-sm btn-outline-primary" data-id="${r.id}" data-bs-toggle="modal" data-bs-target="#detailModal">${SES.escapeHtml(SES.i18n.t('common.detail','詳細'))}</button></td>
        </tr>`).join('');
    body.querySelectorAll('button[data-id]').forEach(btn => btn.addEventListener('click', () => loadDetail(btn.dataset.id)));
    const pag = document.getElementById('requests-pagination');
    const totalPages = Math.max(1, Math.ceil(crPage.total / crPage.size));
    pag.innerHTML = `<span>${SES.escapeHtml(SES.i18n.t('common.page.info', '{0} 件中 {1}-{2} 件目を表示').replace('{0}', crPage.total).replace('{1}', ((crPage.current - 1) * crPage.size + 1)).replace('{2}', Math.min(crPage.current * crPage.size, crPage.total)))}</span>
        <span>
          <button class="btn btn-sm btn-outline-secondary" ${crPage.current <= 1 ? 'disabled' : ''} data-page="${crPage.current - 1}">${SES.escapeHtml(SES.i18n.t('common.page.prev','前へ'))}</button>
          <button class="btn btn-sm btn-outline-secondary ms-1" ${crPage.current >= totalPages ? 'disabled' : ''} data-page="${crPage.current + 1}">${SES.escapeHtml(SES.i18n.t('common.page.next','次へ'))}</button>
        </span>`;
    pag.querySelectorAll('button[data-page]').forEach(btn => btn.addEventListener('click', () => load(Number(btn.dataset.page))));
}

function loadDetail(id) {
    SES.api.get('/api/engineer-change-requests/' + id)
        .then(r => {
            const payload = parseJson(r.payloadJson);
            const diff = parseJson(r.diffJson);
            let html = `<dl class="row mb-0">
                <dt class="col-3">${SES.escapeHtml(SES.i18n.t('changeRequest.id','ID'))}</dt><dd class="col-9">${SES.escapeHtml(String(r.id))}</dd>
                <dt class="col-3">${SES.escapeHtml(SES.i18n.t('changeRequest.type','種別'))}</dt><dd class="col-9">${SES.escapeHtml(SES.i18n.t('changeRequest.type.' + r.requestType, r.requestType))}</dd>
                <dt class="col-3">${SES.escapeHtml(SES.i18n.t('changeRequest.status','状態'))}</dt><dd class="col-9">${SES.escapeHtml(r.status)}</dd>
                <dt class="col-3">${SES.escapeHtml(SES.i18n.t('changeRequest.approvalStatus','承認状態'))}</dt><dd class="col-9">${SES.escapeHtml(r.approvalStatus || '—')}</dd>
                <dt class="col-3">${SES.escapeHtml(SES.i18n.t('changeRequest.reflect','反映'))}</dt><dd class="col-9">${r.unappliedApproved ? '<span class="badge bg-warning text-dark">' + SES.escapeHtml(SES.i18n.t('changeRequest.unapplied','未反映（監視対象）')) + '</span>' : (r.appliedAt || '—')}</dd>
            </dl>`;
            html += `<hr><h6>${SES.escapeHtml(SES.i18n.t('changeRequest.payload','申請内容'))}</h6><pre class="bg-black text-light p-2 rounded small">${SES.escapeHtml(JSON.stringify(payload, null, 2))}</pre>`;
            if (Object.keys(diff).length) {
                html += `<h6>${SES.escapeHtml(SES.i18n.t('changeRequest.diff','変更差分'))}</h6><pre class="bg-black text-light p-2 rounded small">${SES.escapeHtml(JSON.stringify(diff, null, 2))}</pre>`;
            }
            document.getElementById('detail-body').innerHTML = html;
        })
        .catch(() => {});
}

function parseJson(s) {
    if (!s) return {};
    try { return JSON.parse(s); } catch (E) { return {}; }
}
