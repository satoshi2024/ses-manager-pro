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
            delay: 200,
            delayOnTouchOnly: true,
            fallbackTolerance: 3,
            
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
    $.get('/api/engineers/options', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#prop-engineerId');
            select.empty().append(`<option value="">${SES.i18n.t('proposal.engineer.select')}</option>`);
            res.data.forEach(e => select.append(`<option value="${e.id}">${SES.escapeHtml(e.name)}</option>`));
        }
    });
    $.get('/api/projects/options', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#prop-projectId');
            select.empty().append(`<option value="">${SES.i18n.t('proposal.project.select')}</option>`);
            res.data.forEach(p => select.append(`<option value="${p.id}">${SES.escapeHtml(p.name)}</option>`));
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
                <div class="ai-score-badge small" title="${SES.i18n.t('js.kanban.ai_score')}">
                    <i class="bi ${scoreIcon} me-1"></i>${item.aiMatchScore}%
                </div>
                ` : ''}
                <button class="btn btn-sm btn-link text-info p-0 ms-auto" title="${SES.i18n.t('js.kanban.mail_send')}"
                        onclick="event.stopPropagation(); openMailModal(${item.id})">
                    <i class="bi bi-envelope"></i>
                </button>
                <button class="btn btn-sm btn-link text-primary p-0 ms-2" title="${SES.i18n.t('kanban.proposal.skillsheet', 'スキルシート出力')}"
                        onclick="event.stopPropagation(); openSkillSheetModal(${item.id})">
                    <i class="bi bi-file-earmark-person"></i>
                </button>
                <button class="btn btn-sm btn-link text-warning p-0 ms-2" title="${SES.i18n.t('quotation.btn.new', '見積作成')}"
                        onclick="event.stopPropagation(); location.href='/quotation?fromProposal=${item.id}'">
                    <i class="bi bi-file-earmark-ruled"></i>
                </button>
            </div>
        </div>
    `;
}

function openSkillSheetModal(proposalId) {
    $('#skillsheet-proposalId').val(proposalId);
    $('#skillsheet-anonymize').prop('checked', false);
    
    const $template = $('#skillsheet-template');
    $template.html('<option value="">' + (SES.i18n.t('js.common.loading') || 'Loading...') + '</option>');
    $.get('/api/skillsheet-templates', function(res) {
        if (res.code === 200 && res.data) {
            $template.empty();
            res.data.forEach(t => $template.append(`<option value="${t.id}">${SES.escapeHtml(t.name)}</option>`));
        }
    }).fail(function() {
        $template.html(`<option value="STANDARD">自社標準</option>`);
    });

    $('#skillsheet-format').val('PDF');
    bootstrap.Modal.getOrCreateInstance(document.getElementById('skillSheetModal')).show();
}

