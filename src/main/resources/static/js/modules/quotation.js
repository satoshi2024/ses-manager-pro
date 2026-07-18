// 見積管理（quotation-management / P4）
// サーバの状態機械 QuotationServiceImpl.ALLOWED のミラー。変更時は両方追随すること。
const QUOTATION_TRANSITIONS = {
    '下書き': ['提出済'],
    '提出済': ['受注', '失注'],
    '受注': [],
    '失注': []
};

document.addEventListener('DOMContentLoaded', () => {
    loadQuotations();
    document.getElementById('btnSearchQuotation').addEventListener('click', loadQuotations);
    document.getElementById('btnNewQuotation').addEventListener('click', () => openQuotationModal());
    document.getElementById('btnSaveQuotation').addEventListener('click', saveQuotation);

    // 提案カンバンからのプリセット（?fromProposal=ID）
    const params = new URLSearchParams(location.search);
    const fromProposal = params.get('fromProposal');
    if (fromProposal) {
        presetFromProposal(fromProposal);
    }

    // 顧客選択時に案件リストを絞り込み
    document.getElementById('quotationForm').customerId.addEventListener('change', function() {
        const customerId = this.value;
        const projectSelect = document.getElementById('quotationForm').projectId;
        if (customerId) {
            loadSelect(`/api/projects?current=1&size=1000&customerId=${customerId}`, projectSelect, 'id', r => r.projectName);
        } else {
            projectSelect.innerHTML = '<option value=""></option>';
        }
    });
});

function loadSelect(url, sel, valueField, labelFn, selected) {
    return fetch(url).then(res => res.json()).then(data => {
        if (data.code !== 200) return;
        const records = data.data.records || data.data || [];
        sel.innerHTML = '<option value=""></option>';
        records.forEach(r => {
            const opt = document.createElement('option');
            opt.value = r[valueField];
            opt.textContent = labelFn(r);
            if (selected && String(selected) === String(r[valueField])) opt.selected = true;
            sel.appendChild(opt);
        });
    });
}

function loadSelects(q) {
    q = q || {};
    const form = document.getElementById('quotationForm');
    
    const projectPromise = q.customerId 
        ? loadSelect(`/api/projects?current=1&size=1000&customerId=${q.customerId}`, form.projectId, 'id', r => r.projectName, q.projectId)
        : Promise.resolve(form.projectId.innerHTML = '<option value=""></option>');

    return Promise.all([
        loadSelect('/api/customers?current=1&size=1000', form.customerId, 'id', r => r.companyName, q.customerId),
        projectPromise,
        loadSelect('/api/engineers?current=1&size=1000', form.engineerId, 'id', r => r.fullName, q.engineerId)
    ]);
}

function loadQuotations() {
    const keyword = document.getElementById('quotationKeyword').value;
    const status = document.getElementById('quotationStatusFilter').value;
    let url = '/api/quotations?current=1&size=100';
    if (keyword) url += '&keyword=' + encodeURIComponent(keyword);
    if (status) url += '&status=' + encodeURIComponent(status);

    fetch(url).then(res => res.json()).then(data => {
        if (data.code !== 200) return;
        const tbody = document.querySelector('#quotationTable tbody');
        tbody.innerHTML = '';
        const todayStr = new Date().toISOString().split('T')[0];
        data.data.records.forEach(q => {
            const expired = q.validUntil && q.validUntil < todayStr;
            const expiredBadge = expired ? ` <span class="badge bg-secondary">${SES.i18n.t('quotation.badge.expired', '期限切れ')}</span>` : '';
            const transitions = QUOTATION_TRANSITIONS[q.status] || [];
            let actions = `<button class="btn btn-sm btn-outline-primary" onclick="openQuotationModalById(${q.id})">${SES.i18n.t('common.btn.edit', '編集')}</button>`;
            actions += ` <a href="/api/quotations/${q.id}/pdf" class="btn btn-sm btn-info">${SES.i18n.t('quotation.btn.pdf', 'PDF')}</a>`;
            transitions.forEach(t => {
                const label = t === '提出済' ? SES.i18n.t('quotation.btn.submit', '提出')
                    : t === '受注' ? SES.i18n.t('quotation.btn.win', '受注')
                    : t === '失注' ? SES.i18n.t('quotation.btn.lose', '失注') : t;
                actions += ` <button class="btn btn-sm btn-outline-success" onclick="changeQuotationStatus(${q.id}, '${t}')">${label}</button>`;
            });
            if (q.status === '受注') {
                actions += ` <button class="btn btn-sm btn-outline-primary" onclick="createQuotationDraft(${q.id})">${SES.i18n.t('quotation.btn.createDraft', '契約ドラフト生成')}</button>`;
            }
            if (q.status === '下書き') {
                actions += ` <button class="btn btn-sm btn-danger" onclick="deleteQuotation(${q.id})">${SES.i18n.t('common.btn.delete', '削除')}</button>`;
            }
            const tr = document.createElement('tr');
            tr.innerHTML = `
                <td>${SES.escapeHtml(q.quotationNo)}</td>
                <td>${q.customerId || ''}</td>
                <td>${SES.escapeHtml(q.title)}</td>
                <td class="text-right">￥${Number(q.unitPrice).toLocaleString()}</td>
                <td>${SES.i18n.t('quotation.status.' + q.status, q.status)}${expiredBadge}</td>
                <td>${q.validUntil || '-'}</td>
                <td>${actions}</td>`;
            tbody.appendChild(tr);
        });
    });
}

