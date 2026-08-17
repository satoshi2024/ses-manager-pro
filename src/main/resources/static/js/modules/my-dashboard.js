// マイダッシュボード（engineer-self-service-portal-v2 A1）
document.addEventListener('DOMContentLoaded', () => {
    loadDashboard();
});

function loadDashboard() {
    SES.api.get('/api/my/profile')
        .then(data => {
            renderProfile(data);
            renderRequestSummary(data);
        })
        .catch(() => {
            const summary = document.getElementById('my-request-summary');
            if (summary) summary.innerHTML = '<span class="text-warning">' + SES.escapeHtml(SES.i18n.t('my.dashboard.loadFailed', '読み込みに失敗しました')) + '</span>';
        });
}

function renderProfile(data) {
    const name = document.getElementById('my-name');
    const badge = document.getElementById('my-badge');
    if (name) name.textContent = data.fullName || '—';
    if (badge) {
        const bits = [];
        if (data.status) bits.push(SES.escapeHtml(data.status));
        if (data.primarySalesUserName) {
            bits.push(SES.escapeHtml(SES.i18n.t('my.dashboard.salesRep', '担当営業: {0}').replace('{0}', data.primarySalesUserName)));
        }
        badge.innerHTML = bits.join(' / ') || '&nbsp;';
    }
}

function renderRequestSummary(data) {
    const el = document.getElementById('my-request-summary');
    if (!el) return;
    const pending = data.pendingChangeRequests || 0;
    el.innerHTML = pending > 0
        ? '<span class="text-info">' + SES.escapeHtml(SES.i18n.t('my.dashboard.pendingRequests', '申請中/承認待ちの変更申請が {0} 件あります').replace('{0}', pending)) + '</span>'
        : '<span>' + SES.escapeHtml(SES.i18n.t('my.dashboard.noPending', '変更申請はありません')) + '</span>';
}
