// プロフィール・スキル申請（engineer-self-service-portal-v2 A1）
document.addEventListener('click', (e) => {
    const remove = e.target.closest('.skill-remove, .career-remove');
    if (remove) remove.closest('.skill-row, .career-row').remove();
});

document.addEventListener('DOMContentLoaded', () => {
    window.myProfileData = null;
    window._masterSkills = [];
    loadMasterSkills();
    loadProfile();
    loadSkillSheet();
    loadRequests();

    document.getElementById('change-type').addEventListener('change', buildChangeForm);
    document.getElementById('change-submit').addEventListener('click', submitChangeRequest);
});

function loadMasterSkills() {
    SES.api.get('/api/my/profile/skill-options')
        .then(res => {
            window._masterSkills = Array.isArray(res) ? res : ((res && res.records) ? res.records : []);
            if (window.myProfileData) buildChangeForm();
        })
        .catch(() => { window._masterSkills = []; });
}

function loadProfile() {
    SES.api.get('/api/my/profile')
        .then(data => {
            window.myProfileData = data;
            renderProfile(data);
            renderContracts(data.contracts || []);
            renderSkills(data.skills || []);
            renderCareers(data.careers || []);
            buildChangeForm();
        })
        .catch(() => {});
}

function loadRequests() {
    SES.api.get('/api/my/change-requests', { current: 1, size: 100 })
        .then(data => renderRequests(data.records || []))
        .catch(() => {});
}

function renderProfile(data) {
    const b = (v, fallback) => v == null || v === '' ? (fallback || '—') : SES.escapeHtml(String(v));
    document.getElementById('profile-body').innerHTML = `
        <div class="row">
            <div class="col-6"><label class="text-muted">${SES.escapeHtml(SES.i18n.t('my.profile.field.name','氏名'))}</label> ${b(data.fullName)}</div>
            <div class="col-6"><label class="text-muted">${SES.escapeHtml(SES.i18n.t('my.profile.field.kana','カナ'))}</label> ${b(data.fullNameKana)}</div>
            <div class="col-6"><label class="text-muted">${SES.escapeHtml(SES.i18n.t('my.profile.field.station','最寄駅'))}</label> ${b(data.nearestStation)}</div>
            <div class="col-6"><label class="text-muted">${SES.escapeHtml(SES.i18n.t('my.profile.field.prefecture','都道府県'))}</label> ${b(data.prefecture)}</div>
            <div class="col-6"><label class="text-muted">${SES.escapeHtml(SES.i18n.t('my.profile.field.available','稼働可能日'))}</label> ${b(data.availableDate)}</div>
            <div class="col-6"><label class="text-muted">${SES.escapeHtml(SES.i18n.t('my.profile.field.experience','経験年数'))}</label> ${b(data.experienceYears)}</div>
            <div class="col-6"><label class="text-muted">${SES.escapeHtml(SES.i18n.t('my.profile.field.japanese','日本語レベル'))}</label> ${b(data.japaneseLevel)}</div>
            <div class="col-6"><label class="text-muted">${SES.escapeHtml(SES.i18n.t('my.profile.field.status','ステータス'))}</label> ${b(data.status)}</div>
        </div>`;
}

function renderContracts(contracts) {
    const el = document.getElementById('contracts-body');
    if (!contracts.length) { el.textContent = SES.i18n.t('my.profile.noData', 'データがありません'); return; }
    el.innerHTML = contracts.map(c => `
        <div class="border-bottom py-2">
            <div class="fw-bold">${SES.escapeHtml(c.contractNo || '')} <span class="badge bg-primary">${SES.escapeHtml(c.status || '')}</span></div>
            <div>${SES.escapeHtml(c.customerName || '')} / ${SES.escapeHtml(c.projectName || '')}</div>
            <div class="text-muted">${SES.escapeHtml(c.startDate || '')} 〜 ${SES.escapeHtml(c.endDate || '未定')}・${SES.escapeHtml(c.contractType || '')}</div>
            <div class="text-muted small">${SES.escapeHtml(c.workLocation || '')} / ${SES.escapeHtml(c.jobDescription || '')}</div>
        </div>`).join('');
}

