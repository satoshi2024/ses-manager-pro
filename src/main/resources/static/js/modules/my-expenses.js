// 要員ポータル（経費申請 / engineer-self-service-portal-v2 B1）
document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('searchForm');
    if (form) form.addEventListener('submit', (e) => { e.preventDefault(); loadExpenses(1); });
    document.getElementById('expenseModal').addEventListener('hidden.bs.modal', () => {
        document.getElementById('expense-form').reset();
        document.getElementById('expense-id').value = '';
        document.getElementById('expenseModalTitle').textContent = SES.i18n.t('my.expense.new', '経費を登録');
    });
    document.getElementById('receiptModal').addEventListener('hidden.bs.modal', () => {
        document.getElementById('receipt-file').value = '';
        document.getElementById('receipt-progress').classList.add('d-none');
        document.getElementById('receipt-upload-btn').disabled = false;
    });
    loadExpenses(1);
});

let expensePage = { current: 1, size: 10, total: 0 };
// 行データはインラインhandlerへ埋め込まず、idをキーにJS Mapで保持する（保存型XSS対策）。
let expenseRowMap = new Map();

const STATUS_BADGE = {
    '下書き': 'bg-secondary',
    '申請中': 'bg-info',
    '承認済': 'bg-primary',
    '会計連携済': 'bg-accent-blue',
    '支払済': 'bg-success'
};

function loadExpenses(page) {
    expensePage.current = page || 1;
    const status = document.getElementById('searchStatus').value || '';
    SES.api.get('/api/my/expenses', { status: status, current: expensePage.current, size: expensePage.size })
        .then(data => {
            expensePage = {
                current: data.current, size: data.size, total: data.total
            };
            renderExpenses(data.records || []);
            renderPagination();
        })
        .catch(() => { /* SES.apiが共通トーストを表示 */ });
}

function renderExpenses(rows) {
    const body = document.getElementById('expense-table-body');
    body.innerHTML = '';
    expenseRowMap.clear();
    if (rows.length === 0) {
        body.innerHTML = '<tr><td colspan="7" class="text-center py-4 text-muted">' + SES.escapeHtml(SES.i18n.t('my.expense.empty', '経費申請はありません')) + '</td></tr>';
        return;
    }
    rows.forEach(row => {
        expenseRowMap.set(String(row.id), row);
        const tr = document.createElement('tr');
        const statusText = displayStatus(row);
        const receiptCell = receiptCellHtml(row);
        tr.innerHTML = `
            <td class="ps-4">${SES.escapeHtml(row.expenseNo || ('EX-' + row.id))}</td>
            <td>${SES.escapeHtml(row.expenseDate || '-')}</td>
            <td>${SES.escapeHtml(row.category || '-')}</td>
            <td class="text-end">${SES.escapeHtml(SES.util.formatCurrency(row.amount))}</td>
            <td>${receiptCell}</td>
            <td><span class="badge ${STATUS_BADGE[row.status] || 'bg-secondary'}">${SES.escapeHtml(statusText)}</span></td>
            <td class="text-end pe-4">${actionCellHtml(row)}</td>`;
        body.appendChild(tr);
    });
    body.querySelectorAll('[data-action]').forEach(btn => {
        btn.addEventListener('click', () => handleAction(btn.dataset.action, btn.dataset.id));
    });
    body.querySelectorAll('[data-download-receipt]').forEach(link => {
        link.addEventListener('click', (e) => {
            e.preventDefault();
            SES.api.download('/api/my/expenses/' + encodeURIComponent(link.dataset.downloadReceipt) + '/receipt', 'receipt.pdf');
        });
    });
}

// 差戻し・競合はapproval engineのstatusを導出して表示する（leaveと同じ扱い）。
function displayStatus(row) {
    if (row.approvalStatus === 'returned') return SES.i18n.t('my.expense.status.returned', '差戻し');
    if (row.approvalStatus === 'conflict') return SES.i18n.t('my.expense.status.conflict', '競合');
    return SES.i18n.t('my.expense.status.' + row.status, row.status);
}

