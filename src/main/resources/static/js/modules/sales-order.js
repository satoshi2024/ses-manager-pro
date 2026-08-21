// 注文管理（order-acceptance-workflow / A1）
// サーバの状態機械 SalesOrderServiceImpl.ALLOWED_STATUS_TRANSITIONS のミラー。変更時は両方追随する。
const SALES_ORDER_TRANSITIONS = {
    '下書き': ['受領確認', '取消'],
    '受領確認': ['注文請提出', '取消'],
    '注文請提出': ['契約化', '取消'],
    '契約化': ['完了'],
    '完了': [],
    '取消': []
};

document.addEventListener('DOMContentLoaded', () => {
    loadSalesOrders(1);
    document.getElementById('btnSearchSalesOrder').addEventListener('click', () => loadSalesOrders(1));
    document.getElementById('btnNewSalesOrder').addEventListener('click', () => openSalesOrderModal());
    document.getElementById('btnSaveSalesOrder').addEventListener('click', saveSalesOrder);
    document.getElementById('btnAddLine').addEventListener('click', addLineRow);
    document.getElementById('salesOrderForm').customerId.addEventListener('change', onCustomerChanged);
    document.getElementById('salesOrderForm').customerPoNo.addEventListener('blur', async () => {
        const form = document.getElementById('salesOrderForm');
        const customerId = form.customerId.value;
        const poNo = form.customerPoNo.value;
        const orderId = form.id.value;
        if (customerId && poNo) {
            const url = `/api/sales-orders/po-duplicate?customerId=${customerId}&customerPoNo=${encodeURIComponent(poNo)}` + (orderId ? `&excludeOrderId=${orderId}` : '');
            const res = await SES.api.get(url);
            const warn = document.getElementById('poWarningText');
            if (res && res.duplicate) {
                warn.textContent = SES.i18n.t('salesOrder.po.duplicateWarning', '同じPO番号の注文が既にあります');
                warn.style.display = 'block';
            } else {
                warn.style.display = 'none';
            }
        }
    });

    // 見積からの導線（?quotationId=ID）
    const params = new URLSearchParams(location.search);
    const quotationId = params.get('quotationId');
    if (quotationId) {
        presetFromQuotation(quotationId);
    }
});

function loadSelect(url, sel, valueField, labelFn, selected) {
    return SES.api.get(url).then(records => {
        const list = records.records || records || [];
        sel.innerHTML = '<option value=""></option>';
        list.forEach(r => {
            const opt = document.createElement('option');
            opt.value = r[valueField];
            opt.textContent = labelFn(r);
            if (selected && String(selected) === String(r[valueField])) opt.selected = true;
            sel.appendChild(opt);
        });
    });
}

function onCustomerChanged() {
    const customerId = document.getElementById('salesOrderForm').customerId.value;
    const projectSelects = document.querySelectorAll('#lineTable select[name="projectId"]');
    projectSelects.forEach(sel => {
        if (customerId) {
            loadSelect(`/api/projects/options?customerId=${customerId}`, sel, 'id', r => r.name);
        } else {
            sel.innerHTML = '<option value=""></option>';
        }
    });
    const contactSelect = document.getElementById('salesOrderForm').contactId;
    if (customerId) {
        loadSelect(`/api/customers/${customerId}/contacts`, contactSelect, 'id', r => r.name);
    } else {
        contactSelect.innerHTML = '<option value=""></option>';
    }
}