function exportSkillSheet() {
    const proposalId = $('#skillsheet-proposalId').val();
    const anonymize = $('#skillsheet-anonymize').is(':checked');
    const template = $('#skillsheet-template').val();
    const format = $('#skillsheet-format').val();

    $.ajax({
        url: `/api/proposals/${proposalId}/skill-sheet/export`,
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ anonymize: anonymize, template: template, format: format }),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.kanban.skillsheet.success') || 'スキルシートを出力しました');
                bootstrap.Modal.getInstance(document.getElementById('skillSheetModal')).hide();
                window.location.href = `/api/files/${res.data}`;
            } else {
                Toast.error(res.message || SES.i18n.t('js.kanban.skillsheet.error') || '出力に失敗しました');
            }
        },
        error: function(xhr) {
            const msg = (xhr.responseJSON && xhr.responseJSON.message) || SES.i18n.t('js.kanban.skillsheet.error') || '通信エラー';
            Toast.error(msg);
        }
    });
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
    $.ajax({
        url: `/api/proposals/${id}/detail`,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const p = res.data;
                $('#prop-id').val(p.id); // Hidden input for edit mode
                $('#prop-engineerId').val(p.engineerId);
                $('#prop-projectId').val(p.projectId);
                $('#prop-proposedUnitPrice').val(p.proposedUnitPrice || '');
                $('#prop-proposalEmailText').val(p.proposalEmailText || '');
                $('#prop-matchReason').val(p.matchReason || '');
                $('#prop-aiMatchScore').val(p.aiMatchScore || '');
                
                $('#proposalModalLabel').text(SES.i18n.t('kanban.proposal.modal.edit') || '提案編集');
                bootstrap.Modal.getOrCreateInstance(document.getElementById('proposalModal')).show();
            } else {
                Toast.error(res.message || SES.i18n.t('js.kanban.error_fetch'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

function saveProposal() {
    const id = $('#prop-id').val();
    const engineerId = $('#prop-engineerId').val();
    const projectId = $('#prop-projectId').val();
    
    if (!engineerId || !projectId) {
        Toast.error(SES.i18n.t('js.kanban.error.engineer_project'));
        return;
    }
    
    const data = {
        engineerId: parseInt(engineerId),
        projectId: parseInt(projectId),
        proposedUnitPrice: $('#prop-proposedUnitPrice').val() ? parseInt($('#prop-proposedUnitPrice').val()) : null,
        proposalEmailText: $('#prop-proposalEmailText').val(),
        matchReason: $('#prop-matchReason').val(),
        aiMatchScore: $('#prop-aiMatchScore').val() ? parseFloat($('#prop-aiMatchScore').val()) : null
    };

    const method = id ? 'PUT' : 'POST';
    const url = id ? `/api/proposals/${id}` : '/api/proposals';

    let checkUrl = `/api/proposals/duplicate-check?engineerId=${engineerId}&projectId=${projectId}`;
    if (id) {
        checkUrl += `&excludeId=${id}`;
    }

    $.ajax({
        url: checkUrl,
        method: 'GET',
        success: function(checkRes) {
            if (checkRes.code === 200 && checkRes.data && checkRes.data.length > 0) {
                let dupMsg = '';
                checkRes.data.forEach(dup => {
                    const proposedAtStr = dup.proposedAt ? dup.proposedAt.replace('T', ' ').substring(0, 10) : '-';
                    const statusAndDate = `${dup.status} / ${proposedAtStr}`;
                    dupMsg += SES.i18n.t('proposal.duplicate.warning', [dup.customerName, dup.projectName, statusAndDate]) + '\n';
                });
                dupMsg += '\n' + SES.i18n.t('proposal.duplicate.confirm');
                Swal.fire({
                    title: SES.i18n.t('common.confirm'),
                    text: dupMsg.trim(),
                    icon: 'warning',
                    showCancelButton: true,
                    confirmButtonText: SES.i18n.t('common.continue'),
                    cancelButtonText: SES.i18n.t('common.cancel')
                }).then((result) => {
                    if (result.isConfirmed) {
                        doSaveProposal(url, method, data);
                    }
                });
            } else {
                doSaveProposal(url, method, data);
            }
        },
        error: function(err) {
            console.error(err);
            doSaveProposal(url, method, data);
        }
    });
}

function doSaveProposal(url, method, data) {
    $.ajax({
        url: url,
        method: method,
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.kanban.success.register'));
                bootstrap.Modal.getOrCreateInstance(document.getElementById('proposalModal')).hide();
                $('#proposal-form')[0].reset();
                $('#prop-id').val(''); // clear id
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

function openNewProposalModal() {
    $('#proposal-form')[0].reset();
    $('#prop-id').val('');
    $('#proposalModalLabel').text(SES.i18n.t('kanban.proposal.modal.new') || '新規提案作成');
    bootstrap.Modal.getOrCreateInstance(document.getElementById('proposalModal')).show();
}

function generateAiDraft() {
    const engineerId = $('#prop-engineerId').val();
    const projectId = $('#prop-projectId').val();
    
    if (!engineerId || !projectId) {
        Toast.error(SES.i18n.t('js.kanban.error.engineer_project'));
        return;
    }

    const btn = $('#btn-ai-draft');
    const spinner = $('#spinner-ai-draft');
    
    btn.prop('disabled', true);
    spinner.removeClass('d-none');

    $.ajax({
        url: '/api/ai/proposal-draft',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({
            engineerId: parseInt(engineerId),
            projectId: parseInt(projectId)
        }),
        success: function(res) {
            btn.prop('disabled', false);
            spinner.addClass('d-none');
            
            if (res.code === 200 && res.data) {
                $('#prop-proposalEmailText').val(res.data.emailText || '');
                const fullReason = (res.data.matchReason || '') + '\n' + (res.data.sellingPoints || '');
                $('#prop-matchReason').val(fullReason.trim());
                $('#prop-aiMatchScore').val(res.data.matchScore || '');
                Toast.success(SES.i18n.t('js.kanban.ai.success') || 'AIによる生成が完了しました。内容を確認して編集してください。');
            } else {
                Toast.error(res.message || '生成に失敗しました');
            }
        },
        error: function(err) {
            btn.prop('disabled', false);
            spinner.addClass('d-none');
            console.error(err);
            Toast.error('AIとの通信に失敗しました。手動で入力してください。');
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
    
    // テンプレート一覧取得前に提案詳細を取得してAI提案文の有無を確認
    $.get(`/api/proposals/${proposalId}/detail`, function(propRes) {
        $.get('/api/email-templates', function(res) {
            const list = res.data && (res.data.records || res.data) || [];
            $select.empty().append(`<option value="">${SES.i18n.t('kanban.proposal.mail.template.select')}</option>`);
            
            // AI提案文が存在する場合は一番上に追加
            if (propRes.code === 200 && propRes.data && propRes.data.proposalEmailText) {
                $select.append(`<option value="-1" class="text-primary fw-bold">✨ ${SES.i18n.t('kanban.proposal.mail.template.ai')}</option>`);
            }
            
            list.forEach(t => $select.append(`<option value="${t.id}">${SES.escapeHtml(t.templateName)}</option>`));
        }).fail(function() {
            $select.html(`<option value="">${SES.i18n.t('js.kanban.mail.fetch_error')}</option>`);
        });
    }).fail(function() {
        // 詳細取得に失敗した場合でもテンプレート取得は試みる
        $.get('/api/email-templates', function(res) {
            const list = res.data && (res.data.records || res.data) || [];
            $select.empty().append(`<option value="">${SES.i18n.t('kanban.proposal.mail.template.select')}</option>`);
            list.forEach(t => $select.append(`<option value="${t.id}">${SES.escapeHtml(t.templateName)}</option>`));
        }).fail(function() {
            $select.html(`<option value="">${SES.i18n.t('js.kanban.mail.fetch_error')}</option>`);
        });
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
