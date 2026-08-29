// 経費管理（管理者=全件 / マネージャー=配下 / engineer-self-service-portal-v2 B1）
document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('searchForm');
    if (form) form.addEventListener('submit', (e) => { e.preventDefault(); loadExpenses(1); });
    loadExpenses(1);
});

let expensePage = { current: 1, size: 10, total: 0 };

const STATUS_BADGE = {
    '下書き': 'bg-secondary',
    '申請中': 'bg-info',
    '承認済': 'bg-primary',
    '会計連携済': 'bg-accent-blue',
    '支払済': 'bg-success'
};

function loadExpenses(page) {
    expensePage.current = page || 1;
    const params = {
        engineerName: document.getElementById('searchEngineerName').value || '',
        status: document.getElementById('searchStatus').value || '',
        current: expensePage.current,
        size: expensePage.size
    };
    SES.api.get('/api/expense-requests', params)
        .then(data => {
            expensePage = { current: data.current, size: data.size, total: data.total };
            renderExpenses(data.records || []);
            renderPagination();
        })
        .catch(() => { /* SES.apiが共通トーストを表示 */ });
}

function renderExpenses(rows) {
    const body = document.getElementById('expense-table-body');
    body.innerHTML = '';
    if (rows.length === 0) {
        body.innerHTML = '<tr><td colspan="8" class="text-center py-4 text-muted">' + SES.escapeHtml(SES.i18n.t('expense.empty', '該当する経費申請はありません')) + '</td></tr>';
        return;
    }
    rows.forEach(row => {
        const tr = document.createElement('tr');
        // 管理側の領収書DLは文書台帳 /api/documents 経由（FileScopeValidationServiceのRECEIPT規則で
        // 本人/管理者/マネージャー配下のみ可。営業・HRは不可視）。
        const canDownload = row.receiptDocumentId != null && row.receiptVersionNo != null;
        const canMarkPaid = row.status === '会計連携済';
        const statusText = displayStatus(row);
        tr.innerHTML = `
            <td class="ps-4">${SES.escapeHtml(row.expenseNo || ('EX-' + row.id))}</td>
            <td>${SES.escapeHtml(row.engineerName || '-')}</td>
            <td>${SES.escapeHtml(row.expenseDate || '-')}</td>
            <td>${SES.escapeHtml(row.category || '-')}</td>
            <td class="text-end">${SES.escapeHtml(SES.util.formatCurrency(row.amount))}</td>
            <td>${canDownload ? `<a href="#" class="btn btn-sm btn-outline-info" data-document="${SES.escapeHtml(String(row.receiptDocumentId))}" data-version="${SES.escapeHtml(String(row.receiptVersionNo))}"><i class="bi bi-download"></i> ${SES.escapeHtml(SES.i18n.t('expense.receipt.download', 'ダウンロード'))}</a>` : '-'}</td>
            <td><span class="badge ${STATUS_BADGE[row.status] || 'bg-secondary'}">${SES.escapeHtml(statusText)}</span></td>
            <td class="text-end pe-4">
<div class="d-flex flex-wrap justify-content-end align-items-center gap-1">${canMarkPaid ? `<button type="button" class="btn btn-sm btn-success" data-action="mark-paid" data-id="${SES.escapeHtml(String(row.id))}">${SES.escapeHtml(SES.i18n.t('expense.markPaid', '支払済にする'))}</button>` : '-'}</div>
</td>`;
        body.appendChild(tr);
    });
    body.querySelectorAll('[data-action="mark-paid"]').forEach(btn => {
        btn.addEventListener('click', () => markPaid(btn.dataset.id));
    });
    body.querySelectorAll('[data-document]').forEach(link => {
        link.addEventListener('click', (e) => {
            e.preventDefault();
            SES.api.download('/api/documents/' + encodeURIComponent(link.dataset.document)
                + '/versions/' + encodeURIComponent(link.dataset.version) + '/download', 'receipt.pdf');
        });
    });
}

function displayStatus(row) {
    if (row.approvalStatus === 'returned') return SES.i18n.t('expense.status.returned', '差戻し');
    if (row.approvalStatus === 'conflict') return SES.i18n.t('expense.status.conflict', '競合');
    return SES.i18n.t('expense.status.' + row.status, row.status);
}

function renderPagination() {
    const info = document.getElementById('expense-page-info');
    const nav = document.getElementById('expense-pagination');
    if (!info || !nav) return;
    const totalPages = Math.max(1, Math.ceil(expensePage.total / expensePage.size));
    const from = expensePage.total === 0 ? 0 : (expensePage.current - 1) * expensePage.size + 1;
    const to = Math.min(expensePage.current * expensePage.size, expensePage.total);
    info.textContent = SES.i18n.t('common.pageInfo', '全 {0} 件中 {1}-{2} 件を表示')
        .replace('{0}', expensePage.total).replace('{1}', from).replace('{2}', to);
    let html = '';
    for (let p = 1; p <= totalPages; p++) {
        const active = p === expensePage.current ? 'active' : '';
        html += `<li class="page-item ${active}"><a class="page-link bg-dark border-secondary text-light" href="#" data-page="${p}">${p}</a></li>`;
    }
    nav.innerHTML = html;
    nav.querySelectorAll('[data-page]').forEach(a => {
        a.addEventListener('click', (e) => { e.preventDefault(); loadExpenses(Number(a.dataset.page)); });
    });
}

function markPaid(id) {
    Swal.fire({
        title: SES.i18n.t('expense.markPaid', '支払済にする'),
        text: SES.i18n.t('expense.markPaid.confirm', 'この経費の支払いを確認し、支払済にしますか？'),
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: SES.i18n.t('common.ok', 'OK'),
        cancelButtonText: SES.i18n.t('common.cancel', 'キャンセル')
    }).then(result => {
        if (!result.isConfirmed) return;
        SES.api.post('/api/expense-requests/' + encodeURIComponent(id) + '/mark-paid', {})
            .then(() => {
                SES.toast.success(SES.i18n.t('expense.markedPaid', '支払済にしました'));
                loadExpenses(expensePage.current);
            }).catch(() => { /* SES.apiが共通トーストを表示 */ });
    });
}