function addLineRow(line) {
    line = line || {};
    const tbody = document.querySelector('#lineTable tbody');
    const rowNo = tbody.children.length + 1;
    const label = key => SES.escapeHtml(`${SES.i18n.t(key, key)} ${rowNo}`);
    const tr = document.createElement('tr');
    tr.innerHTML = `
        <td>
            <input type="hidden" name="lineId" value="${line.id || ''}">
            <select class="form-select form-select-sm" name="engineerId" aria-label="${label('salesOrder.modal.line.engineer')}" required></select>
        </td>
        <td><select class="form-select form-select-sm" name="projectId" aria-label="${label('salesOrder.modal.line.project')}"></select></td>
        <td><input type="number" class="form-control form-control-sm" name="unitPrice" aria-label="${label('salesOrder.modal.line.unitPrice')}" value="${line.unitPrice || ''}" required></td>
        <td><input type="number" step="0.1" class="form-control form-control-sm" name="settlementMin" aria-label="${label('salesOrder.modal.line.settlementMin')}" value="${line.settlementMin || ''}"></td>
        <td><input type="number" step="0.1" class="form-control form-control-sm" name="settlementMax" aria-label="${label('salesOrder.modal.line.settlementMax')}" value="${line.settlementMax || ''}"></td>
        <td><input type="text" class="form-control form-control-sm" name="remarks" aria-label="${label('salesOrder.modal.line.remarks')}" value="${SES.escapeHtml(line.remarks || '')}"></td>
        <td><button type="button" class="btn btn-sm btn-outline-danger btn-remove-line" aria-label="${SES.escapeHtml(SES.i18n.t('common.btn.delete', '削除'))} ${rowNo}">×</button></td>
    `;
    tbody.appendChild(tr);
    tr.querySelector('.btn-remove-line').addEventListener('click', () => tr.remove());
    loadSelect('/api/engineers/options', tr.querySelector('select[name="engineerId"]'), 'id', r => r.name, line.engineerId);
    const customerId = document.getElementById('salesOrderForm').customerId.value;
    if (customerId) {
        loadSelect(`/api/projects/options?customerId=${customerId}`, tr.querySelector('select[name="projectId"]'), 'id', r => r.name, line.projectId);
    }
}

async function presetFromQuotation(quotationId) {
    try {
        const quotation = await SES.api.get(`/api/quotations/${quotationId}`);
        const form = document.getElementById('salesOrderForm');
        form.quotationId.value = quotationId;
        form.customerId.value = quotation.customerId;
        form.orderDate.value = SES.util.getLocalDateString();
        form.startDate.value = quotation.validUntil || '';
        // 顧客選択の連動を再実行してから明細を埋める
        await loadSelect('/api/customers/options', form.customerId, 'id', r => r.name, quotation.customerId);
        await loadSelect('/api/autocomplete/legal-entities', form.legalEntityId, 'id', r => r.name);
        onCustomerChanged();
        addLineRow({
            engineerId: quotation.engineerId,
            projectId: quotation.projectId,
            unitPrice: quotation.unitPrice,
            settlementMin: quotation.settlementHoursMin,
            settlementMax: quotation.settlementHoursMax
        });
        const modal = bootstrap.Modal.getOrCreateInstance(document.getElementById('salesOrderModal'));
        modal.show();
    } catch (e) {
        // エラーはSES.api側でトースト済み
    }
}

function openSalesOrderModal(id) {
    const form = document.getElementById('salesOrderForm');
    form.reset();
    document.getElementById('poWarningText').style.display = 'none';
    document.querySelector('#lineTable tbody').innerHTML = '';
    const title = document.querySelector('#salesOrderModal .modal-title');
    if (id) {
        title.textContent = SES.i18n.t('salesOrder.detail.title', '注文編集');
        SES.api.get(`/api/sales-orders/${id}`).then(detail => {
            form.id.value = detail.id;
            form.customerId.value = detail.customerId;
            form.contactId.value = detail.contactId || '';
            form.quotationId.value = detail.quotationId || '';
            form.legalEntityId.value = detail.legalEntityId || '';
            form.customerPoNo.value = detail.customerPoNo || '';
            form.orderDate.value = detail.orderDate || '';
            form.startDate.value = detail.startDate || '';
            form.endDate.value = detail.endDate || '';
            form.paymentTerms.value = detail.paymentTermsSnapshot || '';
            loadSelect('/api/customers/options', form.customerId, 'id', r => r.name, detail.customerId);
            loadSelect('/api/autocomplete/legal-entities', form.legalEntityId, 'id', r => r.name, detail.legalEntityId);
            onCustomerChanged();
            detail.lines.forEach(l => addLineRow(l));
        });
    } else {
        title.textContent = SES.i18n.t('salesOrder.btn.new', '注文作成');
        loadSelect('/api/customers/options', form.customerId, 'id', r => r.name);
        loadSelect('/api/autocomplete/legal-entities', form.legalEntityId, 'id', r => r.name);
        addLineRow();
    }
    bootstrap.Modal.getOrCreateInstance(document.getElementById('salesOrderModal')).show();
}

