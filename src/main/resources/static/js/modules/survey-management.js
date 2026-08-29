// サーベイ管理（engineer-self-service-portal-v2 B2 / design §5/§6.2）
document.addEventListener('DOMContentLoaded', () => {
    loadTemplates();
    loadCampaigns();
    document.getElementById('campaign-save').addEventListener('click', createCampaign);
    document.getElementById('template-save').addEventListener('click', createTemplate);
    document.getElementById('question-add').addEventListener('click', () => {
        document.getElementById('question-rows').insertAdjacentHTML('beforeend', questionRow());
    });
});

function loadTemplates() {
    SES.api.get('/api/surveys/templates', { current: 1, size: 100 }).then(d => {
        const body = document.getElementById('template-body');
        const rows = d.records || [];
        body.innerHTML = rows.length ? rows.map(t => `<tr><td>${SES.escapeHtml(t.templateKey)}</td><td>${SES.escapeHtml(t.title)}</td>
            <td>${SES.escapeHtml(t.status)}</td><td>${(t.questions || []).length}</td></tr>`).join('')
            : '<tr><td colspan="4" class="text-center text-muted">—</td></tr>';
        const sel = document.getElementById('campaign-template');
        sel.innerHTML = rows.map(t => `<option value="${t.id}">${SES.escapeHtml(t.title)}</option>`).join('');
    }).catch(error => {
        console.error(error);
        SES.toast.error(error.message || 'サーベイテンプレートの取得に失敗しました');
    });
}

function loadCampaigns() {
    SES.api.get('/api/surveys', { current: 1, size: 100 }).then(d => {
        const body = document.getElementById('campaign-body');
        const rows = d.records || [];
        body.innerHTML = rows.length ? rows.map(c => `<tr>
            <td>${SES.escapeHtml(c.title)}</td>
            <td>${SES.escapeHtml(c.periodFrom || '')} 〜 ${SES.escapeHtml(c.periodTo || '')}</td>
            <td>${SES.escapeHtml(c.status)}</td>
            <td><div class="d-flex flex-wrap justify-content-end align-items-center gap-1">${c.status === 'DRAFT' ? `<button class="btn btn-sm btn-outline-primary" data-act="activate" data-id="${c.id}">${SES.escapeHtml(SES.i18n.t('survey.activate','配信開始'))}</button>` : ''}
                ${c.status === 'ACTIVE' ? `<button class="btn btn-sm btn-outline-secondary" data-act="close" data-id="${c.id}">${SES.escapeHtml(SES.i18n.t('survey.close','締め切り'))}</button>` : ''}
                <button class="btn btn-sm btn-outline-info" data-act="aggregate" data-id="${c.id}">${SES.escapeHtml(SES.i18n.t('survey.aggregate','集計'))}</button>
            </div></td></tr>`).join('')
            : '<tr><td colspan="4" class="text-center text-muted">—</td></tr>';
        body.querySelectorAll('button[data-act]').forEach(b => b.addEventListener('click', () => {
            const id = Number(b.dataset.id);
            const act = b.dataset.act;
            if (act === 'activate') runSurveyAction(id, 'activate');
            else if (act === 'close') runSurveyAction(id, 'close');
            else if (act === 'aggregate') loadAggregate(id);
        }));
    }).catch(error => {
        console.error(error);
        SES.toast.error(error.message || 'サーベイ一覧の取得に失敗しました');
    });
}

