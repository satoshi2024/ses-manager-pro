const PROPOSAL_STATUS_TRANSITIONS = {
    '書類選考中': ['一次面接', '見送り'],
    '一次面接': ['二次面接', '結果待ち', '見送り'],
    '二次面接': ['結果待ち', '見送り'],
    '結果待ち': ['成約', '見送り'],
    '成約': [],
    '見送り': []
};

$(document).ready(function() {
    // --- Initialize Sortable for Kanban Columns ---
    const columns = document.querySelectorAll('.kanban-column-body');
    const sortables = [];
    
    columns.forEach(column => {
        const sortable = new Sortable(column, {
            group: 'kanban', // set both lists to same group
            animation: 150,
            ghostClass: 'sortable-ghost',
            dragClass: 'sortable-drag',
            easing: "cubic-bezier(1, 0, 0, 1)",
            scroll: true,
            scrollSensitivity: 100, // How close to the edge to trigger scrolling
            scrollSpeed: 20, // px per frame
            
            // Triggered when an item is dropped into this list from another list
            onAdd: function (evt) {
                const itemEl = evt.item;  // dragged HTMLElement
                const newCol = evt.to;    // target list
                
                const proposalId = itemEl.dataset.id;
                const oldStatus = evt.from.parentElement.dataset.status;
                const newStatus = newCol.parentElement.dataset.status;

                if (!isProposalStatusTransitionAllowed(oldStatus, newStatus)) {
                    moveProposalCardBack(itemEl, evt.from, evt.oldIndex);
                    Toast.error(`ステータスは「${oldStatus}」から「${newStatus}」へ変更できません`);
                    updateBadgeCounts();
                    return;
                }

                updateProposalStatus(proposalId, newStatus, itemEl, evt.from, evt.oldIndex);
            }
        });
        sortables.push(sortable);
    });

    // --- Load Data ---
    loadKanbanData();
    loadSelectOptions();

    // Horizontal Scrolling for Kanban Board by dragging the background
    const slider = document.querySelector('.kanban-board-container');
    let isDown = false;
    let startX;
    let scrollLeft;

    slider.addEventListener('mousedown', (e) => {
        // Prevent scrolling if clicking on a card (let Sortable handle it)
        if (e.target.closest('.kanban-card')) return;
        
        isDown = true;
        slider.style.cursor = 'grabbing';
        startX = e.pageX - slider.offsetLeft;
        scrollLeft = slider.scrollLeft;
    });
    slider.addEventListener('mouseleave', () => {
        isDown = false;
        slider.style.cursor = 'grab';
    });
    slider.addEventListener('mouseup', () => {
        isDown = false;
        slider.style.cursor = 'grab';
    });
    slider.addEventListener('mousemove', (e) => {
        if (!isDown) return;
        e.preventDefault();
        const x = e.pageX - slider.offsetLeft;
        const walk = (x - startX) * 2; // scroll-fast
        slider.scrollLeft = scrollLeft - walk;
    });

    // Horizontal scrolling with mouse wheel
    slider.addEventListener('wheel', (e) => {
        // If the user is scrolling vertically (deltaY) and NOT hovering over a scrollable column body,
        // or if they hold shift, scroll the board horizontally.
        if (e.deltaY !== 0) {
            // Check if hovered element is a column body that has vertical scroll
            const columnBody = e.target.closest('.kanban-column-body');
            const hasVerticalScroll = columnBody && columnBody.scrollHeight > columnBody.clientHeight;
            
            // If we are over a column that can scroll vertically, let it scroll vertically.
            // Otherwise, translate vertical wheel to horizontal scroll.
            const maxScrollLeft = slider.scrollWidth - slider.clientWidth;
            const canScrollInDirection =
                (e.deltaY > 0 && slider.scrollLeft < maxScrollLeft) ||
                (e.deltaY < 0 && slider.scrollLeft > 0);

            if ((!hasVerticalScroll || e.shiftKey) && canScrollInDirection) {
                e.preventDefault();
                slider.scrollLeft += e.deltaY;
            }
        }
    }, { passive: false });
});

function isProposalStatusTransitionAllowed(oldStatus, newStatus) {
    return (PROPOSAL_STATUS_TRANSITIONS[oldStatus] || []).includes(newStatus);
}

function moveProposalCardBack(itemEl, fromCol, oldIndex) {
    const children = Array.from(fromCol.children).filter(child => child !== itemEl);
    const beforeNode = children[oldIndex] || null;
    fromCol.insertBefore(itemEl, beforeNode);
}

