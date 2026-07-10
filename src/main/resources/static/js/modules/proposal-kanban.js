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
            
            // Triggered when an item is dropped into this list from another list
            onAdd: function (evt) {
                const itemEl = evt.item;  // dragged HTMLElement
                const newCol = evt.to;    // target list
                
                const proposalId = itemEl.dataset.id;
                const newStatus = newCol.parentElement.dataset.status;
                
                updateProposalStatus(proposalId, newStatus, itemEl, evt.from);
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
});

function loadKanbanData() {
    // Show loading state
    $('.kanban-column-body').html('<div class="text-center text-muted p-4"><div class="spinner-border spinner-border-sm me-2"></div>読み込み中...</div>');
    
    $.ajax({
        url: '/api/proposals/kanban',
        method: 'GET',
        success: function(res) {
            if (res.code === 200) {
                renderKanbanBoard(res.data);
            } else {
                Toast.error(res.message || 'データ取得に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
            $('.kanban-column-body').empty();
        }
    });
}

function loadSelectOptions() {
    $.get('/api/engineers', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#prop-engineerId');
            select.empty().append('<option value="">要員を選択...</option>');
            (res.data.records || res.data).forEach(e => select.append(`<option value="${e.id}">${e.fullName}</option>`));
        }
    });
    $.get('/api/projects', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#prop-projectId');
            select.empty().append('<option value="">案件を選択...</option>');
            (res.data.records || res.data).forEach(p => select.append(`<option value="${p.id}">${p.projectName}</option>`));
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
            <div class="kanban-card-title">${item.projectName || '案件未定'}</div>
            
            <div class="kanban-card-subtitle">
                <div class="avatar bg-gradient-purple text-white rounded-circle d-flex justify-content-center align-items-center me-2" style="width: 24px; height: 24px; font-size: 0.6rem;">
                    ${item.engineerInitial || 'N/A'}
                </div>
                <span class="text-truncate">${item.engineerName || '未指定'}</span>
            </div>
            
            <div class="kanban-card-subtitle mb-2 small text-truncate">
                <i class="bi bi-building me-1"></i> ${item.customerName || '顧客未定'}
            </div>
            
            <div class="kanban-card-meta">
                <span class="kanban-card-price">¥${item.proposedUnitPrice ? item.proposedUnitPrice.toLocaleString() : '---'}万</span>
                
                ${item.aiMatchScore ? `
                <div class="ai-score-badge small" title="AIマッチングスコア">
                    <i class="bi ${scoreIcon} me-1"></i>${item.aiMatchScore}%
                </div>
                ` : ''}
            </div>
        </div>
    `;
}

function updateProposalStatus(proposalId, newStatus, itemEl, fromCol) {
    // Prevent UI flicker by keeping it in the new column optimistically
    
    // Check if moved to "成約" -> prompt for contract creation
    if (newStatus === '成約') {
        Swal.fire({
            title: '成約おめでとうございます！',
            text: "この提案から自動的に契約レコードを作成しますか？",
            icon: 'success',
            showCancelButton: true,
            confirmButtonColor: '#20c997',
            cancelButtonColor: '#6c757d',
            confirmButtonText: 'はい、作成する',
            cancelButtonText: '後で'
        }).then((result) => {
            if (result.isConfirmed) {
                // Call API to change status AND create contract
                executeStatusChange(proposalId, newStatus, itemEl, fromCol, true);
            } else {
                executeStatusChange(proposalId, newStatus, itemEl, fromCol, false);
            }
        });
    } else {
        executeStatusChange(proposalId, newStatus, itemEl, fromCol, false);
    }
}

function executeStatusChange(proposalId, newStatus, itemEl, fromCol, createContract) {
    $.ajax({
        url: `/api/proposals/${proposalId}/status`,
        method: 'PUT',
        contentType: 'application/json',
        data: JSON.stringify({ 
            status: newStatus,
            createContract: createContract
        }),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(`ステータスを「${newStatus}」に更新しました`);
                // Update badge counts
                updateBadgeCounts();
                
                if (createContract && res.data && res.data.contractId) {
                    // Redirect to contract edit page
                    window.location.href = `/contract/form?id=${res.data.contractId}`;
                }
            } else {
                Toast.error(res.message);
                // Revert DOM
                $(fromCol).append(itemEl);
                updateBadgeCounts();
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('ステータス更新に失敗しました（モック動作）');
            // Normally revert, but for mock we just update counts
            updateBadgeCounts();
            
            if (createContract) {
                // Mock redirect
                Toast.success('【モック】契約作成画面へ遷移します');
                setTimeout(() => window.location.href = '/contract/list', 1000);
            }
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
    Toast.info('提案詳細画面（ID: ' + id + '）');
}

function saveProposal() {
    const engineerId = $('#prop-engineerId').val();
    const projectId = $('#prop-projectId').val();
    
    if (!engineerId || !projectId) {
        Toast.error('要員と案件は必須です');
        return;
    }
    
    const data = {
        engineerId: parseInt(engineerId),
        projectId: parseInt(projectId),
        proposedUnitPrice: $('#prop-proposedUnitPrice').val() ? parseInt($('#prop-proposedUnitPrice').val()) : null,
        status: $('#prop-status').val() || '書類選考中'
    };

    $.ajax({
        url: '/api/proposals',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success('提案を登録しました');
                bootstrap.Modal.getInstance(document.getElementById('proposalModal')).hide();
                $('#proposal-form')[0].reset();
                loadKanbanData(); // Refresh board
            } else {
                Toast.error(res.message || '登録に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}