function loadAggregate(campaignId) {
    SES.api.get('/api/surveys/' + campaignId + '/aggregate').then(a => {
        const el = document.getElementById('aggregate-body');
        const fmt = q => q.hidden
            ? `<div class="text-muted">${SES.escapeHtml(SES.i18n.t('survey.hidden','回答数不足のため非表示（最低{n}件）').replace('{n}', a.minAnswers))} : ${SES.escapeHtml(q.text)}</div>`
            : `<div>${SES.escapeHtml(q.text)} — ${SES.escapeHtml(SES.i18n.t('survey.average','平均'))}: ${q.average != null ? q.average : '—'}（${q.answeredCount}件回答）${q.commentCount > 0 ? '・コメント' + q.commentCount + '件' : ''}</div>`;
        let html = `<h6>${SES.escapeHtml(a.title)}</h6>`;
        html += (a.questions || []).map(fmt).join('');
        html += '<hr><div class="text-muted small">' + SES.escapeHtml(SES.i18n.t('survey.segments','組織別（匿名閾値適用）')) + '</div>';
        html += (a.segments || []).map(s => `<div class="mt-1"><b>${SES.escapeHtml(s.organizationName)}</b>（回答要員${s.answeredEngineers}名）
            ${s.hidden ? '<span class="badge bg-warning text-dark">' + SES.escapeHtml(SES.i18n.t('survey.hiddenSegment','非表示')) + '</span>' : (s.questions || []).map(fmt).join('')}</div>`).join('');
        el.innerHTML = html;
    }).catch(error => {
        console.error(error);
        SES.toast.error(error.message || 'サーベイ集計の取得に失敗しました');
    });
}

function questionRow(q) {
    q = q || {};
    return `<div class="question-row border-bottom py-2">
        <div class="d-flex gap-2 mb-1">
            <input type="text" class="form-control form-control-sm bg-dark border-secondary text-light q-key" value="${SES.escapeHtml(q.key || '')}" placeholder="key">
            <input type="text" class="form-control form-control-sm bg-dark border-secondary text-light q-text" value="${SES.escapeHtml(q.text || '')}" placeholder="質問文">
            <select class="form-select form-select-sm bg-dark border-secondary text-light q-type" style="width:180px">
                ${['SCALE1_5','COMMENT','SCALE1_5_COMMENT'].map(t => `<option value="${t}" ${q.type === t ? 'selected' : ''}>${t}</option>`).join('')}
            </select>
            <label class="form-check-label ms-2"><input type="checkbox" class="form-check-input q-conf" ${q.confidential ? 'checked' : ''}> HR限定</label>
            <button type="button" class="btn btn-sm btn-outline-danger q-remove">-</button>
        </div></div>`;
}

async function createCampaign() {
    try {
        await SES.api.post('/api/surveys', {
            templateId: Number(document.getElementById('campaign-template').value),
            title: document.getElementById('campaign-title').value,
            periodFrom: document.getElementById('campaign-from').value || null,
            periodTo: document.getElementById('campaign-to').value || null
        });
        loadCampaigns();
        const modal = bootstrap.Modal.getInstance(document.getElementById('campaignModal'));
        if (modal) modal.hide();
    } catch (e) {
        console.error(e);
        SES.toast.error(e.message || 'サーベイの作成に失敗しました');
    }
}

async function createTemplate() {
    const questions = [];
    document.querySelectorAll('#question-rows .question-row').forEach(row => {
        const key = row.querySelector('.q-key').value;
        const text = row.querySelector('.q-text').value;
        if (!key || !text) return;
        questions.push({ key, text, type: row.querySelector('.q-type').value, confidential: row.querySelector('.q-conf').checked });
    });
    try {
        await SES.api.post('/api/surveys/templates', {
            templateKey: document.getElementById('template-key').value,
            title: document.getElementById('template-title').value,
            description: '',
            questions
        });
        loadTemplates();
        const modal = bootstrap.Modal.getInstance(document.getElementById('templateModal'));
        if (modal) modal.hide();
    } catch (e) {
        console.error(e);
        SES.toast.error(e.message || 'サーベイテンプレートの作成に失敗しました');
    }
}

function runSurveyAction(id, action) {
    SES.api.post('/api/surveys/' + encodeURIComponent(id) + '/' + action, {})
        .then(() => loadCampaigns())
        .catch(error => {
            console.error(error);
            SES.toast.error(error.message || 'サーベイの状態更新に失敗しました');
        });
}

document.addEventListener('click', (e) => {
    const remove = e.target.closest('.q-remove');
    if (remove) remove.closest('.question-row').remove();
});
