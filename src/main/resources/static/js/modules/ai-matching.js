let currentEngineerId = null;
let currentEngineerName = null;
let aiMatchModal = null;

$(document).ready(function() {
    aiMatchModal = new bootstrap.Modal(document.getElementById('aiMatchModal'));
    
    // Check URL params for auto-open
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.get('action') === 'ai-match') {
        const id = urlParams.get('id');
        if (id) {
            // Wait slightly for engineer-detail.js to load data, or rely on its callback
            setTimeout(() => {
                if (currentEngineerName) {
                    showAiMatchModal(id, currentEngineerName);
                }
            }, 500);
        }
    }
    
    // Chat input Enter key handling
    $('#chat-input').on('keypress', function(e) {
        if (e.which === 13 && !e.shiftKey) {
            e.preventDefault();
            sendChatMessage();
        }
    });
});

function showAiMatchModal(engineerId, engineerName) {
    currentEngineerId = engineerId;
    currentEngineerName = engineerName;
    $('#chat-eng-name').text(engineerName);
    
    // Clear chat history except first message
    const firstMsg = $('#chat-history-area').children().first();
    $('#chat-history-area').empty().append(firstMsg);
    $('#chat-input').val('');
    
    // Load email templates to select (mock)
    loadTemplatesToSelect();
    
    aiMatchModal.show();
}

function loadTemplatesToSelect() {
    $.ajax({
        url: '/api/email-templates',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const select = $('#chat-template-select');
                select.empty().append('<option value="">(テンプレートを選択...)</option>');
                res.data.forEach(t => {
                    select.append(`<option value="${t.id}">${t.templateName}</option>`);
                });
            }
        }
    });
}

function appendUserMessage(text) {
    const html = `
        <div class="d-flex mb-4 justify-content-end">
            <div class="bg-primary bg-gradient rounded p-3 text-white position-relative shadow-sm" style="max-width: 85%;">
                <p class="mb-0 text-break" style="white-space: pre-wrap;">${text}</p>
            </div>
        </div>
    `;
    $('#chat-history-area').append(html);
    scrollToBottom();
}

function appendAiLoading() {
    const id = 'ai-loading-' + Date.now();
    const html = `
        <div class="d-flex mb-4 ai-msg-block" id="${id}">
            <div class="avatar bg-accent-purple text-white rounded-circle d-flex justify-content-center align-items-center me-3 flex-shrink-0 shadow-sm" style="width: 32px; height: 32px;">
                <i class="bi bi-robot"></i>
            </div>
            <div class="bg-dark border border-secondary rounded p-3 text-light position-relative" style="max-width: 85%;">
                <div class="d-flex align-items-center gap-2">
                    <div class="spinner-grow spinner-grow-sm text-accent-purple" role="status"></div>
                    <div class="spinner-grow spinner-grow-sm text-accent-purple" role="status" style="animation-delay: 0.2s"></div>
                    <div class="spinner-grow spinner-grow-sm text-accent-purple" role="status" style="animation-delay: 0.4s"></div>
                </div>
            </div>
        </div>
    `;
    $('#chat-history-area').append(html);
    scrollToBottom();
    return id;
}

function replaceAiLoadingWithMessage(id, contentHtml) {
    const block = $(`#${id} > div.bg-dark`);
    block.empty().html(contentHtml);
    scrollToBottom();
}

function scrollToBottom() {
    const area = $('#chat-history-area');
    area.scrollTop(area[0].scrollHeight);
}

function sendChatMessage() {
    const input = $('#chat-input');
    const text = input.val().trim();
    if (!text) return;
    
    input.val('');
    appendUserMessage(text);
    
    const loadingId = appendAiLoading();
    
    // Simulate AI response based on keyword
    setTimeout(() => {
        if (text.includes('マッチ') || text.includes('案件')) {
            renderMatchResultsInChat(loadingId);
        } else if (text.includes('要約') || text.includes('スキル')) {
            replaceAiLoadingWithMessage(loadingId, `<p class="mb-0">承知いたしました。${currentEngineerName}さんの経歴を要約します。<br><br><b>【強み】</b><br>・10年以上のJava/Spring Boot開発経験<br>・大規模金融システムでのAWSマイグレーション主導<br>・堅牢なシステム設計能力</p>`);
        } else {
            replaceAiLoadingWithMessage(loadingId, `<p class="mb-0">すみません、その指示はよくわかりません。「案件をマッチング」や「スキル要約」と入力するか、下のテンプレートからメールを作成してみてください。</p>`);
        }
    }, 1500);
}

