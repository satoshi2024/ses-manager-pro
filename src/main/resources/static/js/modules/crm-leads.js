$(function () { loadAssignees(); loadLeads(); });

function loadAssignees() {
    $.get('/api/crm/leads/assignees', res => {
        if (res.code !== 200) return;
        $('#lead-owner').html('<option value="">未割当</option>' + (res.data || []).map(u => `<option value="${u.value}">${SES.escapeHtml(u.label || '')}</option>`).join(''));
        $('#opportunity-owner').html('<option value="">未割当</option>' + (res.data || []).map(u => `<option value="${u.value}">${SES.escapeHtml(u.label || '')}</option>`).join(''));
    });
}

let leadPage = 1;

function loadLeads(page = 1) {
    leadPage = page;
    $.get('/api/crm/leads', { status: $('#lead-status-filter').val(), companyName: $('#lead-company-filter').val(), current: page, size: 20 }, function (res) {
        const payload = res.data || {}, rows = payload.records || payload;
        const html = (rows || []).map(lead => `<tr><td>${SES.escapeHtml(lead.companyName || '')}</td><td>${SES.escapeHtml(lead.contactName || '-')}</td><td>${SES.escapeHtml(lead.source || '-')}</td><td><span class="badge bg-secondary">${SES.escapeHtml(lead.status || '')}</span></td><td class="text-end"><div class="d-flex flex-wrap justify-content-end align-items-center gap-1">${lead.status === '転換済' ? '<span class="text-muted">転換済</span>' : `<button class="btn btn-sm btn-outline-info" onclick="editLead(${lead.id})">編集</button><button class="btn btn-sm btn-info" onclick="convertLead(${lead.id}, ${lead.version || 1})">顧客・商機へ転換</button>`}</div></td></tr>`).join('');
        $('#lead-table-body').html(html || '<tr><td colspan="5" class="text-center text-muted py-4">該当するリードはありません</td></tr>');
        renderLeadPagination(payload);
    }).fail(() => Toast.error('リード一覧の取得に失敗しました'));
}

function renderLeadPagination(page) {
    if (!page || page.pages === undefined) { $('#lead-pagination').empty(); return; }
    const total = page.total || 0;
    const start = total === 0 ? 0 : (page.current - 1) * page.size + 1;
    const end = Math.min(page.current * page.size, total);
    let html = `<div class="text-muted small ps-2">${SES.escapeHtml(SES.i18n.t('common.page.info', [total, start, end]))}</div>`;
    if (page.pages > 1) {
        html += `<nav aria-label="Page navigation"><ul class="pagination pagination-sm mb-0 pe-2">`;
        html += `<li class="page-item ${page.current <= 1 ? 'disabled' : ''}"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="loadLeads(${page.current - 1})">‹</a></li>`;
        for (let i = 1; i <= page.pages; i++) {
            if (i === page.current) {
                html += `<li class="page-item active" aria-current="page"><a class="page-link bg-primary border-primary" href="javascript:void(0)">${i}</a></li>`;
            } else {
                html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="loadLeads(${i})">${i}</a></li>`;
            }
        }
        html += `<li class="page-item ${page.current >= page.pages ? 'disabled' : ''}"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="loadLeads(${page.current + 1})">›</a></li>`;
        html += `</ul></nav>`;
    }
    $('#lead-pagination').html(html);
}

function openLeadModal(lead) {
    $('#lead-form')[0].reset();
    if (lead) { $('#lead-company').val(lead.companyName); $('#lead-contact-name').val(lead.contactName); $('#lead-email').val(lead.contactEmail); $('#lead-phone').val(lead.contactPhone); $('#lead-source').val(lead.source); $('#lead-source-cost').val(lead.sourceCost); $('#lead-status').val(lead.status); $('#lead-owner').val(lead.ownerUserId || ''); }
    $('#lead-duplicate-warning').addClass('d-none'); bootstrap.Modal.getOrCreateInstance(document.getElementById('leadModal')).show();
}

function editLead(id) { $.get(`/api/crm/leads/${id}`, res => { if (res.code === 200) openLeadModal(res.data); }); }

function saveLead() {
    const id = $('#lead-id').val();
    const data = { companyName: $('#lead-company').val(), contactName: $('#lead-contact-name').val(), contactEmail: $('#lead-email').val(), contactPhone: $('#lead-phone').val(), source: $('#lead-source').val(), sourceCost: $('#lead-source-cost').val() ? Number($('#lead-source-cost').val()) : null, ownerUserId: $('#lead-owner').val() ? Number($('#lead-owner').val()) : null, status: $('#lead-status').val(), version: $('#lead-version').val() ? Number($('#lead-version').val()) : null };
    const save = () => $.ajax({ url: id ? `/api/crm/leads/${id}` : '/api/crm/leads', method: id ? 'PUT' : 'POST', contentType: 'application/json', data: JSON.stringify(data) }).done(res => { if (res.code === 200) { Toast.success('リードを保存しました'); bootstrap.Modal.getInstance(document.getElementById('leadModal')).hide(); loadLeads(); } else Toast.error(res.message); }).fail(xhr => Toast.error((xhr.responseJSON || {}).message || '保存に失敗しました'));
    const checkAndSave = (res) => {
        if (res.code === 200 && (res.data || []).length) {
            $('#lead-duplicate-warning').removeClass('d-none');
            Swal.fire({ title: '重複候補があります', text: '自動統合は行いません。別リードとして保存しますか？', icon: 'warning', showCancelButton: true, confirmButtonText: '保存', cancelButtonText: 'キャンセル' }).then(result => { if (result.isConfirmed) save(); });
        } else save();
    };
    $.get('/api/crm/leads/duplicates', data, checkAndSave).fail(save);
}

function convertLead(id, version) {
    Swal.fire({ title: '顧客・商機へ転換しますか？', text: '重複候補があっても自動統合は行いません。', icon: 'question', showCancelButton: true, confirmButtonText: '転換', cancelButtonText: 'キャンセル' }).then(result => {
        if (!result.isConfirmed) return;
        $.ajax({ url: `/api/crm/leads/${id}/convert`, method: 'POST', contentType: 'application/json', data: JSON.stringify({ version }) }).done(res => { if (res.code === 200) { Toast.success('顧客・商機へ転換しました'); loadLeads(); } else Toast.error(res.message); }).fail(xhr => Toast.error((xhr.responseJSON || {}).message || '転換に失敗しました'));
    });
}