function renderSkills(skills) {
    const el = document.getElementById('skills-body');
    if (!skills.length) { el.textContent = SES.i18n.t('my.profile.noData', 'データがありません'); return; }
    el.innerHTML = skills.map(s => `<div class="d-flex justify-content-between border-bottom py-1">
        <span>${SES.escapeHtml(s.skillName || '')}</span><span class="text-muted">${SES.escapeHtml(s.proficiency || '')}・${SES.escapeHtml(String(s.experienceYears ?? ''))}年</span>
    </div>`).join('');
}

function renderCareers(careers) {
    const el = document.getElementById('careers-body');
    if (!careers.length) { el.textContent = SES.i18n.t('my.profile.noData', 'データがありません'); return; }
    el.innerHTML = careers.map(c => `<div class="border-bottom py-2">
        <div class="fw-bold">${SES.escapeHtml(c.projectName || '')}</div>
        <div class="text-muted">${SES.escapeHtml(c.periodFrom || '')} 〜 ${SES.escapeHtml(c.periodTo || '')}・${SES.escapeHtml(c.clientIndustry || '')}・${SES.escapeHtml(c.role || '')}</div>
        <div class="small">${SES.escapeHtml(c.description || '')} ${SES.escapeHtml(c.techStack || '')}</div>
    </div>`).join('');
}

function renderRequests(requests) {
    const el = document.getElementById('requests-body');
    if (!requests || !requests.length) { el.innerHTML = '<tr><td colspan="4">—</td></tr>'; return; }
    el.innerHTML = requests.map(r => {
        const type = SES.i18n.t('my.changeRequest.type.' + r.requestType, r.requestType);
        const ops = r.status === '下書き'
            ? `<button class="btn btn-sm btn-outline-primary" data-id="${r.id}" data-action="submit">${SES.escapeHtml(SES.i18n.t('my.changeRequest.apply','申請'))}</button>`
            : (r.approvalStatus === 'returned' || r.approvalStatus === 'conflict')
                ? `<button class="btn btn-sm btn-outline-warning" data-id="${r.id}" data-action="resubmit">${SES.escapeHtml(SES.i18n.t('my.changeRequest.resubmit','再申請'))}</button>`
                : (r.status === '申請中' ? `<button class="btn btn-sm btn-outline-secondary" data-id="${r.id}" data-action="withdraw">${SES.escapeHtml(SES.i18n.t('my.changeRequest.withdraw','取下げ'))}</button>` : '&nbsp;');
        return `<tr><td>${SES.escapeHtml(type)}</td><td>${SES.escapeHtml(r.status)}${r.unappliedApproved ? ' <span class="badge bg-warning text-dark">未反映</span>' : ''}</td><td>${SES.escapeHtml(r.approvalStatus || '—')}</td><td>${ops}</td></tr>`;
    }).join('');
    el.querySelectorAll('button[data-action]').forEach(btn => {
        btn.addEventListener('click', () => changeRequestAction(btn.dataset.action, btn.dataset.id));
    });
}

async function changeRequestAction(action, id) {
    try {
        await SES.api.post('/api/my/change-requests/' + id + '/' + action, {});
        loadProfile();
    } catch (e) { /* SES.api toasts */ }
}