function loadKanbanData() {
    // Show loading state
    $('.kanban-column-body').html('<div class="text-center text-muted p-4"><div class="spinner-border spinner-border-sm me-2"></div>' + SES.i18n.t('js.common.loading') + '</div>');
    
    $.ajax({
        url: '/api/proposals/kanban',
        method: 'GET',
        success: function(res) {
            if (res.code === 200) {
                renderKanbanBoard(res.data);
            } else {
                Toast.error(res.message || SES.i18n.t('js.kanban.error_fetch'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('js.common.error_network'));
            $('.kanban-column-body').empty();
        }
    });
}

function loadSelectOptions() {
    $.get('/api/engineers', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#prop-engineerId');
            select.empty().append(`<option value="">${SES.i18n.t('proposal.engineer.select')}</option>`);
            (res.data.records || res.data).forEach(e => select.append(`<option value="${e.id}">${SES.escapeHtml(e.fullName)}</option>`));
        }
    });
    $.get('/api/projects', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#prop-projectId');
            select.empty().append(`<option value="">${SES.i18n.t('proposal.project.select')}</option>`);
            (res.data.records || res.data).forEach(p => select.append(`<option value="${p.id}">${SES.escapeHtml(p.projectName)}</option>`));
        }
    });
}

function renderKanbanBoard(data) {
    // Clear all columns
    $('.kanban-column-body').empty();
    $('.kanban-column .badge').text('0');

    // Group by status
    const statusMap = {
        '書類選考中': '#col-document',
        '一次面接': '#col-interview1',
        '二次面接': '#col-interview2',
        '結果待ち': '#col-waiting',
        '成約': '#col-won',
        '見送り': '#col-lost'
    };
    
    const counts = {};

    data.forEach(item => {
        const targetSelector = statusMap[item.status] || '#col-document';
        const col = $(targetSelector);
        
        if (col.length) {
            col.append(createKanbanCard(item));
            counts[item.status] = (counts[item.status] || 0) + 1;
        }
    });

    // Update counts
    Object.keys(counts).forEach(status => {
        $(`.kanban-column[data-status="${status}"] .badge`).text(counts[status]);
    });
}

function createKanbanCard(item) {
    const scoreColor = item.aiMatchScore >= 80 ? 'text-success' : (item.aiMatchScore >= 60 ? 'text-warning' : 'text-danger');
    const scoreIcon = 'bi-robot';
    
    return `
        <div class="kanban-card" data-id="${item.id}" onclick="viewProposalDetail(${item.id})">
            <div class="kanban-card-title">${SES.escapeHtml(item.projectName || SES.i18n.t('js.kanban.project_tbd'))}</div>

            <div class="kanban-card-subtitle">
                <div class="avatar bg-gradient-purple text-white rounded-circle d-flex justify-content-center align-items-center me-2" style="width: 24px; height: 24px; font-size: 0.6rem;">
                    ${SES.escapeHtml(item.engineerInitial || 'N/A')}
                </div>
                <span class="text-truncate">${SES.escapeHtml(item.engineerName || SES.i18n.t('js.kanban.engineer_not_set'))}</span>
            </div>

            <div class="kanban-card-subtitle mb-2 small text-truncate">
                <i class="bi bi-building me-1"></i> ${SES.escapeHtml(item.customerName || SES.i18n.t('js.kanban.customer_tbd'))}
            </div>
            
            <div class="kanban-card-meta">
                <span class="kanban-card-price">¥${item.proposedUnitPrice != null ? item.proposedUnitPrice.toLocaleString() : '---'}</span>

                ${item.aiMatchScore ? `
                <div class="ai-score-badge small" title="' + SES.i18n.t('js.kanban.ai_score') + '">
                    <i class="bi ${scoreIcon} me-1"></i>${item.aiMatchScore}%
                </div>
                ` : ''}
                <button class="btn btn-sm btn-link text-info p-0 ms-auto" title="' + SES.i18n.t('js.kanban.mail_send') + '"
                        onclick="event.stopPropagation(); openMailModal(${item.id})">
                    <i class="bi bi-envelope"></i>
                </button>
            </div>
        </div>
    `;
}

function updateProposalStatus(proposalId, newStatus, itemEl, fromCol, oldIndex) {
    if (newStatus === '成約') {
        executeStatusChange(proposalId, newStatus, itemEl, fromCol, oldIndex, true);
    } else {
        executeStatusChange(proposalId, newStatus, itemEl, fromCol, oldIndex, false);
    }
}

