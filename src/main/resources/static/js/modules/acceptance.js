// 月次検収（order-acceptance-workflow / B1）
// サーバの状態機械 AcceptanceServiceImpl のミラー。変更時は両方追随する。
const ACCEPTANCE_TRANSITIONS = {
    '未提出': ['提出済'],
    '提出済': ['検収済', '差戻し'],
    '検収済': [],
    '差戻し': ['提出済']
};

document.addEventListener('DOMContentLoaded', () => {
    const urlParams = new URLSearchParams(window.location.search);
    const paramMonth = urlParams.get('workMonth');
    const targetAcceptanceId = urlParams.get('acceptanceId') || urlParams.get('id');
    if (paramMonth && /^\d{4}-(?:0[1-9]|1[0-2])$/.test(paramMonth)) {
        document.getElementById('acceptanceWorkMonth').value = paramMonth;
    } else {
        const now = new Date();
        document.getElementById('acceptanceWorkMonth').value = now.toISOString().slice(0, 7);
    }
    loadAcceptances(1, targetAcceptanceId);
    document.getElementById('btnSearchAcceptance').addEventListener('click', () => loadAcceptances(1));
    loadSelectOptions('/api/customers/options', document.getElementById('acceptanceCustomerFilter'), 'id', r => r.name);
    loadSelectOptions('/api/engineers/options', document.getElementById('acceptanceEngineerFilter'), 'id', r => r.name);
});

function loadSelectOptions(url, sel, valueField, labelFn) {
    SES.api.get(url).then(records => {
        const list = records.records || records || [];
        sel.innerHTML = '<option value="">' + SES.i18n.t('common.all', 'すべて') + '</option>';
        list.forEach(r => {
            const opt = document.createElement('option');
            opt.value = r[valueField];
            opt.textContent = labelFn(r);
            sel.appendChild(opt);
        });
    });
}