function requestAiMatch() {
    appendUserMessage('最適な案件をマッチングして。');
    const loadingId = appendAiLoading();
    
    $.ajax({
        url: '/api/ai/match/engineer-to-projects',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ engineerId: currentEngineerId }),
        success: function(res) {
            setTimeout(() => {
                const results = res.code === 200 ? res.data : getMockMatchData();
                renderMatchResultsHTML(loadingId, results);
            }, 1500);
        },
        error: function() {
            setTimeout(() => renderMatchResultsHTML(loadingId, getMockMatchData()), 1500);
        }
    });
}

function requestSkillSummary() {
    appendUserMessage('スキルを要約して。');
    const loadingId = appendAiLoading();
    setTimeout(() => {
        replaceAiLoadingWithMessage(loadingId, `<p class="mb-0">承知いたしました。${currentEngineerName}さんの経歴を要約します。<br><br><b>【強み】</b><br>・10年以上のJava/Spring Boot開発経験<br>・大規模金融システムでのAWSマイグレーション主導<br>・堅牢なシステム設計能力</p>`);
    }, 1200);
}

function generateEmailDraft() {
    const templateId = $('#chat-template-select').val();
    const templateName = $('#chat-template-select option:selected').text();
    
    if (!templateId) {
        Toast.error('テンプレートを選択してください');
        return;
    }
    
    appendUserMessage(`「${templateName}」を使って提案文を作成して。`);
    const loadingId = appendAiLoading();
    
    setTimeout(() => {
        const draft = `
            <div class="mb-2 fw-bold text-accent-blue"><i class="bi bi-magic me-2"></i>AI提案文ドラフト</div>
            <div class="bg-secondary p-3 rounded font-monospace small mb-3 border border-dark text-white" style="white-space: pre-wrap;">件名: 【ご提案】エンジニアのご紹介（${currentEngineerName}）

◯◯株式会社
ご担当者様

お世話になっております。SES Manager Proの営業担当です。

貴社のプロジェクトにつきまして、弊社の${currentEngineerName}をご提案させていただきます。

【アピールポイント】
・10年以上のJava/Spring Boot開発経験
・大規模金融システムでのAWSマイグレーション主導
・堅牢なシステム設計能力

スキルシートを添付いたしますので、ご査収のほどよろしくお願いいたします。</div>
            <div class="d-flex justify-content-end gap-2">
                <button class="btn btn-sm btn-outline-secondary text-light border-dark"><i class="bi bi-clipboard"></i> コピー</button>
                <button class="btn btn-sm btn-primary bg-gradient-purple border-0" onclick="alert('メールソフトを起動します')"><i class="bi bi-envelope"></i> メールソフトを開く</button>
            </div>
        `;
        replaceAiLoadingWithMessage(loadingId, draft);
    }, 2000);
}

function renderMatchResultsInChat(loadingId) {
    const results = getMockMatchData();
    renderMatchResultsHTML(loadingId, results);
}

function renderMatchResultsHTML(loadingId, results) {
    let html = `<p class="mb-3">市場の案件を分析し、以下の3件が特にマッチすると判断しました。</p>`;
    
    results.forEach(match => {
        const scoreColor = match.score >= 90 ? 'text-success' : (match.score >= 70 ? 'text-warning' : 'text-danger');
        html += `
            <div class="card bg-secondary border-dark mb-2 shadow-sm">
                <div class="card-body p-3">
                    <div class="d-flex justify-content-between align-items-start mb-2">
                        <h6 class="text-white fw-bold mb-0">${match.projectName}</h6>
                        <div class="fs-6 fw-bold ${scoreColor}">${match.score}%</div>
                    </div>
                    <p class="small text-muted mb-2"><span class="badge bg-primary bg-opacity-25 text-primary border border-primary border-opacity-50 me-1">AI評価</span>${match.reason}</p>
                    <div class="text-end">
                        <button class="btn btn-sm btn-primary bg-gradient-blue border-0 rounded-pill px-3 shadow-sm" onclick="proposeToProject(${match.projectId}, ${match.score})">
                            <i class="bi bi-send-fill me-1"></i>この案件に提案
                        </button>
                    </div>
                </div>
            </div>
        `;
    });
    replaceAiLoadingWithMessage(loadingId, html);
}

function proposeToProject(projectId, score) {
    Toast.success('提案レコードを作成しました！（カンバンボードへ移動します）');
    setTimeout(() => {
        window.location.href = '/proposal/kanban';
    }, 1500);
}

function getMockMatchData() {
    return [
        { projectId: 101, projectName: '金融系バックエンドAPI開発', score: 95, reason: '必須要件のJava/Spring Boot経験を満たしています。' },
        { projectId: 105, projectName: '大手ECサイトリプレイス', score: 82, reason: '技術要件は合致していますが、リモート希望に対し週3出社のためスコアを少し下げています。' },
        { projectId: 112, projectName: '社内DX推進システム構築', score: 75, reason: '単価要件がギリギリですが、フロントエンドの知見もあるため活躍が見込めます。' }
    ];
}