function buildChangeForm() {
    const data = window.myProfileData;
    const holder = document.getElementById('change-form');
    const type = document.getElementById('change-type').value;
    if (!data) return;
    let formHtml = '';
    if (type === 'profile.change') {
        const fields = [
            ['fullName', SES.i18n.t('my.profile.field.name', '氏名'), 'text'],
            ['fullNameKana', SES.i18n.t('my.profile.field.kana', '氏名カナ'), 'text'],
            ['email', SES.i18n.t('my.profile.field.email', 'メールアドレス'), 'email'],
            ['phone', SES.i18n.t('my.profile.field.phone', '電話番号'), 'text'],
            ['prefecture', SES.i18n.t('my.profile.field.prefecture', '都道府県'), 'text'],
            ['nearestStation', SES.i18n.t('my.profile.field.station', '最寄駅'), 'text'],
            ['railwayCompany', SES.i18n.t('my.profile.field.railway', '鉄道会社・路線'), 'text'],
            ['availableDate', SES.i18n.t('my.profile.field.available', '稼働可能日'), 'date'],
            ['experienceYears', SES.i18n.t('my.profile.field.experience', '経験年数'), 'number'],
            ['japaneseLevel', SES.i18n.t('my.profile.field.japanese', '日本語レベル'), 'text'],
            ['resumeSummary', SES.i18n.t('my.profile.field.summary', '経歴要約'), 'textarea'],
            ['expectedUnitPrice', SES.i18n.t('my.profile.field.expectedUnitPrice', '希望単価（円/月）'), 'number']
        ];
        formHtml = fields.map(([key, labelText, inputType]) => {
            const val = data[key] ?? '';
            const input = inputType === 'textarea'
                ? `<textarea class="form-control bg-dark border-secondary text-light" data-field="${key}" rows="3">${SES.escapeHtml(String(val))}</textarea>`
                : `<input type="${inputType}" class="form-control bg-dark border-secondary text-light" data-field="${key}" value="${SES.escapeHtml(String(val))}">`;
            return `<div class="mb-2"><label class="form-label text-muted small">${SES.escapeHtml(labelText)}</label>${input}</div>`;
        }).join('');
    } else if (type === 'skill.change') {
        const rows = (data.skills || []).map(s => skillRow(s));
        formHtml = `<div class="mb-2"><label class="form-label text-muted small">${SES.escapeHtml(SES.i18n.t('my.changeRequest.skillList', 'スキル一覧（熟練度・経験年数を更新または新規追加）'))}</label><div id="skill-rows">${rows.join('') || skillRow()}</div>
            <button type="button" class="btn btn-sm btn-outline-secondary mt-2" id="skill-add"><i class="bi bi-plus-lg me-1"></i>${SES.escapeHtml(SES.i18n.t('my.changeRequest.addSkill', 'スキルを追加'))}</button></div>`;
    } else {
        const rows = (data.careers || []).map(c => careerRow(c));
        formHtml = `<div class="mb-2"><label class="form-label text-muted small">${SES.escapeHtml(SES.i18n.t('my.changeRequest.careerList', '職務経歴（差し替え）'))}</label><div id="career-rows">${rows.join('') || careerRow()}</div>
            <button type="button" class="btn btn-sm btn-outline-secondary mt-2" id="career-add"><i class="bi bi-plus-lg me-1"></i>${SES.escapeHtml(SES.i18n.t('my.changeRequest.addCareer', '経歴を追加'))}</button></div>`;
    }

    const commonInputs = `
        <div class="mt-3 pt-2 border-top border-secondary">
            <div class="mb-2">
                <label class="form-label text-muted small">${SES.escapeHtml(SES.i18n.t('my.changeRequest.reason', '申請理由・備考'))}</label>
                <input type="text" id="change-reason" class="form-control form-control-sm bg-dark border-secondary text-light" maxlength="1000" placeholder="${SES.escapeHtml(SES.i18n.t('my.changeRequest.reason.placeholder', '変更理由（任意）'))}">
            </div>
            <div class="mb-2">
                <label class="form-label text-muted small">${SES.escapeHtml(SES.i18n.t('my.changeRequest.attachment', '証明書類・添付ファイル（任意）'))}</label>
                <div class="input-group input-group-sm">
                    <input type="file" id="change-attachment-file" class="form-control form-control-sm bg-dark border-secondary text-light">
                    <input type="number" id="change-attachment-doc-id" class="form-control form-control-sm bg-dark border-secondary text-light" style="max-width:140px" placeholder="${SES.escapeHtml(SES.i18n.t('my.changeRequest.attachment.placeholder', '文書ID'))}">
                </div>
            </div>
        </div>`;

    holder.innerHTML = formHtml + commonInputs;

    if (type === 'skill.change') {
        holder.querySelector('#skill-add').addEventListener('click', () => {
            holder.querySelector('#skill-rows').insertAdjacentHTML('beforeend', skillRow());
        });
    } else if (type === 'career.change') {
        holder.querySelector('#career-add').addEventListener('click', () => {
            holder.querySelector('#career-rows').insertAdjacentHTML('beforeend', careerRow());
        });
    }
}

