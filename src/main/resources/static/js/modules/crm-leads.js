$(function () { loadLeads(); });

function loadLeads() {
    $.get('/api/crm/leads', { status: $('#lead-status-filter').val(), companyName: $('#lead-company-filter').val() }, function (res) {
        const rows = (res.data || []).map(lead => `<tr><td>${SES.escapeHtml(lead.companyName || '')}</td><td>${SES.escapeHtml(lead.contactName || '-')}</td><td>${SES.escapeHtml(lead.source || '-')}</td><td><span class="badge bg-secondary">${SES.escapeHtml(lead.status || '')}</span></td><td class="text-end">${lead.status === '転換済' ? '<span class="text-muted">転換済</span>' : `<button class="btn btn-sm btn-outline-info me-1" onclick="editLead(${lead.id})">編集</button><button class="btn btn-sm btn-info" onclick="convertLead(${lead.id}, ${lead.version || 1})">顧客・商機へ転換</button>`}</td></tr>`).join('');
        $('#lead-table-body').html(rows || '<tr><td colspan="5" class="text-center text-muted py-4">該当するリードはありません</td></tr>');
    }).fail(() => Toast.error('リード一覧の取得に失敗しました'));
}

function openLeadModal(lead) {
    $('#lead-form')[0].reset(); $('#lead-id').val(lead ? lead.id : ''); $('#lead-version').val(lead ? lead.version : '');
    if (lead) { $('#lead-company').val(lead.companyName); $('#lead-contact-name').val(lead.contactName); $('#lead-email').val(lead.contactEmail); $('#lead-phone').val(lead.contactPhone); $('#lead-source').val(lead.source); }
    $('#lead-duplicate-warning').addClass('d-none'); bootstrap.Modal.getOrCreateInstance(document.getElementById('leadModal')).show();
}

function editLead(id) { $.get(`/api/crm/leads/${id}`, res => { if (res.code === 200) openLeadModal(res.data); }); }

function saveLead() {
    const id = $('#lead-id').val();
    const data = { companyName: $('#lead-company').val(), contactName: $('#lead-contact-name').val(), contactEmail: $('#lead-email').val(), contactPhone: $('#lead-phone').val(), source: $('#lead-source').val(), version: $('#lead-version').val() ? Number($('#lead-version').val()) : null };
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