function openQuotationModalById(id) {
    fetch('/api/quotations/' + id).then(res => res.json()).then(data => {
        if(data.code === 200) openQuotationModal(data.data);
    });
}

function openQuotationModal(q) {
    const form = document.getElementById('quotationForm');
    form.reset();
    loadSelects(q).then(() => {
        form.id.value = q && q.id ? q.id : '';
        form.proposalId.value = q && q.proposalId ? q.proposalId : '';
        if (q) {
            form.title.value = q.title || '';
            form.unitPrice.value = q.unitPrice || '';
            form.settlementHoursMin.value = q.settlementHoursMin || '';
            form.settlementHoursMax.value = q.settlementHoursMax || '';
            form.validUntil.value = q.validUntil || '';
            form.remarks.value = q.remarks || '';
        }
    });
    new bootstrap.Modal(document.getElementById('quotationModal')).show();
}

function collectForm() {
    const form = document.getElementById('quotationForm');
    return {
        id: form.id.value || null,
        proposalId: form.proposalId.value || null,
        customerId: form.customerId.value || null,
        projectId: form.projectId.value || null,
        engineerId: form.engineerId.value || null,
        title: form.title.value,
        unitPrice: form.unitPrice.value ? Number(form.unitPrice.value) : null,
        settlementHoursMin: form.settlementHoursMin.value ? Number(form.settlementHoursMin.value) : null,
        settlementHoursMax: form.settlementHoursMax.value ? Number(form.settlementHoursMax.value) : null,
        validUntil: form.validUntil.value || null,
        remarks: form.remarks.value || null
    };
}

function saveQuotation() {
    const q = collectForm();
    const isUpdate = !!q.id;
    const url = isUpdate ? `/api/quotations/${q.id}` : '/api/quotations';
    fetch(url, {
        method: isUpdate ? 'PUT' : 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify(q)
    }).then(res => res.json()).then(data => {
        if (data.code === 200) {
            bootstrap.Modal.getInstance(document.getElementById('quotationModal')).hide();
            SES.toast.success(SES.i18n.t('common.msg.saveSuccess', '保存しました'));
            loadQuotations();
        } else {
            SES.toast.error(data.message);
        }
    }).catch(err => SES.toast.error(err.message));
}

function changeQuotationStatus(id, newStatus) {
    const proceed = (createDraft) => {
        fetch(`/api/quotations/${id}/status`, {
            method: 'PUT',
            headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
            body: JSON.stringify({ status: newStatus })
        }).then(res => res.json()).then(data => {
            if (data.code !== 200) { SES.toast.error(data.message); return; }
            if (newStatus === '受注' && createDraft) {
                createQuotationDraft(id);
            } else {
                loadQuotations();
            }
        }).catch(err => SES.toast.error(err.message));
    };

    if (newStatus === '受注') {
        Swal.fire({
            title: SES.i18n.t('quotation.btn.win', '受注'),
            html: `${SES.i18n.t('quotation.confirm.createDraftOnWin', '受注に伴い契約ドラフトを生成しますか？')}
                   <div class="form-check mt-2 d-inline-block">
                     <input class="form-check-input" type="checkbox" id="createDraftChecked" checked>
                   </div>`,
            showCancelButton: true,
            preConfirm: () => {
                const cb = document.getElementById('createDraftChecked');
                return cb ? cb.checked : false;
            }
        }).then(r => { if (r.isConfirmed) proceed(r.value); });
    } else {
        proceed(false);
    }
}

function createQuotationDraft(id) {
    fetch(`/api/quotations/${id}/create-draft`, {
        method: 'POST',
        headers: SES.csrf.header()
    }).then(res => res.json()).then(data => {
        if (data.code === 200) {
            SES.toast.success(SES.i18n.t('quotation.btn.createDraft', '契約ドラフト生成') + ' OK');
        } else {
            SES.toast.error(data.message);
        }
        loadQuotations();
    }).catch(err => SES.toast.error(err.message));
}

function deleteQuotation(id) {
    Swal.fire({
        title: SES.i18n.t('common.title.confirm', '確認'),
        icon: 'warning',
        showCancelButton: true
    }).then(r => {
        if (!r.isConfirmed) return;
        fetch(`/api/quotations/${id}`, { method: 'DELETE', headers: SES.csrf.header() })
            .then(res => res.json()).then(data => {
                if (data.code === 200) loadQuotations();
                else alert(data.message);
            });
    });
}

function presetFromProposal(proposalId) {
    fetch(`/api/proposals/${proposalId}`).then(res => res.json()).then(data => {
        if (data.code !== 200 || !data.data) { openQuotationModal(); return; }
        const p = data.data;
        openQuotationModal({
            proposalId: proposalId,
            engineerId: p.engineerId,
            projectId: p.projectId,
            customerId: p.customerId,
            unitPrice: p.proposedUnitPrice,
            title: p.projectName || ''
        });
    }).catch(() => openQuotationModal());
}