function loadAcceptances(page, targetAcceptanceId) {
    const workMonth = document.getElementById('acceptanceWorkMonth').value;
    if (!workMonth) {
        SES.toast.error(SES.i18n.t('acceptance.workMonthRequired', '対象月を指定してください'));
        return;
    }
    const status = document.getElementById('acceptanceStatusFilter').value;
    const customerId = document.getElementById('acceptanceCustomerFilter').value;
    const engineerId = document.getElementById('acceptanceEngineerFilter').value;
    const pageSize = targetAcceptanceId ? 1000 : 50;
    const params = { current: page, size: pageSize, workMonth };
    if (status) params.status = status;
    if (customerId) params.customerId = customerId;
    if (engineerId) params.engineerId = engineerId;
    if (targetAcceptanceId) params.acceptanceId = targetAcceptanceId;
    SES.api.get('/api/acceptances', params).then(data => {
        const tbody = document.querySelector('#acceptanceTable tbody');
        tbody.innerHTML = '';
        (data.records || []).forEach(r => {
            const tr = document.createElement('tr');
            // 通知遷移先のDOM検証（tr[data-acceptance-id='...']）とE2E/ブラウザDemoのための識別子（R7-P2-04）
            tr.dataset.acceptanceId = String(r.id);
            if (targetAcceptanceId && String(r.id) === String(targetAcceptanceId)) {
                tr.classList.add('table-warning');
            }
            tr.innerHTML = `
                <td>${SES.escapeHtml(r.contractNo || '')}</td>
                <td>${SES.escapeHtml(r.engineerName || '')}</td>
                <td>${SES.escapeHtml(r.customerName || '')}</td>
                <td>${SES.escapeHtml(r.projectName || '')}</td>
                <td>${SES.escapeHtml(r.workMonth || '')}</td>
                <td>${acceptanceBadge(r.status)}</td>
                <td class="text-end">${r.hoursSnapshot != null ? Number(r.hoursSnapshot) : '-'}</td>
                <td class="text-end">${r.amountSnapshot != null ? Number(r.amountSnapshot).toLocaleString() : '-'}</td>
                <td>${r.submittedAt ? new Date(r.submittedAt).toLocaleString() : '-'}</td>
                <td>${actionButtons(r)}</td>
            `;
            tbody.appendChild(tr);
        });
        renderPagination(data, 'loadAcceptances');
        if (targetAcceptanceId) {
            const highlighted = tbody.querySelector('.table-warning');
            if (highlighted) {
                highlighted.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        }
    });
}

function acceptanceBadge(status) {
    const cls = status === '検収済' ? 'bg-success' : (status === '差戻し' ? 'bg-danger' : (status === '提出済' ? 'bg-warning text-dark' : 'bg-secondary'));
    return `<span class="badge ${cls}">${SES.escapeHtml(status || '')}</span>`;
}

function actionButtons(r) {
    let html = '';
    if (r.status === '未提出') {
        html += `<button type="button" class="btn btn-sm btn-primary btn-submit" data-contract="${r.contractId}" data-month="${r.workMonth}">${SES.i18n.t('acceptance.btn.submit', '提出')}</button>`;
    }
    if (r.status === '提出済') {
        html += `<button type="button" class="btn btn-sm btn-success btn-accept" data-id="${r.id}">${SES.i18n.t('acceptance.btn.accept', '検収')}</button>`;
        html += `<button type="button" class="btn btn-sm btn-danger btn-reject" data-id="${r.id}">${SES.i18n.t('acceptance.btn.reject', '差戻し')}</button>`;
    }
    if (r.status === '差戻し') {
        html += `<button type="button" class="btn btn-sm btn-primary btn-resubmit" data-id="${r.id}">${SES.i18n.t('acceptance.btn.resubmit', '再提出')}</button>`;
        if (r.rejectComment) {
            html += `<span class="text-danger small ms-1" title="${SES.escapeHtml(r.rejectComment)}">${SES.i18n.t('acceptance.table.rejectReason', '差戻し理由')}</span>`;
        }
    }
    if (r.status === '検収済') {
        html += `<button type="button" class="btn btn-sm btn-outline-primary btn-doc-upload" data-id="${r.id}">${SES.i18n.t('acceptance.btn.docUpload', '検収書登録')}</button>`;
        if (r.documentId) {
            html += `<a class="btn btn-sm btn-outline-secondary" href="/api/acceptances/${r.id}/document">${SES.i18n.t('acceptance.btn.docDownload', '検収書DL')}</a>`;
        }
        html += `<button type="button" class="btn btn-sm btn-outline-danger btn-cancel-approval" data-id="${r.id}">${SES.i18n.t('acceptance.btn.cancelApproval', '取消を承認申請')}</button>`;
    }
    return html;
}

document.addEventListener('click', (e) => {
    if (e.target.closest('.btn-submit')) {
        const btn = e.target.closest('.btn-submit');
        SES.api.post('/api/acceptances/submit', { contractId: btn.dataset.contract, workMonth: btn.dataset.month }).then(() => {
            SES.toast.success(SES.i18n.t('common.saved', '保存しました'));
            loadAcceptances(1);
        });
    } else if (e.target.closest('.btn-accept')) {
        const btn = e.target.closest('.btn-accept');
        SES.api.post(`/api/acceptances/${btn.dataset.id}/accept`, { customerContactId: null }).then(() => {
            SES.toast.success(SES.i18n.t('common.saved', '保存しました'));
            loadAcceptances(1);
        });
    } else if (e.target.closest('.btn-reject')) {
        const btn = e.target.closest('.btn-reject');
        const comment = window.prompt(SES.i18n.t('acceptance.rejectPrompt', '差戻し理由を入力してください'));
        if (comment === null) return;
        SES.api.post(`/api/acceptances/${btn.dataset.id}/reject`, { comment }).then(() => {
            SES.toast.success(SES.i18n.t('common.saved', '保存しました'));
            loadAcceptances(1);
        });
    } else if (e.target.closest('.btn-resubmit')) {
        const btn = e.target.closest('.btn-resubmit');
        SES.api.post(`/api/acceptances/${btn.dataset.id}/resubmit`, {}).then(() => {
            SES.toast.success(SES.i18n.t('common.saved', '保存しました'));
            loadAcceptances(1);
        });
    } else if (e.target.closest('.btn-cancel-approval')) {
        const btn = e.target.closest('.btn-cancel-approval');
        SES.api.post(`/api/acceptances/${btn.dataset.id}/cancel-approval`, { reason: '' }).then(() => {
            SES.toast.success(SES.i18n.t('salesOrder.approvalRequested', '承認申請しました'));
        });
    } else if (e.target.closest('.btn-doc-upload')) {
        const btn = e.target.closest('.btn-doc-upload');
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.pdf,.png,.jpg,.jpeg';
        input.onchange = () => {
            if (!input.files.length) return;
            const formData = new FormData();
            formData.append('file', input.files[0]);
            fetch(`/api/acceptances/${btn.dataset.id}/document`, {
                method: 'POST',
                headers: { 'X-XSRF-TOKEN': SES.csrf.token() },
                body: formData
            }).then(res => res.json()).then(result => {
                if (result.code !== 200) {
                    SES.toast.error(result.message || '処理に失敗しました。');
                    return;
                }
                SES.toast.success(SES.i18n.t('common.saved', '保存しました'));
                loadAcceptances(1);
            }).catch(() => SES.toast.error(SES.i18n.t('error.networkError', '通信エラー')));
        };
        input.click();
    }
});

function renderPagination(pageData, loadFuncName) {
    const paginationContainer = $('.card-footer');
    if (!pageData.total) {
        paginationContainer.html('<div class="text-muted small ps-2">' + SES.i18n.t('common.page.totalZero', 'データがありません') + '</div>');
        return;
    }
    const start = (pageData.current - 1) * pageData.size + 1;
    const end = Math.min(pageData.current * pageData.size, pageData.total);
    let html = `<div class="text-muted small ps-2">${SES.i18n.t('common.page.info', [pageData.total, start, end])}</div>
        <nav aria-label="Page navigation"><ul class="pagination pagination-sm mb-0 pe-2">`;
    if (pageData.current > 1) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current - 1})"><i class="bi bi-chevron-left"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-muted" href="javascript:void(0)" tabindex="-1" aria-disabled="true"><i class="bi bi-chevron-left"></i></a></li>`;
    }
    for (let i = 1; i <= pageData.pages; i++) {
        if (i === pageData.current) {
            html += `<li class="page-item active" aria-current="page"><a class="page-link bg-info border-info text-dark fw-bold" href="javascript:void(0)">${i}</a></li>`;
        } else if (i <= 3 || i >= pageData.pages - 2 || Math.abs(i - pageData.current) <= 1) {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${i})">${i}</a></li>`;
        } else if (i === 4 && pageData.current > 5) {
            html += `<li class="page-item disabled"><span class="page-link bg-dark border-secondary text-muted">…</span></li>`;
        }
    }
    if (pageData.current < pageData.pages) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current + 1})"><i class="bi bi-chevron-right"></i></a></li>`;
    }
    html += '</ul></nav>';
    paginationContainer.html(html);
}
