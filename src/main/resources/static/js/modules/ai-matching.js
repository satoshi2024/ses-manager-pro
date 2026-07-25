let currentEngineerId = null;
let currentEngineerName = null;
// 要員の希望単価(円・生値)。表示文字列を解析せずに提案単価へ渡すため engineer-detail.js が設定する。
let currentEngineerExpectedPrice = null;
// 要員の保有スキル(実データ)。engineer-detail.js が設定する。
let currentEngineerSkills = [];
// メールテンプレート実体(本文を含む)。ドラフトは必ずこの実データから組み立てる。
let loadedTemplates = [];
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
                loadedTemplates = res.data;
                const select = $('#chat-template-select');
                select.empty().append('<option value="">' + SES.i18n.t('ai.select.template') + '</option>');
                res.data.forEach(t => {
                    select.append(`<option value="${t.id}">${SES.escapeHtml(t.templateName)}</option>`);
                });
            }
        }
    });
}

function appendUserMessage(text) {
    const html = `
        <div class="d-flex mb-4 justify-content-end">
            <div class="bg-primary bg-gradient rounded p-3 text-white position-relative shadow-sm" style="max-width: 85%;">
                <p class="mb-0 text-break" style="white-space: pre-wrap;">${SES.escapeHtml(text)}</p>
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

    if (text.includes('マッチ') || text.includes('案件')) {
        fetchAndRenderMatches(loadingId);
    } else if (text.includes('要約') || text.includes('スキル')) {
        renderSkillSummary(loadingId);
    } else {
        replaceAiLoadingWithMessage(loadingId,
            '<p class="mb-0">' + SES.escapeHtml(SES.i18n.t('ai.msg.unknownCommand')) + '</p>');
    }
}

function requestAiMatch() {
    appendUserMessage(SES.i18n.t('ai.msg.requestMatch'));
    fetchAndRenderMatches(appendAiLoading());
}

/**
 * マッチング結果はAPIの実データのみを表示する。
 * 以前は失敗時・非200時にハードコードされたダミー案件(projectId 101/105/112)へ
 * 無言でフォールバックしており、実在しない案件がAIの判定結果として表示されたうえ、
 * そのカードの「提案」ボタンから実データとして提案が登録できてしまっていた。
 */
function fetchAndRenderMatches(loadingId) {
    $.ajax({
        url: '/api/ai/match/engineer-to-projects',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({ engineerId: currentEngineerId }),
        success: function(res) {
            if (res.code !== 200) {
                showMatchError(loadingId, res.message);
                return;
            }
            const results = Array.isArray(res.data) ? res.data : [];
            if (results.length === 0) {
                replaceAiLoadingWithMessage(loadingId,
                    '<p class="mb-0">' + SES.escapeHtml(SES.i18n.t('ai.msg.matchEmpty')) + '</p>');
                return;
            }
            renderMatchResultsHTML(loadingId, results);
        },
        error: function() {
            showMatchError(loadingId, null);
        }
    });
}

function showMatchError(loadingId, message) {
    const text = message || SES.i18n.t('ai.msg.matchFailed');
    replaceAiLoadingWithMessage(loadingId,
        '<p class="mb-0 text-danger">' + SES.escapeHtml(text) + '</p>');
}

/**
 * スキル要約は登録済みの実データ（保有スキル・経歴サマリ）だけで組み立てる。
 * 以前はどの要員に対しても同一のハードコード文（"10年以上のJava/Spring Boot開発経験"等）を
 * 返しており、実在しない経歴があたかもAIの分析結果として表示されていた。
 */
function requestSkillSummary() {
    appendUserMessage(SES.i18n.t('ai.msg.requestSummary'));
    renderSkillSummary(appendAiLoading());
}

function renderSkillSummary(loadingId) {
    const skills = (currentEngineerSkills || []).slice().sort(
        (a, b) => (b.experienceYears || 0) - (a.experienceYears || 0));
    const resume = (typeof detailEngineer !== 'undefined' && detailEngineer)
        ? detailEngineer.resumeSummary : null;

    if (skills.length === 0 && !resume) {
        replaceAiLoadingWithMessage(loadingId,
            '<p class="mb-0">' + SES.escapeHtml(SES.i18n.t('ai.msg.summaryNoData')) + '</p>');
        return;
    }

    let html = '<p class="mb-2">' + SES.escapeHtml(
        SES.i18n.t('ai.msg.summaryTitle', [currentEngineerName || ''])) + '</p>';
    if (skills.length > 0) {
        html += '<ul class="mb-2 ps-3">';
        skills.slice(0, 5).forEach(s => {
            const years = s.experienceYears
                ? ' / ' + s.experienceYears + SES.i18n.t('engineer.experience.unit') : '';
            const prof = s.proficiency ? ' (' + SES.escapeHtml(s.proficiency) + ')' : '';
            html += '<li>' + SES.escapeHtml(s.skillName) + prof + SES.escapeHtml(years) + '</li>';
        });
        html += '</ul>';
    }
    if (resume) {
        html += '<p class="mb-0 small text-muted" style="white-space: pre-wrap;">'
             + SES.escapeHtml(resume) + '</p>';
    }
    replaceAiLoadingWithMessage(loadingId, html);
}

/**
 * 提案文ドラフトは選択した実テンプレート({@code m_email_template})の本文を差し込む。
 * 以前は要員によらず固定の「強み」箇条書きを出力しており、実在しない経歴が
 * 提案メール文面としてそのままコピーされうる状態だった。
 */
function generateEmailDraft() {
    const templateId = $('#chat-template-select').val();
    const templateName = $('#chat-template-select option:selected').text();

    if (!templateId) {
        Toast.error(SES.i18n.t('ai.msg.selectTemplate'));
        return;
    }

    appendUserMessage(SES.i18n.t('ai.msg.requestDraft', { template: templateName }));
    const loadingId = appendAiLoading();

    const template = (loadedTemplates || []).find(t => String(t.id) === String(templateId));
    if (!template || !template.bodyTemplate) {
        replaceAiLoadingWithMessage(loadingId,
            '<p class="mb-0 text-danger">' + SES.escapeHtml(SES.i18n.t('ai.msg.draftFailed')) + '</p>');
        return;
    }

    // テンプレート内の {engineerName} 等は実データで置換する（未知のプレースホルダーは残す）
    const filled = fillTemplate(template.bodyTemplate);
    const subject = template.subjectTemplate ? fillTemplate(template.subjectTemplate) : '';

    const draft = `
        <div class="mb-2 fw-bold text-accent-blue"><i class="bi bi-magic me-2"></i>${SES.escapeHtml(SES.i18n.t('ai.msg.draftTitle'))}</div>
        <div class="bg-secondary p-3 rounded font-monospace small mb-3 border border-dark text-white" style="white-space: pre-wrap;">${SES.escapeHtml((subject ? subject + '\n\n' : '') + filled)}</div>
        <div class="d-flex justify-content-end gap-2">
            <button class="btn btn-sm btn-outline-secondary text-light border-dark" onclick="copyDraftToClipboard(this)"><i class="bi bi-clipboard"></i> ${SES.escapeHtml(SES.i18n.t('common.btn.copy'))}</button>
        </div>
    `;
    replaceAiLoadingWithMessage(loadingId, draft);
}

function fillTemplate(text) {
    const engineerName = currentEngineerName || '';
    return String(text)
        .replace(/\{engineerName\}/g, engineerName)
        .replace(/\{要員名\}/g, engineerName);
}

function copyDraftToClipboard(btn) {
    const body = $(btn).closest('div').prev('div').text();
    if (navigator.clipboard) {
        navigator.clipboard.writeText(body).then(
            () => Toast.success(SES.i18n.t('common.btn.copy')),
            () => Toast.error(SES.i18n.t('js.common.error_network')));
    }
}

function renderMatchResultsHTML(loadingId, results) {
    // 件数は実データの数を出す（以前はダミー3件前提の固定文だった）
    let html = `<p class="mb-3">${SES.escapeHtml(SES.i18n.t('ai.msg.matchResult', [results.length]))}</p>`;
    
    results.forEach(match => {
        const scoreColor = match.score >= 90 ? 'text-success' : (match.score >= 70 ? 'text-warning' : 'text-danger');
        html += `
            <div class="card bg-secondary border-dark mb-2 shadow-sm">
                <div class="card-body p-3">
                    <div class="d-flex justify-content-between align-items-start mb-2">
                        <h6 class="text-white fw-bold mb-0">${SES.escapeHtml(match.projectName)}</h6>
                        <div class="fs-6 fw-bold ${scoreColor}">${match.score}%</div>
                    </div>
                    <p class="small text-muted mb-2"><span class="badge bg-primary bg-opacity-25 text-primary border border-primary border-opacity-50 me-1">${SES.i18n.t('ai.badge.evaluation')}</span>${SES.escapeHtml(match.reason)}</p>
                    <div class="text-end">
                        <button class="btn btn-sm btn-primary bg-gradient-blue border-0 rounded-pill px-3 shadow-sm" onclick="proposeToProject(${match.projectId}, ${match.score})">
                            <i class="bi bi-send-fill me-1"></i>${SES.i18n.t('ai.btn.propose')}
                        </button>
                    </div>
                </div>
            </div>
        `;
    });
    replaceAiLoadingWithMessage(loadingId, html);
}

function proposeToProject(projectId, score) {
    // 希望単価は表示文字列からではなく、要員詳細が保持している生値(円)から取る。
    // 表示は "¥600,000 / 月" のように整形されるため、文字列を parseInt しても必ず NaN になり、
    // 提案単価が黙って未設定のまま登録されていた。
    const proposedPrice = currentEngineerExpectedPrice != null ? currentEngineerExpectedPrice : null;

    const data = {
        engineerId: currentEngineerId,
        projectId: projectId,
        proposedUnitPrice: proposedPrice,
        status: '書類選考中',
        aiMatchScore: score,
        matchReason: 'AIマッチング経由'
    };

    $.ajax({
        url: '/api/proposals',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('ai.msg.proposeSuccess'));
                setTimeout(() => {
                    window.location.href = '/proposal/kanban';
                }, 1500);
            } else {
                Toast.error(SES.i18n.t('ai.msg.proposeFail') + (res.message || ''));
            }
        },
        error: function() {
            Toast.error(SES.i18n.t('common.msg.networkError'));
        }
    });
}


