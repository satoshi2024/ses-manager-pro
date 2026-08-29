// サーベイ回答（engineer-self-service-portal-v2 B2）
document.addEventListener('DOMContentLoaded', () => {
    loadCampaigns();
});

let currentCampaign = null;

function loadCampaigns() {
    SES.api.get('/api/my/surveys')
        .then(list => renderCampaigns(list || []))
        .catch(error => {
            console.error(error);
            SES.toast.error(error.message || 'サーベイ一覧の取得に失敗しました');
        });
}

function renderCampaigns(list) {
    const el = document.getElementById('campaign-list');
    if (!list.length) { el.innerHTML = '<div class="text-muted small">' + SES.escapeHtml(SES.i18n.t('my.survey.noCampaign', '回答対象のサーベイはありません')) + '</div>'; return; }
    el.innerHTML = list.map(c => `<button class="list-group-item list-group-item-action bg-dark text-light border-secondary" data-id="${c.id}">
        <div class="fw-bold">${SES.escapeHtml(c.title)}</div>
        <div class="small text-muted">${SES.escapeHtml(c.periodFrom || '')} 〜 ${SES.escapeHtml(c.periodTo || '')}</div>
    </button>`).join('');
    el.querySelectorAll('button[data-id]').forEach(btn => btn.addEventListener('click', () => openCampaign(Number(btn.dataset.id))));
}

function openCampaign(campaignId) {
    SES.api.get('/api/my/surveys/' + campaignId)
        .then(detail => renderDetail(detail))
        .catch(error => {
            console.error(error);
            SES.toast.error(error.message || 'サーベイ詳細の取得に失敗しました');
        });
}

function renderDetail(detail) {
    currentCampaign = detail;
    document.getElementById('answer-title').textContent = detail.title;
    document.getElementById('answer-card').style.display = '';
    const body = document.getElementById('answer-body');
    body.innerHTML = `<div class="form-check mb-3">
        <input class="form-check-input" type="checkbox" id="survey-consent">
        <label class="form-check-label" for="survey-consent">${SES.escapeHtml(SES.i18n.t('my.survey.consent', '回答を送信することに同意します'))}</label>
    </div>`;
    (detail.questions || []).forEach((q, i) => {
        const scale = q.type === 'SCALE1_5' || q.type === 'SCALE1_5_COMMENT';
        const comment = q.type === 'COMMENT' || q.type === 'SCALE1_5_COMMENT';
        const current = detail.answers && detail.answers[q.key] != null ? detail.answers[q.key] : null;
        let html = `<div class="border-bottom py-2" data-qkey="${SES.escapeHtml(q.key)}">
            <label class="fw-bold">${i + 1}. ${SES.escapeHtml(q.text)}</label>`;
        if (scale) {
            const opts = [1, 2, 3, 4, 5].map(v =>
                `<label class="me-2"><input type="radio" name="q-${SES.escapeHtml(q.key)}" value="${v}" ${String(current) === String(v) ? 'checked' : ''}> ${v}</label>`).join('');
            html += `<div class="mt-1">${opts}</div>`;
        }
        if (comment) {
            html += `<textarea class="form-control form-control-sm bg-dark border-secondary text-light mt-1" rows="2" placeholder="${SES.escapeHtml(SES.i18n.t('my.survey.comment','コメント（任意）'))}"></textarea>`;
        }
        html += '</div>';
        body.insertAdjacentHTML('beforeend', html);
    });
    const submit = document.createElement('button');
    submit.type = 'button';
    submit.className = 'btn btn-primary mt-3';
    submit.textContent = SES.i18n.t('my.survey.submit', '回答を送信');
    submit.addEventListener('click', submitAnswers);
    body.appendChild(submit);
    if (detail.consentFlag) document.getElementById('survey-consent').checked = true;
}

async function submitAnswers() {
    const consent = document.getElementById('survey-consent').checked;
    const answers = [];
    document.querySelectorAll('#answer-body [data-qkey]').forEach(row => {
        const key = row.dataset.qkey;
        const radio = row.querySelector('input[type="radio"]:checked');
        const comment = row.querySelector('textarea');
        answers.push({
            questionKey: key,
            answerValue: radio ? Number(radio.value) : null,
            comment: comment ? comment.value : null,
            commentVisibility: 'PUBLIC'
        });
    });
    try {
        await SES.api.post('/api/my/surveys/' + currentCampaign.campaignId + '/answers', { consent, answers });
        Toast.success(SES.i18n.t('my.survey.submitted', '回答を送信しました'));
        loadCampaigns();
    } catch (e) {
        console.error(e);
        SES.toast.error(e.message || 'サーベイ回答の送信に失敗しました');
    }
}