function skillRow(s) {
    s = s || {};
    const profs = [
        { code: '初級', label: SES.i18n.t('my.skill.proficiency.beginner', '初級') },
        { code: '中級', label: SES.i18n.t('my.skill.proficiency.intermediate', '中級') },
        { code: '上級', label: SES.i18n.t('my.skill.proficiency.advanced', '上級') }
    ];
    const opts = profs.map(p => `<option value="${p.code}" ${s.proficiency === p.code ? 'selected' : ''}>${SES.escapeHtml(p.label)}</option>`).join('');
    const skillOptions = (window._masterSkills || []).map(m =>
        `<option value="${m.id}" ${s.skillId === m.id ? 'selected' : ''}>${SES.escapeHtml(m.name || m.skillName || '')}</option>`
    ).join('');

    const selectOrInput = (window._masterSkills && window._masterSkills.length > 0)
        ? `<select class="form-select form-select-sm skill-select"><option value="">-- ${SES.escapeHtml(SES.i18n.t('my.changeRequest.addSkill', 'スキル選択'))} --</option>${skillOptions}</select>`
        : `<input type="number" class="form-control form-control-sm skill-id" value="${SES.escapeHtml(String(s.skillId ?? ''))}" placeholder="Skill ID">`;

    return `<div class="row g-2 mb-1 skill-row align-items-center">
        <div class="col-12 col-sm-6">${selectOrInput}</div>
        <input type="hidden" class="skill-id" value="${SES.escapeHtml(String(s.skillId ?? ''))}">
        <div class="col-6 col-sm-3"><select class="form-select form-select-sm skill-proficiency">${opts}</select></div>
        <div class="col-4 col-sm-2"><input type="number" class="form-control form-control-sm skill-years" value="${SES.escapeHtml(String(s.experienceYears ?? ''))}" placeholder="${SES.escapeHtml(SES.i18n.t('my.skill.yearsPlaceholder', '年数'))}"></div>
        <div class="col-2 col-sm-1"><button type="button" class="btn btn-sm btn-outline-danger skill-remove">-</button></div>
    </div>`;
}

function careerRow(c) {
    c = c || {};
    const f = (k, ph, type) => `<input type="${type || 'text'}" class="form-control form-control-sm bg-dark border-secondary text-light career-${k}" value="${SES.escapeHtml(String(c[k] ?? ''))}" placeholder="${SES.escapeHtml(SES.i18n.t('my.changeRequest.career.' + k, ph))}">`;
    return `<div class="career-row border-bottom border-secondary py-2">
        <div class="d-flex gap-2 mb-1">${f('periodFrom', '開始', 'date')} ${f('periodTo', '終了', 'date')} ${f('projectName', '案件名')}</div>
        <div class="d-flex gap-2 mb-1">${f('clientIndustry', '業界')} ${f('role', '役割')} <input type="number" class="form-control form-control-sm bg-dark border-secondary text-light career-teamSize" style="width:90px" value="${SES.escapeHtml(String(c.teamSize ?? ''))}" placeholder="${SES.escapeHtml(SES.i18n.t('my.changeRequest.career.teamSize', '規模'))}"></div>
        ${f('techStack', '使用技術')}
        <textarea class="form-control form-control-sm bg-dark border-secondary text-light career-description mt-1" rows="2" placeholder="${SES.escapeHtml(SES.i18n.t('my.changeRequest.career.description', '業務概要'))}">${SES.escapeHtml(String(c.description ?? ''))}</textarea>
        <button type="button" class="btn btn-sm btn-outline-danger mt-1 career-remove">- ${SES.escapeHtml(SES.i18n.t('common.delete', '削除'))}</button>
    </div>`;
}

