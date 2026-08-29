/* 資格・学習・skill gap管理。検索条件はlist/detail/count/exportで共有する。 */
(function () {
    'use strict';

    const pageSize = 10;
    let pageData = null;
    let certificationMasters = [];
    let trainingCourses = [];
    let skillTags = [];
    let editingCourseVersion = null;

    function value(id) { return document.getElementById(id)?.value || ''; }

    function params() {
        const result = { current: pageData ? pageData.current : 1, size: pageSize };
        const fields = { engineerName: value('cert-gap-name'), engineerStatus: value('cert-gap-status'), lifecycleState: value('cert-gap-lifecycle'), asOf: value('cert-gap-asof'), projectId: value('cert-gap-project'), demandSource: value('cert-gap-demand') };
        Object.keys(fields).forEach(function (key) { if (fields[key]) result[key] = fields[key]; });
        return result;
    }

    function esc(text) {
        return SES.escapeHtml(text == null ? '' : String(text));
    }

    function showCertificationError(error, fallback) {
        console.error(error);
        SES.toast.error(error && error.message ? error.message : fallback);
    }

    window.loadCertificationLearningGap = async function (page) {
        const query = params(); query.current = page || 1;
        try {
            const data = await SES.api.get('/api/certification-learning-gap', query);
            pageData = data;
            render(data);
        } catch (e) {
            showCertificationError(e, '資格・学習データの取得に失敗しました');
            document.getElementById('cert-gap-table-body').innerHTML = '<tr><td colspan="7" class="text-center text-danger py-4">読み込みに失敗しました</td></tr>';
        }
    };

    function render(data) {
        const body = document.getElementById('cert-gap-table-body');
        const records = (data && data.records) || [];
        if (!records.length) {
            body.innerHTML = '<tr><td colspan="7" class="text-center text-muted py-4">対象データがありません</td></tr>';
        } else {
            body.innerHTML = records.map(function (row) {
                const gap = row.gapStatus ? (row.gapStatus === 'OK' ? '確認済' : esc(row.gapStatus)) : '-';
                return '<tr><td class="ps-4"><a href="#" class="text-info" onclick="showCertificationLearningGapDetail(' + row.engineerId + '); return false;">' + esc(row.engineerName) + '</a><div class="small text-muted">ID: ' + row.engineerId + '</div></td>'
                    + '<td>' + esc(row.engineerStatus || '-') + '</td><td>' + lifecycleLabel(row.lifecycleState) + '</td>'
                    + '<td>' + (row.certifications || []).length + '件</td><td>' + (row.trainings || []).length + '件</td><td class="cert-gap-summary">' + gap + ' / ' + ((row.skillGaps || []).filter(function (item) { return item.gap; }).length) + '件</td>'
                    + '<td class="text-end pe-4"><div class="d-flex flex-wrap justify-content-end align-items-center gap-1"><button class="btn btn-sm btn-outline-info" title="詳細" aria-label="詳細" onclick="showCertificationLearningGapDetail(' + row.engineerId + ')"><i class="bi bi-eye" aria-hidden="true"></i><span class="visually-hidden">詳細</span></button></div></td></tr>';
            }).join('');
        }
        renderPagination(data);
    }

    function lifecycleLabel(value) {
        return { ACTIVE: '在籍', ON_LEAVE: '休職', RESIGNED: '退職', PENDING: '手続中' }[value] || esc(value || '-');
    }

    function renderPagination(data) {
        const total = data ? data.total : 0;
        const current = data ? data.current : 1;
        const size = data ? data.size : pageSize;
        const pages = data ? data.pages : 0;
        document.getElementById('cert-gap-page-info').textContent = total ? '全 ' + total + ' 件中 ' + ((current - 1) * size + 1) + '-' + Math.min(current * size, total) + ' 件を表示' : '対象 0 件';
        let html = '';
        if (pages > 1) {
            for (let i = 1; i <= pages; i++) {
                if (i <= 3 || i >= pages - 2 || Math.abs(i - current) <= 1) {
                    html += '<li class="page-item ' + (i === current ? 'active' : '') + '"><a class="page-link bg-dark border-secondary text-light" href="#" onclick="loadCertificationLearningGap(' + i + '); return false;">' + i + '</a></li>';
                }
            }
        }
        document.getElementById('cert-gap-pagination').innerHTML = html;
    }

    window.showCertificationLearningGapDetail = async function (engineerId) {
        const query = params(); delete query.current; delete query.size;
        try {
            const row = await SES.api.get('/api/certification-learning-gap/' + encodeURIComponent(engineerId), query);
            document.getElementById('cert-gap-detail-title').textContent = String(row.engineerName || '-') + ' - 資格・学習・gap詳細';
            document.getElementById('cert-gap-detail-body').innerHTML = detailHtml(row);
            bootstrap.Modal.getOrCreateInstance(document.getElementById('cert-gap-detail-modal')).show();
        } catch (e) { showCertificationError(e, '資格・学習詳細の取得に失敗しました'); }
    };

    function detailHtml(row) {
        const certs = (row.certifications || []).map(function (item) {
            const number = item.certificateNumber ? esc(item.certificateNumber) : esc(item.certificateNumberMasked || '未登録');
            const evidences = (item.evidences || []).map(function (evidence) {
                return '<a class="d-block" href="/api/certification-learning-gap/' + encodeURIComponent(row.engineerId)
                    + '/certifications/' + encodeURIComponent(item.id) + '/evidence/' + encodeURIComponent(evidence.documentId)
                    + '/versions/' + encodeURIComponent(evidence.versionNo) + '/download">' + esc(evidence.originalName || '証憑') + '</a>';
            }).join('');
            const state = item.recordState || '';
            let actions = '';
            if (document.getElementById('cert-gap-cert-write') && ['DRAFT', 'SUBMITTED', 'VERIFIED'].indexOf(state) >= 0) {
                if ((item.evidences || []).length) {
                    const evidence = item.evidences[0];
                    actions += '<button type="button" class="btn btn-sm btn-outline-success" onclick="verifyCertificationRecord(' + item.id + ',' + (item.version ?? 0) + ',' + evidence.documentId + ',' + evidence.documentVersionId + ',decodeURIComponent(\'' + encodeURIComponent(evidence.sha256 || '') + '\'))">証憑確認</button>';
                }
                actions += '<button type="button" class="btn btn-sm btn-outline-danger" onclick="rejectCertificationRecord(' + item.id + ',' + (item.version ?? 0) + ')">却下</button>';
            }
            return '<tr><td>' + esc(item.certificationDisplayName || '-') + '</td><td>' + esc(item.effectiveState || state || '-') + '</td><td>' + esc(item.expiresOn || '-') + '</td><td>' + number + '</td><td>' + (evidences || '-') + '</td><td><div class="d-flex flex-wrap justify-content-end align-items-center gap-1">' + actions + '</div></td></tr>';
        }).join('');
        const plans = (row.trainings || []).map(function (item) {
            let actions = '';
            if (item.status === 'SUBMITTED' && document.getElementById('cert-gap-training-write')) {
                actions = '<div class="d-flex flex-wrap justify-content-end align-items-center gap-1"><button type="button" class="btn btn-sm btn-outline-success" onclick="approveTrainingPlan(' + item.planId + ')">承認</button><button type="button" class="btn btn-sm btn-outline-danger" onclick="rejectTrainingPlan(' + item.planId + ')">却下</button></div>';
            }
            return '<tr><td>' + esc(item.title || '-') + '</td><td>' + esc(item.status || '-') + '</td><td>' + esc(item.courseName || '-') + '</td><td>' + actions + '</td></tr>';
        }).join('');
        const gaps = (row.skillGaps || []).map(function (item) { return '<li>' + esc(item.canonicalName || item.requestedName || item.key) + '：' + (item.gap ? 'gap' : '充足') + (item.unknown ? '（未知skill）' : '') + '</li>'; }).join('');
        const aiButton = value('cert-gap-project') ? '<button type="button" class="btn btn-sm btn-outline-warning mb-3" onclick="showCertificationLearningGapAiCandidate(' + row.engineerId + ')"><i class="bi bi-stars me-1"></i>AI候補を表示</button>' : '';
        return '<p class="text-muted">状態: ' + esc(row.engineerStatus || '-') + ' / ライフサイクル: ' + lifecycleLabel(row.lifecycleState) + '</p>' + aiButton
            + '<h6>資格履歴</h6><div class="table-responsive"><table class="table table-dark table-sm"><thead><tr><th>資格</th><th>状態</th><th>期限</th><th>番号</th><th>証憑</th><th>操作</th></tr></thead><tbody>' + (certs || '<tr><td colspan="6">資格履歴なし</td></tr>') + '</tbody></table></div>'
            + '<h6 class="mt-4">学習計画・受講</h6><div class="table-responsive"><table class="table table-dark table-sm"><thead><tr><th>計画</th><th>状態</th><th>コース</th><th>操作</th></tr></thead><tbody>' + (plans || '<tr><td colspan="4">学習計画なし</td></tr>') + '</tbody></table></div>'
            + '<h6 class="mt-4">skill gap</h6><ul>' + (gaps || '<li>gapデータなし</li>') + '</ul>';
    }

    window.showCertificationLearningGapAiCandidate = async function (engineerId) {
        const projectId = value('cert-gap-project');
        if (!projectId) return;
        const query = { projectId: projectId, asOf: value('cert-gap-asof'), demandSource: value('cert-gap-demand') };
        Object.keys(query).forEach(function (key) { if (!query[key]) delete query[key]; });
        try {
            const result = await SES.api.get('/api/certification-learning-gap/' + encodeURIComponent(engineerId) + '/ai-candidates', query);
            const candidate = result.aiCandidate;
            const rule = result.ruleGap || {};
            Swal.fire({ title: '学習course候補', html: '<p>rule gap: ' + esc(rule.status || '-') + '</p><p>as-of: ' + esc(rule.asOf || '-') + '</p><p>候補: ' + esc(candidate ? (candidate.aiSuggestedCourseIds || []).join(', ') : 'AI停止または履歴不足') + '</p><p class="text-muted small">AIは評価・配置・採否を確定しません。</p>', confirmButtonText: '閉じる' });
        } catch (e) { showCertificationError(e, '学習course候補の取得に失敗しました'); }
    };

    window.verifyCertificationRecord = async function (recordId, version, documentId, documentVersionId, evidenceHash) {
        const result = await Swal.fire({ title: '証憑を確認してACTIVE化', text: '指定版のCLEAN証憑を確認します。', showCancelButton: true, confirmButtonText: '確認', cancelButtonText: '戻る' });
        if (!result.isConfirmed) return;
        try {
            await SES.api.post('/api/certification-learning-gap/certifications/' + recordId + '/verify', { expectedVersion: version, evidenceDocumentId: documentId, evidenceDocumentVersionId: documentVersionId, evidenceHash: evidenceHash });
            bootstrap.Modal.getInstance(document.getElementById('cert-gap-detail-modal')).hide(); await loadCertificationLearningGap(pageData ? pageData.current : 1);
        } catch (e) { showCertificationError(e, '証憑処理に失敗しました'); }
    };

    window.rejectCertificationRecord = async function (recordId, version) {
        const result = await Swal.fire({ title: '資格申請を却下', input: 'text', inputLabel: '理由', showCancelButton: true, confirmButtonText: '却下', cancelButtonText: '戻る' });
        if (!result.isConfirmed) return;
        try {
            await SES.api.post('/api/certification-learning-gap/certifications/' + recordId + '/reject', { expectedVersion: version, reason: result.value });
            bootstrap.Modal.getInstance(document.getElementById('cert-gap-detail-modal')).hide(); await loadCertificationLearningGap(pageData ? pageData.current : 1);
        } catch (e) { showCertificationError(e, '資格申請の却下に失敗しました'); }
    };

    window.approveTrainingPlan = async function (planId) {
        try { await SES.api.post('/api/certification-learning-gap/training-plans/' + planId + '/approve', {}); await loadCertificationLearningGap(pageData ? pageData.current : 1); } catch (e) { showCertificationError(e, '学習計画の承認に失敗しました'); }
    };
    window.rejectTrainingPlan = async function (planId) {
        const result = await Swal.fire({ title: '学習計画を却下', input: 'text', inputLabel: '理由', showCancelButton: true, confirmButtonText: '却下', cancelButtonText: '戻る' });
        if (!result.isConfirmed) return;
        try { await SES.api.post('/api/certification-learning-gap/training-plans/' + planId + '/reject', { comment: result.value }); await loadCertificationLearningGap(pageData ? pageData.current : 1); } catch (e) { showCertificationError(e, '学習計画の却下に失敗しました'); }
    };

    function loadCatalogs() {
        const panel = document.getElementById('cert-gap-catalog-panel');
        if (!panel) return;
        Promise.all([SES.api.get('/api/certification-learning-gap/masters/certifications', { includeInactive: true }), SES.api.get('/api/certification-learning-gap/masters/courses', { includeInactive: true }), SES.api.get('/api/skill-tags')]).then(function (data) {
            certificationMasters = data[0] || []; trainingCourses = data[1] || []; skillTags = data[2] || [];
            renderMasters(); renderCourses();
        }).catch(function (error) { showCertificationError(error, '資格・学習catalogの取得に失敗しました'); document.getElementById('cert-gap-master-body').innerHTML = '<tr><td colspan="5" class="text-danger">catalogを読み込めません</td></tr>'; });
    }

    function renderMasters() {
        document.getElementById('cert-gap-master-body').innerHTML = certificationMasters.map(function (item) {
            return '<tr><td>' + esc(item.displayName || '-') + '</td><td>' + esc(item.issuerDisplay || '-') + '</td><td>' + esc(item.expiryType || 'NONE') + (item.expiryMonths ? ' / ' + esc(item.expiryMonths) + 'か月' : '') + '</td><td>' + (item.activeFlag === 1 ? '有効' : '無効') + '</td><td><div class="d-flex flex-wrap justify-content-end align-items-center gap-1"><button class="btn btn-sm btn-outline-info" onclick="openCertificationMasterForm(' + item.id + ')">編集</button>' + (item.activeFlag === 1 ? '<button class="btn btn-sm btn-outline-danger" onclick="deactivateCertificationMaster(' + item.id + ')">無効化</button>' : '') + '</div></td></tr>';
        }).join('') || '<tr><td colspan="5" class="text-muted">資格masterはありません</td></tr>';
    }

    function renderCourses() {
        document.getElementById('cert-gap-course-body').innerHTML = trainingCourses.map(function (item) {
            return '<tr><td>' + esc(item.name || '-') + '</td><td>' + esc(item.provider || '-') + '</td><td>' + esc(item.costJpy == null ? '-' : item.costJpy + '円') + '</td><td>' + (item.skills || []).map(function (skill) { return esc(skill.skillName || ('ID:' + skill.skillId)); }).join('、') + '</td><td><div class="d-flex flex-wrap justify-content-end align-items-center gap-1"><button class="btn btn-sm btn-outline-info" onclick="openTrainingCourseForm(' + item.id + ')">編集</button>' + (item.activeFlag === 1 ? '<button class="btn btn-sm btn-outline-danger" onclick="deactivateTrainingCourse(' + item.id + ')">無効化</button>' : '') + '</div></td></tr>';
        }).join('') || '<tr><td colspan="5" class="text-muted">courseはありません</td></tr>';
    }

    window.openCertificationMasterForm = async function (id) {
        try {
            const item = id ? await SES.api.get('/api/certification-learning-gap/masters/certifications/' + id) : {};
            document.getElementById('cert-gap-master-id').value = item.id || '';
            document.getElementById('cert-gap-master-name').value = item.displayName || '';
            document.getElementById('cert-gap-master-issuer').value = item.issuerDisplay || '';
            document.getElementById('cert-gap-master-code').value = item.externalCode || '';
            document.getElementById('cert-gap-master-expiry-type').value = item.expiryType || 'NONE';
            document.getElementById('cert-gap-master-expiry-months').value = item.expiryMonths || '';
            document.getElementById('cert-gap-master-rule-version').value = item.ruleVersion || 1;
            document.getElementById('cert-gap-master-active').value = item.activeFlag == null ? 1 : item.activeFlag;
            bootstrap.Modal.getOrCreateInstance(document.getElementById('cert-gap-master-modal')).show();
        } catch (e) { showCertificationError(e, '資格masterの取得に失敗しました'); }
    };

    window.saveCertificationMaster = async function () {
        const id = document.getElementById('cert-gap-master-id').value;
        const expiryType = document.getElementById('cert-gap-master-expiry-type').value;
        const payload = { displayName: document.getElementById('cert-gap-master-name').value, issuerDisplay: document.getElementById('cert-gap-master-issuer').value, externalCode: document.getElementById('cert-gap-master-code').value, expiryType: expiryType, expiryMonths: expiryType === 'FIXED_MONTHS' ? Number(document.getElementById('cert-gap-master-expiry-months').value) : null, ruleVersion: Number(document.getElementById('cert-gap-master-rule-version').value || 1), activeFlag: Number(document.getElementById('cert-gap-master-active').value) };
        try { if (id) await SES.api.put('/api/certification-learning-gap/masters/certifications/' + id, payload); else await SES.api.post('/api/certification-learning-gap/masters/certifications', payload); bootstrap.Modal.getInstance(document.getElementById('cert-gap-master-modal')).hide(); loadCatalogs(); } catch (e) { showCertificationError(e, '資格masterの保存に失敗しました'); }
    };
    window.deactivateCertificationMaster = async function (id) { const result = await Swal.fire({ title: '資格masterを無効化', showCancelButton: true, confirmButtonText: '無効化', cancelButtonText: '戻る' }); if (!result.isConfirmed) return; try { await SES.api.delete('/api/certification-learning-gap/masters/certifications/' + id); loadCatalogs(); } catch (e) { showCertificationError(e, '資格masterの無効化に失敗しました'); } };

    window.openTrainingCourseForm = async function (id) {
        try {
            editingCourseVersion = null;
            const item = id ? await SES.api.get('/api/certification-learning-gap/masters/courses/' + id) : {};
            if (id) editingCourseVersion = item.version;
            document.getElementById('cert-gap-course-id').value = item.id || '';
            document.getElementById('cert-gap-course-name').value = item.name || '';
            document.getElementById('cert-gap-course-provider').value = item.provider || '';
            document.getElementById('cert-gap-course-cost').value = item.costJpy == null ? '' : item.costJpy;
            document.getElementById('cert-gap-course-period').value = item.periodDays || '';
            document.getElementById('cert-gap-course-capacity').value = item.capacity || '';
            document.getElementById('cert-gap-course-description').value = item.description || '';
            const select = document.getElementById('cert-gap-course-skills');
            select.innerHTML = skillTags.map(function (tag) { return '<option value="' + esc(tag.id) + '">' + esc(tag.category || '') + ' / ' + esc(tag.skillName || '') + '</option>'; }).join('');
            const selected = new Set((item.skills || []).map(function (skill) { return String(skill.skillId); }));
            Array.from(select.options).forEach(function (option) { option.selected = selected.has(option.value); });
            bootstrap.Modal.getOrCreateInstance(document.getElementById('cert-gap-course-modal')).show();
        } catch (e) { showCertificationError(e, '学習courseの取得に失敗しました'); }
    };
    window.saveTrainingCourse = async function () {
        const id = document.getElementById('cert-gap-course-id').value;
        const payload = { provider: document.getElementById('cert-gap-course-provider').value, name: document.getElementById('cert-gap-course-name').value, description: document.getElementById('cert-gap-course-description').value, costJpy: Number(document.getElementById('cert-gap-course-cost').value), periodDays: document.getElementById('cert-gap-course-period').value ? Number(document.getElementById('cert-gap-course-period').value) : null, capacity: document.getElementById('cert-gap-course-capacity').value ? Number(document.getElementById('cert-gap-course-capacity').value) : null, version: editingCourseVersion, requiredSkillIds: Array.from(document.getElementById('cert-gap-course-skills').selectedOptions).map(function (option) { return Number(option.value); }) };
        try { if (id) await SES.api.put('/api/certification-learning-gap/masters/courses/' + id, payload); else await SES.api.post('/api/certification-learning-gap/masters/courses', payload); bootstrap.Modal.getInstance(document.getElementById('cert-gap-course-modal')).hide(); loadCatalogs(); } catch (e) { showCertificationError(e, '学習courseの保存に失敗しました'); }
    };
    window.deactivateTrainingCourse = async function (id) { const result = await Swal.fire({ title: 'courseを無効化', showCancelButton: true, confirmButtonText: '無効化', cancelButtonText: '戻る' }); if (!result.isConfirmed) return; try { await SES.api.delete('/api/certification-learning-gap/masters/courses/' + id); loadCatalogs(); } catch (e) { showCertificationError(e, '学習courseの無効化に失敗しました'); } };

    window.exportCertificationLearningGap = async function () {
        const query = params(); delete query.current; delete query.size;
        await SES.download('/api/certification-learning-gap/export?' + new URLSearchParams(query).toString(), '資格・学習・skill-gap.csv');
    };

    document.addEventListener('DOMContentLoaded', function () { loadCertificationLearningGap(1); loadCatalogs(); });
}());