function receiptCellHtml(row) {
    const canAttach = ['下書き', '申請中'].includes(row.status)
        && row.approvalStatus !== 'returned' && row.approvalStatus !== 'conflict';
    const attachBtn = canAttach
        ? `<button type="button" class="btn btn-sm btn-outline-primary me-1" data-action="receipt" data-id="${SES.escapeHtml(String(row.id))}"><i class="bi bi-paperclip"></i> ${SES.escapeHtml(SES.i18n.t('my.expense.receipt.attach', '添付'))}</button>` : '';
    const downloadLink = row.receiptDocumentId
        ? `<a href="#" class="btn btn-sm btn-outline-info" data-download-receipt="${SES.escapeHtml(String(row.id))}"><i class="bi bi-download"></i> ${SES.escapeHtml(SES.i18n.t('my.expense.receipt.download', 'ダウンロード'))}</a>` : '';
    return attachBtn + downloadLink;
}

function actionCellHtml(row) {
    const buttons = [];
    if (row.status === '下書き') {
        buttons.push(`<button type="button" class="btn btn-sm btn-outline-secondary me-1" data-action="edit" data-id="${SES.escapeHtml(String(row.id))}">${SES.escapeHtml(SES.i18n.t('common.edit', '編集'))}</button>`);
        buttons.push(`<button type="button" class="btn btn-sm btn-primary me-1" data-action="submit" data-id="${SES.escapeHtml(String(row.id))}">${SES.escapeHtml(SES.i18n.t('my.expense.submit', '申請'))}</button>`);
        buttons.push(`<button type="button" class="btn btn-sm btn-outline-danger me-1" data-action="delete" data-id="${SES.escapeHtml(String(row.id))}">${SES.escapeHtml(SES.i18n.t('common.delete', '削除'))}</button>`);
    } else if (row.approvalStatus === 'returned' || row.approvalStatus === 'conflict') {
        buttons.push(`<button type="button" class="btn btn-sm btn-primary" data-action="resubmit" data-id="${SES.escapeHtml(String(row.id))}">${SES.escapeHtml(SES.i18n.t('my.expense.resubmit', '再申請'))}</button>`);
    }
    return buttons.join('');
}