function executeStatusChange(proposalId, newStatus, itemEl, fromCol, oldIndex, isWon) {
    $.ajax({
        url: `/api/proposals/${proposalId}/status`,
        method: 'PUT',
        contentType: 'application/json',
        data: JSON.stringify({ status: newStatus }),
        success: function(res) {
            if (res.code === 200) {
                updateBadgeCounts();

                if (isWon) {
                    // 契約ドラフトはサーバー側(成約と同一トランザクション)で自動生成済み。
                    // クライアントからの契約作成は行わず、確認導線のみ提示する。
                    Swal.fire({
                        title: SES.i18n.t('js.kanban.won.title'),
                        text: SES.i18n.t('js.kanban.won.text'),
                        icon: 'success',
                        showCancelButton: true,
                        confirmButtonText: SES.i18n.t('js.kanban.won.confirm'),
                        cancelButtonText: SES.i18n.t('js.kanban.won.cancel')
                    }).then((result) => {
                        if (result.isConfirmed) {
                            window.location.href = '/contract/list';
                        }
                    });
                } else {
                    Toast.success(SES.i18n.t('js.kanban.status.update').replace('{0}', SES.i18n.e(newStatus)));
                }
            } else {
                Toast.error(res.message);
                moveProposalCardBack(itemEl, fromCol, oldIndex);
                updateBadgeCounts();
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('js.common.error_network'));
            moveProposalCardBack(itemEl, fromCol, oldIndex);
            updateBadgeCounts();
        }
    });
}

function updateBadgeCounts() {
    $('.kanban-column').each(function() {
        const count = $(this).find('.kanban-card').length;
        $(this).find('.badge').text(count);
    });
}

function viewProposalDetail(id) {
    // Disable click if dragging
    // Handled by sortable preventing default click sometimes, 
    // but just in case, check if we're dragging. 
    // Usually Sortable intercepts the drag.
    
    // window.location.href = `/proposal/detail?id=${id}`;
    // Toast.info('提案詳細画面へ遷移します(ID: ' + id + ')');
    
    // For MVP demo, show a quick modal or toast
    Toast.info(SES.i18n.t('js.kanban.proposal.detail').replace('{0}', id));
}

function saveProposal() {
    const engineerId = $('#prop-engineerId').val();
    const projectId = $('#prop-projectId').val();
    
    if (!engineerId || !projectId) {
        Toast.error(SES.i18n.t('js.kanban.error.engineer_project'));
        return;
    }
    
    const data = {
        engineerId: parseInt(engineerId),
        projectId: parseInt(projectId),
        proposedUnitPrice: $('#prop-proposedUnitPrice').val() ? parseInt($('#prop-proposedUnitPrice').val()) : null
    };

    $.ajax({
        url: '/api/proposals',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.kanban.success.register'));
                bootstrap.Modal.getOrCreateInstance(document.getElementById('proposalModal')).hide();
                $('#proposal-form')[0].reset();
                loadKanbanData(); // Refresh board
            } else {
                Toast.error(res.message || SES.i18n.t('js.kanban.error.register'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

// ==========================================
// 提案メール送信
// ==========================================
function openMailModal(proposalId) {
    $('#mail-proposalId').val(proposalId);
    $('#mail-to').val('');
    const $select = $('#mail-template');
    $select.html('<option value="">' + SES.i18n.t('js.common.loading') + '</option>');
    $.get('/api/email-templates', function(res) {
        const list = res.data && (res.data.records || res.data) || [];
        $select.empty().append(`<option value="">${SES.i18n.t('kanban.proposal.mail.template.select')}</option>`);
        list.forEach(t => $select.append(`<option value="${t.id}">${SES.escapeHtml(t.templateName)}</option>`));
    }).fail(function() {
        $select.html(`<option value="">${SES.i18n.t('js.kanban.mail.fetch_error')}</option>`);
    });
    bootstrap.Modal.getOrCreateInstance(document.getElementById('mailModal')).show();
}

function sendProposalMail() {
    const proposalId = $('#mail-proposalId').val();
    const templateId = $('#mail-template').val();
    const to = $('#mail-to').val();
    if (!templateId) { Toast.error(SES.i18n.t('js.kanban.mail.select_template')); return; }

    $.ajax({
        url: `/api/proposals/${proposalId}/send-mail`,
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ templateId: templateId, to: to }),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.kanban.mail.success'));
                bootstrap.Modal.getInstance(document.getElementById('mailModal')).hide();
            } else {
                Toast.error(res.message || SES.i18n.t('js.kanban.mail.error'));
            }
        },
        error: function(xhr) {
            const msg = (xhr.responseJSON && xhr.responseJSON.message) || SES.i18n.t('js.kanban.mail.error');
            Toast.error(msg);
        }
    });
}
