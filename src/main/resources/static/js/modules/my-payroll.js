// 要員ポータル（マイ給与明細 / engineer-self-service-portal-v2 A2 / R2.1〜R2.3）
// 一覧に金額は出さない。詳細は再認証成功後にのみ取得・表示する。
// 注意: 金額・明細データをconsoleへ出力しない（DevTools誤用防止 / R2.2）。
document.addEventListener('DOMContentLoaded', () => {
    const now = new Date();
    document.getElementById('payrollMonth').value =
        now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
    document.getElementById('payrollMonth').addEventListener('change', loadStatements);
    document.getElementById('payrollType').addEventListener('change', loadStatements);
    document.getElementById('reauthSubmit').addEventListener('click', doReauth);
    document.getElementById('reauthPassword').addEventListener('keydown', e => {
        if (e.key === 'Enter') doReauth();
    });
    loadStatements();
});

// 再認証後に再取得する詳細リクエスト（モーダル内でクロージャ参照を避けるため保持する）
let reauthRetry = null;

function loadStatements() {
    hideMessage('payrollUnlinked');
    hideMessage('payrollError');
    const rowsEl = document.getElementById('payrollRows');
    const emptyEl = document.getElementById('payrollEmpty');
    emptyEl.classList.add('d-none');
    const yearMonth = document.getElementById('payrollMonth').value;
    if (!yearMonth) return;
    const [year, month] = yearMonth.split('-');
    const type = document.getElementById('payrollType').value;
    rowsEl.innerHTML = `<tr><td colspan="5" class="text-center text-muted py-3">${SES.escapeHtml(SES.i18n.t('my.payroll.loading', '読み込み中…'))}</td></tr>`;
    fetch(`/api/my/payroll/statements?year=${encodeURIComponent(year)}&month=${encodeURIComponent(month)}&type=${encodeURIComponent(type)}`)
        .then(res => res.json())
        .then(data => {
            if (data.code !== 200) { renderError(data); return; }
            const payload = data.data || {};
            const rows = payload.statements || [];
            if (payload.linked === false) {
                // 未連携: 0円と表示せず「未連携」メッセージを出す（design §6.1）。
                rowsEl.innerHTML = '';
                showMessage('payrollUnlinked', SES.i18n.t('my.payroll.unlinked', '給与連携が設定されていません。管理者またはHR担当者にお問い合わせください。'));
                return;
            }
            emptyEl.classList.toggle('d-none', rows.length > 0);
            rowsEl.innerHTML = rows.map((row, idx) => `
                <tr>
                    <td>${SES.escapeHtml(typeLabel(row.type))}</td>
                    <td>${SES.escapeHtml(String(row.month || ''))}</td>
                    <td>${SES.escapeHtml(row.payDate || '')}</td>
                    <td>${SES.escapeHtml(calcStatusLabel(row.calculationStatus))}</td>
                    <td class="text-end">
                        <button type="button" class="btn btn-sm btn-outline-primary" data-action="show-statement" data-index="${idx}">${SES.escapeHtml(SES.i18n.t('my.payroll.list.detail', '詳細'))}</button>
                    </td>
                </tr>`).join('');
            rowsEl.querySelectorAll('[data-action="show-statement"]').forEach(btn => {
                btn.addEventListener('click', () => showStatement(rows[Number(btn.dataset.index)]));
            });
        })
        .catch(() => renderError(null));
}

function showStatement(row) {
    const yearMonth = document.getElementById('payrollMonth').value;
    const [year, month] = yearMonth.split('-');
    const type = document.getElementById('payrollType').value;
    fetch(`/api/my/payroll/statement?year=${encodeURIComponent(year)}&month=${encodeURIComponent(month)}&type=${encodeURIComponent(type)}`)
        .then(res => res.json())
        .then(data => {
            if (data.code === 200) {
                renderStatement(data.data);
                return;
            }
            if (data.code === 403) {
                openReauth(() => showStatement(row));
                return;
            }
            Toast.error(data.message || SES.i18n.t('error.my.payroll.unavailable', '給与情報を取得できませんでした。'));
        })
        .catch(() => Toast.error(SES.i18n.t('error.my.payroll.unavailable', '給与情報を取得できませんでした。')));
}

function openReauth(retry) {
    reauthRetry = retry || null;
    const input = document.getElementById('reauthPassword');
    input.value = '';
    hideMessage('reauthError');
    bootstrap.Modal.getOrCreateInstance(document.getElementById('reauthModal')).show();
    input.focus();
}