function handleAction(action, id) {
    if (action === 'edit') openEditModal(id);
    else if (action === 'receipt') openReceiptModal(id);
    else if (action === 'submit') submitExpense(id);
    else if (action === 'resubmit') resubmitExpense(id);
    else if (action === 'delete') deleteExpense(id);
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

function openEditModal(id) {
    const row = findRow(id);
    if (!row) return;
    document.getElementById('expense-id').value = row.id;
    document.getElementById('expense-date').value = row.expenseDate || '';
    document.getElementById('expense-category').value = row.category || '';
    document.getElementById('expense-amount').value = row.amount || '';
    document.getElementById('expense-description').value = row.description || '';
    document.getElementById('expenseModalTitle').textContent = SES.i18n.t('my.expense.edit', '経費を編集');
    new bootstrap.Modal(document.getElementById('expenseModal')).show();
}

// 行データをidで引く（render時にMapへ格納済み）。
function findRow(id) {
    return expenseRowMap.get(String(id));
}

function saveExpense() {
    const id = document.getElementById('expense-id').value;
    const body = {
        expenseDate: document.getElementById('expense-date').value || null,
        category: document.getElementById('expense-category').value,
        amount: document.getElementById('expense-amount').value,
        description: document.getElementById('expense-description').value || null
    };
    if (!body.expenseDate || !body.category || !body.amount) {
        SES.toast.error(SES.i18n.t('my.expense.required', '必須項目を入力してください'));
        return;
    }
    const request = id
        ? SES.api.put('/api/my/expenses/' + encodeURIComponent(id), body)
        : SES.api.post('/api/my/expenses', body);
    request.then(() => {
        bootstrap.Modal.getInstance(document.getElementById('expenseModal')).hide();
        SES.toast.success(SES.i18n.t('common.saved', '保存しました'));
        loadExpenses(expensePage.current);
    }).catch(() => { /* SES.apiが共通トーストを表示 */ });
}

function openReceiptModal(id) {
    document.getElementById('receiptModal').dataset.expenseId = id;
    new bootstrap.Modal(document.getElementById('receiptModal')).show();
}

function uploadReceipt() {
    const id = document.getElementById('receiptModal').dataset.expenseId;
    const fileInput = document.getElementById('receipt-file');
    const file = fileInput.files && fileInput.files[0];
    if (!file) {
        SES.toast.error(SES.i18n.t('my.expense.receipt.selectFile', 'ファイルを選択してください'));
        return;
    }
    document.getElementById('receipt-progress').classList.remove('d-none');
    document.getElementById('receipt-upload-btn').disabled = true;
    const formData = new FormData();
    formData.append('file', file);
    fetch('/api/my/expenses/' + encodeURIComponent(id) + '/receipt', {
        method: 'POST',
        headers: SES.csrf.header(),
        body: formData
    }).then(res => res.json()).then(data => {
        document.getElementById('receipt-progress').classList.add('d-none');
        document.getElementById('receipt-upload-btn').disabled = false;
        if (data.code === 200) {
            bootstrap.Modal.getInstance(document.getElementById('receiptModal')).hide();
            SES.toast.success(SES.i18n.t('my.expense.receipt.attached', '領収書を添付しました'));
            loadExpenses(expensePage.current);
        } else {
            SES.toast.error(data.message || SES.i18n.t('common.error', '処理に失敗しました'));
        }
    }).catch(() => {
        document.getElementById('receipt-progress').classList.add('d-none');
        document.getElementById('receipt-upload-btn').disabled = false;
    });
}

function submitExpense(id) {
    Swal.fire({
        title: SES.i18n.t('my.expense.submit', '申請'),
        text: SES.i18n.t('my.expense.submit.confirm', 'この経費を承認申請します。よろしいですか？'),
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: SES.i18n.t('common.ok', 'OK'),
        cancelButtonText: SES.i18n.t('common.cancel', 'キャンセル')
    }).then(result => {
        if (!result.isConfirmed) return;
        SES.api.post('/api/my/expenses/' + encodeURIComponent(id) + '/submit', {})
            .then(() => {
                SES.toast.success(SES.i18n.t('my.expense.submitted', '申請しました'));
                loadExpenses(expensePage.current);
            }).catch(() => { /* SES.apiが共通トーストを表示 */ });
    });
}

function resubmitExpense(id) {
    Swal.fire({
        title: SES.i18n.t('my.expense.resubmit', '再申請'),
        text: SES.i18n.t('my.expense.resubmit.confirm', 'この経費を再申請します。よろしいですか？'),
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: SES.i18n.t('common.ok', 'OK'),
        cancelButtonText: SES.i18n.t('common.cancel', 'キャンセル')
    }).then(result => {
        if (!result.isConfirmed) return;
        SES.api.post('/api/my/expenses/' + encodeURIComponent(id) + '/resubmit', {})
            .then(() => {
                SES.toast.success(SES.i18n.t('my.expense.resubmitted', '再申請しました'));
                loadExpenses(expensePage.current);
            }).catch(() => { /* SES.apiが共通トーストを表示 */ });
    });
}

function deleteExpense(id) {
    Swal.fire({
        title: SES.i18n.t('common.delete', '削除'),
        text: SES.i18n.t('my.expense.delete.confirm', 'この下書きを削除しますか？'),
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#d33',
        confirmButtonText: SES.i18n.t('common.delete', '削除'),
        cancelButtonText: SES.i18n.t('common.cancel', 'キャンセル')
    }).then(result => {
        if (!result.isConfirmed) return;
        SES.api.delete('/api/my/expenses/' + encodeURIComponent(id))
            .then(() => {
                SES.toast.success(SES.i18n.t('common.deleted', '削除しました'));
                loadExpenses(expensePage.current);
            }).catch(() => { /* SES.apiが共通トーストを表示 */ });
    });
}
