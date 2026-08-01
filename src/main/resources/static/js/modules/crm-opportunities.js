const CRM_OPPORTUNITY_STAGES = ['見込', '要件確認', '提案準備', '見積提出', '交渉', '受注', '失注'];
$(function () { loadOpportunities(); initOpportunityDragDrop(); });

function loadOpportunities() {
    $.get('/api/crm/opportunities', function (res) { if (res.code === 200) { $('#opportunity-board [data-stage] .crm-kanban-list').empty(); (res.data || []).forEach(renderOpportunityCard); updateOpportunityCounts(); } }).fail(() => Toast.error('商機一覧の取得に失敗しました'));
}

function renderOpportunityCard(item) {
    const list = $(`#opportunity-board .crm-kanban-list[data-stage="${CSS.escape(item.stage)}"]`); if (!list.length) return;
    list.append(`<div class="crm-opportunity-card card bg-dark border-secondary mb-2 p-2" draggable="true" data-id="${item.id}" data-stage="${SES.escapeHtml(item.stage)}" data-version="${item.version || 1}"><div class="fw-bold text-light">${SES.escapeHtml(item.title || '')}</div><div class="small text-muted">${SES.escapeHtml(item.customerName || '')}</div><div class="small text-info mt-1">${item.unitPrice == null ? '-' : `¥${Number(item.unitPrice).toLocaleString()}`}</div></div>`);
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
function openOpportunityModal() { $('#opportunity-form')[0].reset(); $('#opportunity-count').val(1); bootstrap.Modal.getOrCreateInstance(document.getElementById('opportunityModal')).show(); }
function saveOpportunity() { const data = { customerId: Number($('#opportunity-customer').val()), title: $('#opportunity-title').val(), expectedStartMonth: $('#opportunity-month').val() || null, requiredCount: Number($('#opportunity-count').val() || 1), unitPrice: $('#opportunity-price').val() ? Number($('#opportunity-price').val()) : null }; $.ajax({ url: '/api/crm/opportunities', method: 'POST', contentType: 'application/json', data: JSON.stringify(data) }).done(res => { if (res.code === 200) { Toast.success('商機を保存しました'); bootstrap.Modal.getInstance(document.getElementById('opportunityModal')).hide(); loadOpportunities(); } else Toast.error(res.message); }).fail(xhr => Toast.error((xhr.responseJSON || {}).message || '保存に失敗しました')); }