function doReauth() {
    const btn = document.getElementById('reauthSubmit');
    const input = document.getElementById('reauthPassword');
    const errorEl = document.getElementById('reauthError');
    if (btn.disabled) return;
    btn.disabled = true;
    fetch('/api/my/payroll/reauthenticate', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify({ password: input.value })
    }).then(res => res.json()).then(data => {
        if (data.code === 200) {
            bootstrap.Modal.getOrCreateInstance(document.getElementById('reauthModal')).hide();
            Toast.success(SES.i18n.t('my.payroll.reauth.success', '再認証が完了しました'));
            const retry = reauthRetry;
            reauthRetry = null;
            if (retry) retry();
        } else {
            errorEl.textContent = data.message || SES.i18n.t('error.my.payroll.badPassword', 'パスワードが正しくありません');
            errorEl.classList.remove('d-none');
        }
    }).catch(() => {
        errorEl.textContent = SES.i18n.t('error.my.payroll.unavailable', '給与情報を取得できませんでした。');
        errorEl.classList.remove('d-none');
    }).finally(() => { btn.disabled = false; });
}

function renderStatement(s) {
    // null（計算中など）は0円と表示せず「-」にする。
    const fmt = v => (v == null ? '-' : '¥' + Number(v).toLocaleString('ja-JP'));
    const label = (key, fallback) => SES.escapeHtml(SES.i18n.t(key, fallback));
    document.getElementById('statementSummary').innerHTML = `
        <div class="row g-3">
            <div class="col">
                <div class="text-muted small">${label('my.payroll.detail.payDate', '支給日')}</div>
                <div>${SES.escapeHtml(s.payDate || '')}</div>
            </div>
            <div class="col">
                <div class="text-muted small">${label('my.payroll.detail.gross', '支給額')}</div>
                <div>${SES.escapeHtml(fmt(s.grossAmount))}</div>
            </div>
            <div class="col">
                <div class="text-muted small">${label('my.payroll.detail.deduction', '控除額')}</div>
                <div>${SES.escapeHtml(fmt(s.deductionAmount))}</div>
            </div>
            <div class="col">
                <div class="text-muted small">${label('my.payroll.detail.net', '差引支給額')}</div>
                <div class="fw-bold">${SES.escapeHtml(fmt(s.netAmount))}</div>
            </div>
            ${s.employerShareAmount != null
                ? `<div class="col"><div class="text-muted small">${label('my.payroll.detail.employerShare', '会社負担額')}</div><div>${SES.escapeHtml(fmt(s.employerShareAmount))}</div></div>`
                : ''}
        </div>`;
    const items = s.items || [];
    document.getElementById('statementItems').innerHTML = items.length
        ? items.map(it => `
            <tr>
                <td>${SES.escapeHtml(itemCategoryLabel(it.category))}</td>
                <td>${SES.escapeHtml(it.name || '')}</td>
                <td class="text-end">${SES.escapeHtml(fmt(it.amount))}</td>
            </tr>`).join('')
        : `<tr><td colspan="3" class="text-center text-muted">${SES.escapeHtml(SES.i18n.t('my.payroll.detail.noItems', '明細項目がありません'))}</td></tr>`;
    bootstrap.Modal.getOrCreateInstance(document.getElementById('statementModal')).show();
}

function typeLabel(type) {
    return type === 'bonus'
        ? SES.i18n.t('my.payroll.type.bonus', '賞与')
        : SES.i18n.t('my.payroll.type.salary', '給与');
}

function calcStatusLabel(status) {
    const fallback = status || '';
    return SES.i18n.t('my.payroll.calcStatus.' + fallback, fallback);
}

function itemCategoryLabel(category) {
    const fallback = category || '';
    return SES.i18n.t('my.payroll.category.' + fallback, fallback);
}

function renderError(data) {
    document.getElementById('payrollRows').innerHTML = '';
    hideMessage('payrollUnlinked');
    const message = data && data.message
        ? data.message
        : SES.i18n.t('error.my.payroll.unavailable', '給与情報を取得できませんでした。');
    showMessage('payrollError', message);
}

function hideMessage(id) {
    const el = document.getElementById(id);
    if (el) el.classList.add('d-none');
}

function showMessage(id, text) {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = text;
    el.classList.remove('d-none');
}