async function saveSalesOrder() {
    const form = document.getElementById('salesOrderForm');
    const lines = [];
    document.querySelectorAll('#lineTable tbody tr').forEach(tr => {
        lines.push({
            id: tr.querySelector('input[name="lineId"]').value || null,
            engineerId: tr.querySelector('select[name="engineerId"]').value,
            projectId: tr.querySelector('select[name="projectId"]').value || null,
            unitPrice: tr.querySelector('input[name="unitPrice"]').value,
            settlementMin: tr.querySelector('input[name="settlementMin"]').value || null,
            settlementMax: tr.querySelector('input[name="settlementMax"]').value || null,
            remarks: tr.querySelector('input[name="remarks"]').value || null
        });
    });
    const payload = {
        customerId: form.customerId.value,
        contactId: form.contactId.value || null,
        quotationId: form.quotationId.value || null,
        legalEntityId: form.legalEntityId.value || null,
        customerPoNo: form.customerPoNo.value || null,
        orderDate: form.orderDate.value,
        startDate: form.startDate.value || null,
        endDate: form.endDate.value || null,
        paymentTerms: form.paymentTerms.value || null,
        lines: lines
    };
    const id = form.id.value;
    let result;
    if (id) {
        result = await SES.api.put(`/api/sales-orders/${id}`, payload);
    } else {
        result = await SES.api.post('/api/sales-orders', payload);
    }
    if (result && result.poWarning) {
        const warn = document.getElementById('poWarningText');
        warn.textContent = SES.i18n.t('salesOrder.po.duplicateWarning', '同じPO番号の注文が既にあります');
        warn.style.display = 'block';
        // 警告は表示するが保存自体は成功（R2.4: PO重複は拒否しない）
    } else {
        const warn = document.getElementById('poWarningText');
        warn.style.display = 'none';
    }
    bootstrap.Modal.getInstance(document.getElementById('salesOrderModal')).hide();
    SES.toast.success(SES.i18n.t('common.saved', '保存しました'));
    loadSalesOrders(1);
}

