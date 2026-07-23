// 見積管理（quotation-management / P4）
// サーバの状態機械 QuotationServiceImpl.ALLOWED のミラー。変更時は両方追随すること。
const QUOTATION_TRANSITIONS = {
    '下書き': ['提出済'],
    '提出済': ['受注', '失注'],
    '受注': [],
    '失注': []
};

document.addEventListener('DOMContentLoaded', () => {
    loadQuotations(1);
    document.getElementById('btnSearchQuotation').addEventListener('click', () => loadQuotations(1));
    document.getElementById('btnNewQuotation').addEventListener('click', () => openQuotationModal());
    document.getElementById('btnSaveQuotation').addEventListener('click', saveQuotation);

    // 提案カンバンからのプリセット（?fromProposal=ID）
    const params = new URLSearchParams(location.search);
    const fromProposal = params.get('fromProposal');
    if (fromProposal) {
        presetFromProposal(fromProposal);
    }

    // 契約からの導線（?openId=ID）: scope検証済み詳細APIで見積を開く（担当外は404 / R3R-28）。
    const openId = params.get('openId');
    if (openId) {
        openQuotationModalById(openId);
    }

    // 顧客選択時に案件リストを絞り込み
    document.getElementById('quotationForm').customerId.addEventListener('change', function() {
        const customerId = this.value;
        const projectSelect = document.getElementById('quotationForm').projectId;
        if (customerId) {
            loadSelect(`/api/projects/options?customerId=${customerId}`, projectSelect, 'id', r => r.name);
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
        ? loadSelect(`/api/projects/options?customerId=${q.customerId}`, form.projectId, 'id', r => r.name, q.projectId)
        : Promise.resolve(form.projectId.innerHTML = '<option value=""></option>');

    return Promise.all([
        loadSelect('/api/customers/options', form.customerId, 'id', r => r.name, q.customerId),
        projectPromise,
        loadSelect('/api/engineers/options', form.engineerId, 'id', r => r.name, q.engineerId)
    ]);
}

function renderPagination(pageData, loadFuncName) {
    const paginationContainer = $('.card-footer');
    if (pageData.total === 0) {
        paginationContainer.html(`<div class="text-muted small ps-2">${SES.i18n.t('common.page.totalZero', 'データがありません')}</div>`);
        return;
    }
    
    const start = (pageData.current - 1) * pageData.size + 1;
    const end = Math.min(pageData.current * pageData.size, pageData.total);
    
    let html = `
        <div class="text-muted small ps-2">
            ${SES.i18n.t('common.page.info', [pageData.total, start, end], `全${pageData.total}件中 ${start}〜${end}件`)}
        </div>
        <nav aria-label="Page navigation">
            <ul class="pagination pagination-sm mb-0 pe-2">
    `;
    
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
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light disabled border-0"><span class="bg-transparent border-0 text-muted">...</span></a></li>`;
        } else if (i === pageData.pages - 3 && pageData.current < pageData.pages - 4) {
             html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light disabled border-0"><span class="bg-transparent border-0 text-muted">...</span></a></li>`;
        }
    }
    
    if (pageData.current < pageData.pages) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current + 1})"><i class="bi bi-chevron-right"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)"><i class="bi bi-chevron-right"></i></a></li>`;
    }
    
    html += `</ul></nav>`;
    paginationContainer.html(html);
}

function loadQuotations(page = 1) {
    const keyword = document.getElementById('quotationKeyword').value;
    const status = document.getElementById('quotationStatusFilter').value;
    let url = `/api/quotations?current=${page}&size=10`;
    if (keyword) url += '&keyword=' + encodeURIComponent(keyword);
    if (status) url += '&status=' + encodeURIComponent(status);

    fetch(url).then(res => res.json()).then(data => {
        if (data.code !== 200) return;
        const tbody = document.querySelector('#quotationTable tbody');
        tbody.innerHTML = '';
        
        const d = new Date();
        const todayStr = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
        
        const records = data.data.records || data.data || [];
        records.forEach(q => {
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
                <td>${SES.escapeHtml(q.customerName || q.customerId || '')}</td>
                <td>${SES.escapeHtml(q.title)}</td>
                <td class="text-right">￥${Number(q.unitPrice).toLocaleString()}</td>
                <td>${SES.i18n.t('quotation.status.' + q.status, q.status)}${expiredBadge}</td>
                <td>${q.validUntil || '-'}</td>
                <td>${actions}</td>`;
            tbody.appendChild(tr);
        });
        
        if (data.data.total !== undefined) {
            renderPagination(data.data, 'loadQuotations');
        }
    });
}

function openQuotationModalById(id) {
    fetch('/api/quotations/' + encodeURIComponent(id)).then(res => res.json()).then(data => {
        if(data.code === 200) openQuotationModal(data.data);
        else SES.toast.error(data.message);
    }).catch(err => SES.toast.error(err.message));
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

        const isTerminal = q && (q.status === '受注' || q.status === '失注');
        const appendSection = document.getElementById('appendRemarksSection');
        const appendBtn = document.getElementById('btnAppendRemark');
        const appendText = document.getElementById('additionalRemarks');
        const btnSave = document.getElementById('btnSaveQuotation');

        Array.from(form.elements).forEach(el => {
            if (el.tagName !== 'BUTTON' && el.id !== 'additionalRemarks') {
                if (el.tagName === 'SELECT') {
                    el.disabled = isTerminal;
                } else {
                    el.readOnly = isTerminal;
                }
            }
        });

        if (isTerminal) {
            btnSave.style.display = 'none';
            if (appendSection) {
                appendSection.style.display = 'block';
                appendText.value = '';
                appendBtn.onclick = () => {
                    const txt = appendText.value.trim();
                    if(!txt) {
                        if(window.SES && SES.toast) SES.toast.error('追記内容を入力してください');
                        else alert('追記内容を入力してください');
                        return;
                    }
                    fetch(`/api/quotations/${q.id}/remarks`, {
                        method: 'POST',
                        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
                        body: JSON.stringify({ additionalRemark: txt })
                    }).then(res => res.json()).then(resData => {
                        if (resData.code === 200) {
                            if(window.SES && SES.toast) SES.toast.success('備考を追記しました');
                            bootstrap.Modal.getInstance(document.getElementById('quotationModal')).hide();
                            loadQuotations(1);
                        } else {
                            if(window.SES && SES.toast) SES.toast.error(resData.message);
                            else alert(resData.message);
                        }
                    });
                };
            }
        } else {
            btnSave.style.display = 'inline-block';
            if (appendSection) appendSection.style.display = 'none';
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
            loadQuotations(1);
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
            // 状態遷移成功直後に必ず一覧をreloadし、受注状態と再試行ボタンを即時反映する（R3R-27）。
            loadQuotations(1);
            if (newStatus === '受注' && createDraft) {
                createQuotationDraft(id);
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
        loadQuotations(1);
    }).catch(err => {
        // draft生成が通信失敗しても一覧を再読込し、受注状態と再試行ボタンを表示する（R3R-27）。
        SES.toast.error(err.message);
        loadQuotations(1);
    });
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
                if (data.code === 200) loadQuotations(1);
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