async function submitChangeRequest() {
    const type = document.getElementById('change-type').value;
    const reason = document.getElementById('change-reason') ? document.getElementById('change-reason').value.trim() : null;
    let attachmentDocumentId = null;

    const fileInput = document.getElementById('change-attachment-file');
    if (fileInput && fileInput.files && fileInput.files[0]) {
        const formData = new FormData();
        formData.append('file', fileInput.files[0]);
        try {
            const uploadRes = await fetch('/api/my/change-requests/attachment', {
                method: 'POST',
                headers: SES.csrf.header(),
                body: formData
            }).then(r => r.json());
            if (uploadRes.code === 200 && uploadRes.data && uploadRes.data.documentId) {
                attachmentDocumentId = uploadRes.data.documentId;
            } else {
                SES.toast.error(uploadRes.message || SES.i18n.t('error.file.uploadFailed', 'ファイルのアップロードに失敗しました'));
                return;
            }
        } catch (e) {
            SES.toast.error(SES.i18n.t('error.file.uploadFailed', 'ファイルのアップロードに失敗しました'));
            return;
        }
    } else {
        const attachmentDocIdRaw = document.getElementById('change-attachment-doc-id') ? document.getElementById('change-attachment-doc-id').value.trim() : null;
        attachmentDocumentId = attachmentDocIdRaw ? Number(attachmentDocIdRaw) : null;
    }

    if (type === 'profile.change') {
        const payload = {};
        document.querySelectorAll('#change-form [data-field]').forEach(inp => {
            let val = inp.value;
            if (inp.type === 'number' && val !== '') val = Number(val);
            payload[inp.dataset.field] = val;
        });
        delete emptyStrings(payload);
        await doCreate(type, payload, reason, attachmentDocumentId);
    } else if (type === 'skill.change') {
        const skills = [];
        document.querySelectorAll('#change-form .skill-row').forEach(row => {
            const selectEl = row.querySelector('.skill-select');
            const idInput = row.querySelector('.skill-id');
            const skillId = selectEl ? (selectEl.value || idInput.value) : idInput.value;
            if (!skillId) return;
            skills.push({
                skillId: Number(skillId),
                proficiency: row.querySelector('.skill-proficiency').value,
                experienceYears: row.querySelector('.skill-years').value === '' ? null : Number(row.querySelector('.skill-years').value)
            });
        });
        await doCreate(type, { skills }, reason, attachmentDocumentId);
    } else {
        const careers = [];
        document.querySelectorAll('#change-form .career-row').forEach(row => {
            const projectName = row.querySelector('.career-projectName').value;
            if (!projectName) return;
            careers.push({
                periodFrom: row.querySelector('.career-periodFrom').value || null,
                periodTo: row.querySelector('.career-periodTo').value || null,
                projectName,
                clientIndustry: row.querySelector('.career-clientIndustry').value || null,
                role: row.querySelector('.career-role').value || null,
                description: row.querySelector('.career-description').value || null,
                techStack: row.querySelector('.career-techStack').value || null,
                teamSize: row.querySelector('.career-teamSize').value === '' ? null : Number(row.querySelector('.career-teamSize').value)
            });
        });
        await doCreate(type, { careers }, reason, attachmentDocumentId);
    }
}

function emptyStrings(obj) {
    Object.keys(obj).forEach(k => { if (obj[k] === '') delete obj[k]; });
    return obj;
}

async function doCreate(type, payload, reason, attachmentDocumentId) {
    try {
        await SES.api.post('/api/my/change-requests', {
            requestType: type,
            payload,
            reason: reason || null,
            attachmentDocumentId: attachmentDocumentId || null
        });
        loadProfile();
        loadRequests();
        const modal = bootstrap.Modal.getInstance(document.getElementById('changeModal'));
        if (modal) modal.hide();
        Toast.success(SES.i18n.t('my.changeRequest.created', '変更申請の下書きを作成しました'));
    } catch (e) { /* SES.api toasts */ }
}

// ---- スキルシート確認 ----
function loadSkillSheet() {
    SES.api.get('/api/my/profile/skill-sheet').then(renderSkillSheet).catch(() => {});
}

function renderSkillSheet(preview) {
    const el = document.getElementById('skillsheet-body');
    if (!el) return;
    const status = preview.current
        ? `<span class="badge bg-success">${SES.escapeHtml(SES.i18n.t('my.skillSheet.current','最新版を確認済み'))}</span>`
        : `<span class="badge bg-warning text-dark">${SES.escapeHtml(SES.i18n.t('my.skillSheet.stale','未確認（客先提出前チェック対象）'))}</span>`;
    const confirmed = preview.confirmedAt
        ? SES.i18n.t('my.skillSheet.confirmedAt', '確認日時: {0}').replace('{0}', preview.confirmedAt)
        : SES.i18n.t('my.skillSheet.notConfirmed', '未確認');
    el.innerHTML = `<p>${status}</p><p class="text-muted small">${SES.escapeHtml(confirmed)}</p>
        <button type="button" class="btn btn-sm btn-outline-primary" id="skillsheet-confirm-btn">${SES.escapeHtml(SES.i18n.t('my.skillSheet.confirm','この内容で確認する（客先提出前）'))}</button>`;
    document.getElementById('skillsheet-confirm-btn').addEventListener('click', () => confirmSkillSheet(preview.fingerprint));
    window._skillSheetFingerprint = preview.fingerprint;
}

async function confirmSkillSheet(fingerprint) {
    try {
        await SES.api.post('/api/my/profile/skill-sheet/confirm', { fingerprint });
        loadSkillSheet();
        Toast.success(SES.i18n.t('my.skillSheet.confirmed', '確認を記録しました'));
    } catch (e) { /* SES.api toasts */ }
}
