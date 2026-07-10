let currentEngineerId = null;

function showAiMatchModal(engineerId, engineerName) {
    currentEngineerId = engineerId;
    
    // Reset modal state
    $('#ai-results').addClass('d-none');
    $('#ai-loading').removeClass('d-none');
    $('#match-list-container').empty();
    
    // Show modal
    const modal = new bootstrap.Modal(document.getElementById('aiMatchModal'));
    modal.show();
    
    // Call API (simulated with setTimeout if backend is mock)
    $.ajax({
        url: '/api/ai/match/engineer-to-projects',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ engineerId: engineerId }),
        success: function(res) {
            if (res.code === 200) {
                // Add artificial delay for "AI thinking" effect
                setTimeout(() => renderMatchResults(res.data), 1500);
            } else {
                handleMatchError(res.message);
            }
        },
        error: function(err) {
            console.error(err);
            // Fallback to mock data
            setTimeout(() => renderMatchResults(getMockMatchData()), 1500);
        }
    });
}

function renderMatchResults(results) {
    $('#ai-loading').addClass('d-none');
    $('#ai-results').removeClass('d-none');
    
    const container = $('#match-list-container');
    container.empty();
    
    if (!results || results.length === 0) {
        container.html('<div class="text-center text-muted p-3">適合する案件が見つかりませんでした</div>');
        return;
    }
    
    results.forEach((match, idx) => {
        const scoreColor = match.score >= 90 ? 'text-success' : (match.score >= 70 ? 'text-warning' : 'text-danger');
        
        const html = `
            <div class="list-group-item bg-dark border-secondary rounded p-3 mb-2">
                <div class="d-flex justify-content-between align-items-start mb-2">
                    <h6 class="text-white fw-bold mb-0">${match.projectName}</h6>
                    <div class="fs-5 fw-bold ${scoreColor}">${match.score}%</div>
                </div>
                <div class="mb-2">
                    <span class="badge bg-primary bg-opacity-25 text-primary border border-primary border-opacity-50 me-1">AIアピールポイント</span>
                    <span class="text-white small">${match.sellingPoints}</span>
                </div>
                <div class="p-2 bg-secondary rounded small text-muted border border-dark mb-3">
                    <i class="bi bi-info-circle me-1"></i> ${match.reason}
                </div>
                <div class="text-end">
                    <button class="btn btn-sm btn-outline-primary border-0 text-accent-blue hover-bg-blue" onclick="window.location.href='/project/detail?id=${match.projectId}'">
                        案件詳細
                    </button>
                    <button class="btn btn-sm btn-primary bg-gradient-blue border-0 rounded-pill px-3 shadow-sm ms-2" onclick="proposeToProject(${match.projectId}, ${match.score})">
                        <i class="bi bi-send-fill me-1"></i>この案件に提案
                    </button>
                </div>
            </div>
        `;
        container.append(html);
    });
}

function handleMatchError(msg) {
    $('#ai-loading').addClass('d-none');
    $('#ai-results').removeClass('d-none');
    $('#match-list-container').html(`<div class="text-center text-danger p-3"><i class="bi bi-exclamation-triangle me-2"></i>エラー: ${msg}</div>`);
}

function proposeToProject(projectId, score) {
    Toast.success('提案レコードを作成しました！（カンバンボードへ移動します）');
    // In real app, make POST request to /api/proposals then redirect
    setTimeout(() => {
        window.location.href = '/proposal/kanban';
    }, 1500);
}

function getMockMatchData() {
    return [
        { projectId: 101, projectName: '金融系バックエンドAPI開発', score: 95, reason: '必須要件のJava/Spring Boot経験を完全に満たしており、金融業界での経験も長いため即戦力として期待できます。', sellingPoints: '10年のJava経験と堅牢なシステム設計能力' },
        { projectId: 105, projectName: '大手ECサイトリプレイス', score: 82, reason: '技術要件は合致していますが、リモート希望に対して本案件は週3出社のためスコアを少し下げています。', sellingPoints: '過去の類似ECサイトリプレイス実績' },
        { projectId: 112, projectName: '社内DX推進システム構築', score: 75, reason: 'バックエンド主体の要件に対してフロントエンドの知見もあるため、フルスタックな活躍が見込めます。単価要件がギリギリです。', sellingPoints: 'インフラからフロントまで対応可能な汎用性' }
    ];
}