function loadSalesOrders(page) {
    const keyword = document.getElementById('salesOrderKeyword').value;
    const status = document.getElementById('salesOrderStatusFilter').value;
    const dateFrom = document.getElementById('salesOrderDateFrom').value;
    const dateTo = document.getElementById('salesOrderDateTo').value;
    const params = { current: page, size: 20 };
    if (keyword) params.keyword = keyword;
    if (status) params.status = status;
    if (dateFrom) params.dateFrom = dateFrom;
    if (dateTo) params.dateTo = dateTo;
    SES.api.get('/api/sales-orders', params).then(data => {
        const tbody = document.querySelector('#salesOrderTable tbody');
        tbody.innerHTML = '';
        (data.records || []).forEach(r => {
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${SES.escapeHtml(r.orderNo || '')}</td>
                <td>${SES.escapeHtml(r.customerPoNo || '')}</td>
                <td>${SES.escapeHtml(r.customerName || '')}</td>
                <td>${SES.escapeHtml(r.orderDate || '')}</td>
                <td>${SES.escapeHtml((r.startDate || '') + ' 〜 ' + (r.endDate || ''))}</td>
                <td class="text-end">${r.totalAmountSnapshot ? Number(r.totalAmountSnapshot).toLocaleString() : '-'}</td>
                <td><span class="badge bg-info text-dark">${SES.escapeHtml(r.status || '')}</span></td>
                <td>
                    <button type="button" class="btn btn-sm btn-outline-secondary btn-open-detail" data-id="${r.id}">詳細</button>
                </td>
            `;
            tr.querySelector('.btn-open-detail').addEventListener('click', () => openSalesOrderDetail(r.id));
            tbody.appendChild(tr);
        });
        renderSalesOrderPagination(data, 'loadSalesOrders');
    });
}

function renderSalesOrderPagination(pageData, loadFuncName) {
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
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" aria-label="${SES.i18n.t('common.page.prev', '前へ')}" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current - 1})"><i class="bi bi-chevron-left" aria-hidden="true"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-muted" aria-label="${SES.i18n.t('common.page.prev', '前へ')}" href="javascript:void(0)" tabindex="-1" aria-disabled="true"><i class="bi bi-chevron-left" aria-hidden="true"></i></a></li>`;
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
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" aria-label="${SES.i18n.t('common.page.next', '次へ')}" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current + 1})"><i class="bi bi-chevron-right" aria-hidden="true"></i></a></li>`;
    }
    html += '</ul></nav>';
    paginationContainer.html(html);
}

function statusBadge(status) {
    return `<span class="badge bg-info text-dark">${SES.escapeHtml(status || '')}</span>`;
}

function openSalesOrderDetail(id) {
    const body = document.getElementById('salesOrderDetailBody');
    body.innerHTML = '<div class="text-muted">' + SES.i18n.t('common.loading', '読み込み中...') + '</div>';
    bootstrap.Modal.getOrCreateInstance(document.getElementById('salesOrderDetailModal')).show();
    SES.api.get(`/api/sales-orders/${id}`).then(d => {
        let html = `
            <div class="row g-2 mb-2">
                <div class="col-md-3"><strong>${SES.i18n.t('salesOrder.table.no', '注文番号')}:</strong> ${SES.escapeHtml(d.orderNo || '')}</div>
                <div class="col-md-3"><strong>PO:</strong> ${SES.escapeHtml(d.customerPoNo || '')}</div>
                <div class="col-md-3"><strong>${SES.i18n.t('salesOrder.table.customer', '顧客')}:</strong> ${SES.escapeHtml(d.customerName || '')}</div>
                <div class="col-md-3"><strong>${SES.i18n.t('salesOrder.table.status', '状態')}:</strong> ${statusBadge(d.status)}</div>
                <div class="col-md-3"><strong>${SES.i18n.t('salesOrder.modal.orderDate', '注文日')}:</strong> ${SES.escapeHtml(d.orderDate || '')}</div>
                <div class="col-md-3"><strong>${SES.i18n.t('salesOrder.modal.paymentTerms', '支払条件')}:</strong> ${SES.escapeHtml(d.paymentTermsSnapshot || '-')}</div>
                <div class="col-md-3"><strong>${SES.i18n.t('salesOrder.modal.quotation', '生成元見積')}:</strong> ${SES.escapeHtml(d.quotationNo || '-')}</div>
                <div class="col-md-3"><strong>${SES.i18n.t('salesOrder.table.amount', '金額')}:</strong> ${d.totalAmountSnapshot ? Number(d.totalAmountSnapshot).toLocaleString() : '-'}</div>
            </div>
            <h6>${SES.i18n.t('salesOrder.modal.lines', '注文明細')}</h6>
            <table class="table table-sm table-bordered">
                <thead><tr><th>#</th><th>${SES.i18n.t('salesOrder.modal.line.engineer', '要員')}</th><th>${SES.i18n.t('salesOrder.modal.line.project', '案件')}</th><th>${SES.i18n.t('salesOrder.modal.line.unitPrice', '単価')}</th><th>${SES.i18n.t('salesOrder.table.amount', '金額')}</th><th>${SES.i18n.t('contract.contractNo', '契約')}</th></tr></thead>
                <tbody>`;
        (d.lines || []).forEach(l => {
            html += `<tr><td>${l.lineNo}</td><td>${SES.escapeHtml(l.engineerName || '')}</td><td>${SES.escapeHtml(l.projectName || '')}</td>
                <td class="text-end">${l.unitPrice ? Number(l.unitPrice).toLocaleString() : '-'}</td>
                <td class="text-end">${l.amount ? Number(l.amount).toLocaleString() : '-'}</td>
                <td>${l.contractNo ? `<a href="/contract/list?openId=${l.contractId}">${SES.escapeHtml(l.contractNo)}</a>` : '-'}</td></tr>`;
        });
        html += `</tbody></table>`;
        if (d.diffs && d.diffs.length > 0) {
            html += `<div class="alert alert-warning"><h6>${SES.i18n.t('salesOrder.detail.diffTitle', '見積/契約との条件差分')}</h6><ul>`;
            d.diffs.forEach(diff => {
                html += `<li>${SES.escapeHtml(diff.label)}: ${SES.escapeHtml(diff.before)} → ${SES.escapeHtml(diff.after)} (${SES.escapeHtml(diff.target)})</li>`;
            });
            html += `</ul>
                <button type="button" class="btn btn-sm btn-warning" id="btnConditionDiffApproval">${SES.i18n.t('salesOrder.btn.conditionDiffApproval', '差分を承認申請')}</button></div>`;
        }
        html += `<div class="d-flex flex-wrap gap-2 mt-2">`;
        html += `<button type="button" class="btn btn-sm btn-outline-secondary btn-edit-order" data-id="${d.id}">${SES.i18n.t('common.btn.edit', '編集')}</button>`;
        if (d.status === '下書き') {
            html += `<button type="button" class="btn btn-sm btn-primary btn-status-change" data-status="受領確認">${SES.i18n.t('salesOrder.btn.receive', '受領確認')}</button>`;
            html += `<button type="button" class="btn btn-sm btn-danger btn-status-change" data-status="取消">${SES.i18n.t('salesOrder.btn.cancel', '取消')}</button>`;
        }
        if (d.status === '受領確認') {
            html += `<button type="button" class="btn btn-sm btn-primary btn-upload-source">${SES.i18n.t('salesOrder.btn.uploadSource', '原本アップロード')}</button>`;
            html += `<button type="button" class="btn btn-sm btn-success btn-ack-pdf">${SES.i18n.t('salesOrder.btn.ackPdf', '注文請書PDF発行')}</button>`;
            html += `<button type="button" class="btn btn-sm btn-danger btn-status-change" data-status="取消">${SES.i18n.t('salesOrder.btn.cancel', '取消')}</button>`;
        }
        if (d.status === '注文請提出') {
            html += `<button type="button" class="btn btn-sm btn-success btn-create-contracts">${SES.i18n.t('salesOrder.btn.contracts', '契約化')}</button>`;
            html += `<button type="button" class="btn btn-sm btn-danger btn-cancel-approval">${SES.i18n.t('salesOrder.btn.cancelApproval', '取消を承認申請')}</button>`;
        }
        if (d.status === '契約化') {
            html += `<button type="button" class="btn btn-sm btn-success btn-status-change" data-status="完了">${SES.i18n.t('salesOrder.btn.complete', '完了')}</button>`;
            html += `<button type="button" class="btn btn-sm btn-danger btn-cancel-approval">${SES.i18n.t('salesOrder.btn.cancelApproval', '取消を承認申請')}</button>`;
        }
        if (d.sourceDocumentId) {
            html += `<a class="btn btn-sm btn-outline-secondary" href="/api/sales-orders/${d.id}/documents/${d.sourceDocumentId}/download">${SES.i18n.t('salesOrder.btn.sourceDoc', '原本DL')}</a>`;
        }
        if (d.acknowledgementDocumentId) {
            html += `<a class="btn btn-sm btn-outline-secondary" href="/api/sales-orders/${d.id}/documents/${d.acknowledgementDocumentId}/download">${SES.i18n.t('salesOrder.btn.ackDoc', '注文請DL')}</a>`;
        }
        html += `</div>`;
        body.innerHTML = html;
        body.querySelector('.btn-edit-order').addEventListener('click', () => openSalesOrderModal(d.id));
        const statusButtons = body.querySelectorAll('.btn-status-change');
        statusButtons.forEach(btn => btn.addEventListener('click', () => changeOrderStatus(d.id, btn.dataset.status)));
        const uploadBtn = body.querySelector('.btn-upload-source');
        if (uploadBtn) uploadBtn.addEventListener('click', () => uploadSourceDocument(d.id));
        const ackBtn = body.querySelector('.btn-ack-pdf');
        if (ackBtn) ackBtn.addEventListener('click', () => generateAckPdf(d.id));
        const contractBtn = body.querySelector('.btn-create-contracts');
        if (contractBtn) contractBtn.addEventListener('click', () => createOrderContracts(d.id));
        const cancelApprovalBtn = body.querySelector('.btn-cancel-approval');
        if (cancelApprovalBtn) cancelApprovalBtn.addEventListener('click', () => requestCancelApproval(d.id));
        const diffApprovalBtn = body.querySelector('#btnConditionDiffApproval');
        if (diffApprovalBtn) diffApprovalBtn.addEventListener('click', () => requestConditionDiffApproval(d.id));
    });
}

function changeOrderStatus(id, status) {
    SES.api.post(`/api/sales-orders/${id}/status`, { status }).then(() => {
        SES.toast.success(SES.i18n.t('common.saved', '保存しました'));
        openSalesOrderDetail(id);
        loadSalesOrders(1);
    });
}

function uploadSourceDocument(id) {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.pdf,.png,.jpg,.jpeg';
    input.onchange = () => {
        if (!input.files.length) return;
        const file = input.files[0];
        const formData = new FormData();
        formData.append('file', file);
        fetch(`/api/sales-orders/${id}/source-document`, {
            method: 'POST',
            headers: { 'X-XSRF-TOKEN': SES.csrf.token() },
            body: formData
        }).then(res => res.json()).then(result => {
            if (result.code !== 200) {
                SES.toast.error(result.message || '処理に失敗しました。');
                return;
            }
            SES.toast.success(SES.i18n.t('common.saved', '保存しました'));
            openSalesOrderDetail(id);
        }).catch(() => SES.toast.error(SES.i18n.t('error.networkError', '通信エラー')));
    };
    input.click();
}

function generateAckPdf(id) {
    fetch(`/api/sales-orders/${id}/acknowledgement-pdf`, {
        method: 'POST',
        headers: { 'X-XSRF-TOKEN': SES.csrf.token() }
    }).then(res => {
        if (!res.ok) return res.json().then(r => { throw new Error(r.message || 'PDF生成失敗'); });
        return res.blob();
    }).then(blob => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `order_ack_${id}.pdf`;
        a.click();
        URL.revokeObjectURL(url);
        SES.toast.success(SES.i18n.t('common.saved', '保存しました'));
        openSalesOrderDetail(id);
        loadSalesOrders(1);
    }).catch(e => SES.toast.error(e.message || SES.i18n.t('error.networkError', '通信エラー')));
}

function createOrderContracts(id) {
    SES.api.post(`/api/sales-orders/${id}/contract-drafts`, {}).then(() => {
        SES.toast.success(SES.i18n.t('common.saved', '保存しました'));
        openSalesOrderDetail(id);
        loadSalesOrders(1);
    });
}

function requestCancelApproval(id) {
    SES.api.post(`/api/sales-orders/${id}/cancel-approval`, { reason: '' }).then(() => {
        SES.toast.success(SES.i18n.t('salesOrder.approvalRequested', '承認申請しました'));
    });
}

function requestConditionDiffApproval(id) {
    SES.api.post(`/api/sales-orders/${id}/condition-diff-approval`, { reason: '' }).then(() => {
        SES.toast.success(SES.i18n.t('salesOrder.approvalRequested', '承認申請しました'));
    });
}
