const CRM_OPPORTUNITY_STAGES = ['見込', '要件確認', '提案準備', '見積提出', '交渉', '受注', '失注'];
$(function () { loadAssignees(); loadOpportunities(); initOpportunityDragDrop(); });

function loadAssignees() {
    $.get('/api/crm/leads/assignees', function(res) {
        if (res.code !== 200) return;
        $('#opportunity-owner').html('<option value="">未割当</option>' + (res.data || []).map(u => `<option value="${u.value}">${SES.escapeHtml(u.label || '')}</option>`).join(''));
    });
}

let opportunityPage = 1;

function loadOpportunities(page = opportunityPage) {
    opportunityPage = page;
    const query = new URLSearchParams(window.location.search);
    const params = {};
    if (query.get('stage')) params.stage = query.get('stage');
    if (query.get('ownerUserId')) params.ownerUserId = query.get('ownerUserId');
    params.current = page;
    params.size = 50;
    $.get('/api/crm/opportunities', params, function (res) { if (res.code === 200) { const payload = res.data || {}, rows = payload.records || payload; window.crmOpportunities = rows; $('#opportunity-board [data-stage] .crm-kanban-list').empty(); window.crmOpportunities.forEach(renderOpportunityCard); updateOpportunityCounts(); renderOpportunityPagination(payload); } }).fail(() => Toast.error('商機一覧の取得に失敗しました'));
}

function renderOpportunityPagination(page) {
    if (!page || page.pages === undefined || page.pages <= 1) return;
    $('#opportunity-pagination').html(`<button class="btn btn-sm btn-outline-secondary" ${page.current <= 1 ? 'disabled' : ''} onclick="loadOpportunities(${page.current - 1})">‹</button><span class="mx-2 text-muted">${page.current} / ${page.pages}</span><button class="btn btn-sm btn-outline-secondary" ${page.current >= page.pages ? 'disabled' : ''} onclick="loadOpportunities(${page.current + 1})">›</button>`);
}

function renderOpportunityCard(item) {
    const list = $(`#opportunity-board .crm-kanban-list[data-stage="${CSS.escape(item.stage)}"]`); if (!list.length) return;
    list.append(`<div class="crm-opportunity-card card bg-dark border-secondary mb-2 p-2" draggable="true" data-id="${item.id}" data-stage="${SES.escapeHtml(item.stage)}" data-version="${item.version || 1}" tabindex="0" role="button" onclick="editOpportunity(${item.id})" onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault();editOpportunity(${item.id})}"><div class="fw-bold text-light">${SES.escapeHtml(item.title || '')}</div><div class="small text-muted">${SES.escapeHtml(item.customerName || '')}</div><div class="small text-info mt-1">${item.unitPrice == null ? '-' : `¥${Number(item.unitPrice).toLocaleString()}`} · ${item.probability == null ? '-' : item.probability + '%'}</div></div>`);
}

function initOpportunityDragDrop() {
    $(document).on('dragstart', '.crm-opportunity-card', function (event) { this.classList.add('crm-dragging'); event.originalEvent.dataTransfer.setData('text/plain', this.dataset.id); this.dataset.originStage = this.dataset.stage; this.dataset.originIndex = $(this).index(); });
    $(document).on('dragend', '.crm-opportunity-card', function () { this.classList.remove('crm-dragging'); });
    $(document).on('dragover', '.crm-kanban-list', function (event) { event.preventDefault(); });
    $(document).on('drop', '.crm-kanban-list', function (event) { event.preventDefault(); const card = document.querySelector('.crm-opportunity-card.crm-dragging'); if (!card) return; const from = card.dataset.originStage; const to = this.dataset.stage; if (from === to) return; this.appendChild(card); changeOpportunityStage(card, from, to); });
}

function changeOpportunityStage(card, from, to) {
    const oldVersion = Number(card.dataset.version || 1);
    $.ajax({ url: `/api/crm/opportunities/${card.dataset.id}/stage`, method: 'PUT', contentType: 'application/json', data: JSON.stringify({ stage: to, version: oldVersion, lostReason: to === '失注' ? window.prompt('失注理由を入力してください') : null }) }).done(res => { if (res.code === 200) { card.dataset.stage = to; card.dataset.version = res.data.version; updateOpportunityCounts(); } else { rollbackOpportunityCard(card, from); Toast.error(res.message); } }).fail(xhr => { rollbackOpportunityCard(card, from); Toast.error((xhr.responseJSON || {}).message || '状態変更に失敗しました'); });
}

function rollbackOpportunityCard(card, from) { $(`#opportunity-board .crm-kanban-list[data-stage="${CSS.escape(from)}"]`).append(card); card.dataset.stage = from; updateOpportunityCounts(); }
function updateOpportunityCounts() { $('#opportunity-board .crm-kanban-column').each(function () { $(this).find('[data-count]').text($(this).find('.crm-opportunity-card').length); }); }
function openOpportunityModal(item) { $('#opportunity-form')[0].reset(); $('#opportunity-id').val(item ? item.id : ''); $('#opportunity-version').val(item ? item.version : ''); $('#opportunity-count').val(item && item.requiredCount || 1); $('#opportunity-probability').val(item && item.probability != null ? item.probability : 20); $('#opportunity-owner').val(item && item.ownerUserId || ''); if (item) { $('#opportunity-customer').val(item.customerId); $('#opportunity-title').val(item.title); $('#opportunity-month').val(item.expectedStartMonth || ''); $('#opportunity-duration').val(item.durationMonths || ''); $('#opportunity-price').val(item.unitPrice || ''); $('#opportunity-amount').val(item.expectedAmount || ''); $('#opportunity-next-action').val(item.nextActionDate || ''); $('#opportunity-competitor').val(item.competitor || ''); $('#opportunity-override-reason').val(item.probabilityOverrideReason || ''); } bootstrap.Modal.getOrCreateInstance(document.getElementById('opportunityModal')).show(); }
function editOpportunity(id) { const item = (window.crmOpportunities || []).find(x => x.id === id); if (item) openOpportunityModal(item); }
function saveOpportunity() { const id = $('#opportunity-id').val(); const data = { customerId: Number($('#opportunity-customer').val()), title: $('#opportunity-title').val(), expectedStartMonth: $('#opportunity-month').val() || null, durationMonths: $('#opportunity-duration').val() ? Number($('#opportunity-duration').val()) : null, requiredCount: Number($('#opportunity-count').val() || 1), unitPrice: $('#opportunity-price').val() ? Number($('#opportunity-price').val()) : null, expectedAmount: $('#opportunity-amount').val() ? Number($('#opportunity-amount').val()) : null, probability: $('#opportunity-probability').val() ? Number($('#opportunity-probability').val()) : null, ownerUserId: $('#opportunity-owner').val() ? Number($('#opportunity-owner').val()) : null, nextActionDate: $('#opportunity-next-action').val() || null, competitor: $('#opportunity-competitor').val() || null, probabilityOverrideReason: $('#opportunity-override-reason').val() || null, version: id ? Number($('#opportunity-version').val()) : null }; $.ajax({ url: id ? `/api/crm/opportunities/${id}` : '/api/crm/opportunities', method: id ? 'PUT' : 'POST', contentType: 'application/json', data: JSON.stringify(data) }).done(res => { if (res.code === 200) { Toast.success(SES.i18n.t('success.save')); bootstrap.Modal.getInstance(document.getElementById('opportunityModal')).hide(); loadOpportunities(); } else Toast.error(res.message); }).fail(xhr => Toast.error((xhr.responseJSON || {}).message || SES.i18n.t('error.saveFailed'))); }
