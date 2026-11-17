/* 資格・学習・スキルギャップ管理。検索条件はlist/detail/count/exportで共有する。画面文言は SES.i18n.t（certGap.*）から取る。 */
(function () {
    'use strict';

    const pageSize = 10;
    let pageData = null;
    let certificationMasters = [];
    let trainingCourses = [];
    let skillTags = [];
    let editingCourseVersion = null;
    let editingCertificationVersion = null;

    function t(key, ...args) { return SES.i18n.t(key, ...args); }

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

    /** 要員状態（DB値）の表示名。未知の値はそのまま出す。 */
    function engineerStatusLabel(status) {
        return status ? SES.i18n.e('engineerStatus', status) : '-';
    }

    window.loadCertificationLearningGap = async function (page) {
        const query = params(); query.current = page || 1;
        try {
            const data = await SES.api.get('/api/certification-learning-gap', query);
            pageData = data;
            render(data);
        } catch (e) {
            showCertificationError(e, t('certGap.loadFailed'));
            document.getElementById('cert-gap-table-body').innerHTML = '<tr><td colspan="7" class="text-center text-danger py-4">' + esc(t('certGap.tableLoadFailed')) + '</td></tr>';
        }
    };

    function render(data) {
        const body = document.getElementById('cert-gap-table-body');
        const records = (data && data.records) || [];
        if (!records.length) {
            body.innerHTML = '<tr><td colspan="7" class="text-center text-muted py-4">' + esc(t('certGap.empty')) + '</td></tr>';
        } else {
            body.innerHTML = records.map(function (row) {
                const gap = row.gapStatus ? (row.gapStatus === 'OK' ? esc(t('certGap.gap.confirmed')) : esc(row.gapStatus)) : '-';
                const gapCount = (row.skillGaps || []).filter(function (item) { return item.gap; }).length;
                return '<tr><td class="ps-4"><a href="#" class="text-info" onclick="showCertificationLearningGapDetail(' + row.engineerId + '); return false;">' + esc(row.engineerName) + '</a><div class="small text-muted">ID: ' + row.engineerId + '</div></td>'
                    + '<td>' + esc(engineerStatusLabel(row.engineerStatus)) + '</td><td>' + lifecycleLabel(row.lifecycleState) + '</td>'
                    + '<td>' + esc(t('certGap.count', (row.certifications || []).length)) + '</td><td>' + esc(t('certGap.count', (row.trainings || []).length)) + '</td><td class="cert-gap-summary">' + gap + ' / ' + esc(t('certGap.count', gapCount)) + '</td>'
                    + '<td class="text-end pe-4"><div class="d-flex flex-wrap justify-content-end align-items-center gap-1"><button class="btn btn-sm btn-outline-info" title="' + esc(t('common.detail')) + '" aria-label="' + esc(t('common.detail')) + '" onclick="showCertificationLearningGapDetail(' + row.engineerId + ')"><i class="bi bi-eye" aria-hidden="true"></i><span class="visually-hidden">' + esc(t('common.detail')) + '</span></button></div></td></tr>';
            }).join('');
        }
        renderPagination(data);
    }

    function lifecycleLabel(value) {
        const keys = { ACTIVE: 'certGap.lifecycle.active', ON_LEAVE: 'certGap.lifecycle.onLeave', RESIGNED: 'certGap.lifecycle.resigned', PENDING: 'certGap.lifecycle.pending' };
        return keys[value] ? esc(t(keys[value])) : esc(value || '-');
    }

    function renderPagination(data) {
        const total = data ? data.total : 0;
        const current = data ? data.current : 1;
        const size = data ? data.size : pageSize;
        const pages = data ? data.pages : 0;
        document.getElementById('cert-gap-page-info').textContent = total
            ? t('certGap.pageInfo', total, (current - 1) * size + 1, Math.min(current * size, total)) : t('certGap.pageInfoZero');
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
            document.getElementById('cert-gap-detail-title').textContent = t('certGap.detail.title', String(row.engineerName || '-'));
            document.getElementById('cert-gap-detail-body').innerHTML = detailHtml(row);
            bootstrap.Modal.getOrCreateInstance(document.getElementById('cert-gap-detail-modal')).show();
        } catch (e) { showCertificationError(e, t('certGap.detail.loadFailed')); }
    };

    function detailHtml(row) {
        const certs = (row.certifications || []).map(function (item) {
            const number = item.certificateNumber ? esc(item.certificateNumber) : esc(item.certificateNumberMasked || t('certGap.number.notRegistered'));
            const downloadable = item.recordState === 'ACTIVE';
            const evidences = (item.evidences || []).map(function (evidence) {
                const label = evidence.originalName || t('certGap.col.evidence');
                if (!downloadable) return '<span class="d-block text-muted">' + esc(t('certGap.evidence.pending', label)) + '</span>';
                return '<a class="d-block" href="/api/certification-learning-gap/' + encodeURIComponent(row.engineerId)
                    + '/certifications/' + encodeURIComponent(item.id) + '/evidence/' + encodeURIComponent(evidence.documentId)
                    + '/versions/' + encodeURIComponent(evidence.versionNo) + '/download">' + esc(label) + '</a>';
            }).join('');
            const state = item.recordState || '';
            let actions = '';
            if (document.getElementById('cert-gap-cert-write') && ['DRAFT', 'SUBMITTED', 'VERIFIED'].indexOf(state) >= 0) {
                if ((item.evidences || []).length) {
                    const evidence = item.evidences[0];
                    actions += '<button type="button" class="btn btn-sm btn-outline-success" onclick="verifyCertificationRecord(' + item.id + ',' + (item.version ?? 0) + ',' + evidence.documentId + ',' + evidence.documentVersionId + ',decodeURIComponent(\'' + encodeURIComponent(evidence.sha256 || '') + '\'))">' + esc(t('certGap.evidence.verify')) + '</button>';
                }
                actions += '<button type="button" class="btn btn-sm btn-outline-danger" onclick="rejectCertificationRecord(' + item.id + ',' + (item.version ?? 0) + ')">' + esc(t('certGap.reject')) + '</button>';
            }
            return '<tr><td>' + esc(item.certificationDisplayName || '-') + '</td><td>' + esc(item.effectiveState || state || '-') + '</td><td>' + esc(item.expiresOn || '-') + '</td><td>' + number + '</td><td>' + (evidences || '-') + '</td><td><div class="d-flex flex-wrap justify-content-end align-items-center gap-1">' + actions + '</div></td></tr>';
        }).join('');
        const plans = (row.trainings || []).map(function (item) {
            let actions = '';
            if (item.status === 'SUBMITTED' && document.getElementById('cert-gap-training-write')) {
                actions = '<div class="d-flex flex-wrap justify-content-end align-items-center gap-1"><button type="button" class="btn btn-sm btn-outline-success" onclick="approveTrainingPlan(' + item.planId + ')">' + esc(t('certGap.approve')) + '</button><button type="button" class="btn btn-sm btn-outline-danger" onclick="rejectTrainingPlan(' + item.planId + ')">' + esc(t('certGap.reject')) + '</button></div>';
            }
            return '<tr><td>' + esc(item.title || '-') + '</td><td>' + esc(item.status || '-') + '</td><td>' + esc(item.courseName || '-') + '</td><td>' + actions + '</td></tr>';
        }).join('');
        const gaps = (row.skillGaps || []).map(function (item) {
            return '<li>' + esc(item.canonicalName || item.requestedName || item.key) + ': ' + esc(t(item.gap ? 'certGap.gap.short' : 'certGap.gap.sufficient'))
                + (item.unknown ? esc(t('certGap.gap.unknownSkill')) : '') + '</li>';
        }).join('');
        const aiButton = value('cert-gap-project') ? '<button type="button" class="btn btn-sm btn-outline-warning mb-3" onclick="showCertificationLearningGapAiCandidate(' + row.engineerId + ')"><i class="bi bi-stars me-1"></i>' + esc(t('certGap.ai.show')) + '</button>' : '';
        const head = function (keys) { return '<thead><tr>' + keys.map(function (key) { return '<th>' + esc(t(key)) + '</th>'; }).join('') + '</tr></thead>'; };
        return '<p class="text-muted">' + esc(t('certGap.detail.stateLine', engineerStatusLabel(row.engineerStatus), lifecycleText(row.lifecycleState))) + '</p>' + aiButton
            + '<h6>' + esc(t('certGap.detail.certHistory')) + '</h6><div class="table-responsive"><table class="table table-dark table-sm">'
            + head(['certGap.col.certifications', 'certGap.col.status', 'certGap.master.expiry', 'certGap.col.number', 'certGap.col.evidence', 'common.actions'])
            + '<tbody>' + (certs || '<tr><td colspan="6">' + esc(t('certGap.detail.noCert')) + '</td></tr>') + '</tbody></table></div>'
            + '<h6 class="mt-4">' + esc(t('certGap.detail.plans')) + '</h6><div class="table-responsive"><table class="table table-dark table-sm">'
            + head(['certGap.col.plan', 'certGap.col.status', 'certGap.col.course', 'common.actions'])
            + '<tbody>' + (plans || '<tr><td colspan="4">' + esc(t('certGap.detail.noPlan')) + '</td></tr>') + '</tbody></table></div>'
            + '<h6 class="mt-4">' + esc(t('certGap.detail.skillGap')) + '</h6><ul>' + (gaps || '<li>' + esc(t('certGap.detail.noGap')) + '</li>') + '</ul>';
    }

    /** ライフサイクルの表示名（HTML化前の文字列）。 */
    function lifecycleText(value) {
        const keys = { ACTIVE: 'certGap.lifecycle.active', ON_LEAVE: 'certGap.lifecycle.onLeave', RESIGNED: 'certGap.lifecycle.resigned', PENDING: 'certGap.lifecycle.pending' };
        return keys[value] ? t(keys[value]) : (value || '-');
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
            const candidateId = candidate && candidate.aiRunId;
            const decisionButtons = candidateId && candidate.humanDecisionRequired && candidate.status === 'AI_CANDIDATE'
                ? '<div class="d-flex justify-content-center gap-2 mt-3"><button id="cert-gap-ai-accept" type="button" class="btn btn-sm btn-outline-success">' + esc(t('certGap.ai.accept')) + '</button><button id="cert-gap-ai-reject" type="button" class="btn btn-sm btn-outline-danger">' + esc(t('certGap.ai.rejectCandidate')) + '</button></div>' : '';
            const candidates = candidate ? (candidate.aiSuggestedCourseIds || []).join(', ') : t('certGap.ai.unavailable');
            Swal.fire({ title: t('certGap.ai.title'), html: '<p>' + esc(t('certGap.ai.ruleGap', rule.status || '-')) + '</p><p>' + esc(t('certGap.ai.asOf', rule.asOf || '-')) + '</p><p>' + esc(t('certGap.ai.candidates', candidates)) + '</p><p class="text-muted small">' + esc(t('certGap.ai.notice')) + '</p>' + decisionButtons, showConfirmButton: !decisionButtons, confirmButtonText: t('common.close'), didOpen: function () {
                const accept = document.getElementById('cert-gap-ai-accept');
                const reject = document.getElementById('cert-gap-ai-reject');
                if (accept) accept.addEventListener('click', function () { decideCertificationLearningGapCandidate(engineerId, candidateId, 'accept'); });
                if (reject) reject.addEventListener('click', function () { decideCertificationLearningGapCandidate(engineerId, candidateId, 'reject'); });
            } });
        } catch (e) { showCertificationError(e, t('certGap.ai.loadFailed')); }
    };

    async function decideCertificationLearningGapCandidate(engineerId, candidateId, decision) {
        const accept = decision === 'accept';
        const result = await Swal.fire({ title: t(accept ? 'certGap.ai.acceptTitle' : 'certGap.ai.rejectTitle'), input: 'text', inputLabel: t('certGap.reason'), inputValidator: function (value) { return value && value.trim() ? undefined : t('certGap.reasonRequired'); }, showCancelButton: true, confirmButtonText: t(accept ? 'certGap.ai.acceptButton' : 'certGap.reject'), cancelButtonText: t('common.back') });
        if (!result.isConfirmed) return;
        try {
            await SES.api.post('/api/certification-learning-gap/' + encodeURIComponent(engineerId) + '/ai-candidates/' + encodeURIComponent(candidateId) + '/' + decision, { reason: result.value });
            Toast.success(t('certGap.ai.decided'));
        } catch (e) { showCertificationError(e, t('certGap.ai.decideFailed')); }
    }

    window.verifyCertificationRecord = async function (recordId, version, documentId, documentVersionId, evidenceHash) {
        const result = await Swal.fire({ title: t('certGap.verify.title'), text: t('certGap.verify.text'), showCancelButton: true, confirmButtonText: t('common.confirm'), cancelButtonText: t('common.back') });
        if (!result.isConfirmed) return;
        try {
            await SES.api.post('/api/certification-learning-gap/certifications/' + recordId + '/verify', { expectedVersion: version, evidenceDocumentId: documentId, evidenceDocumentVersionId: documentVersionId, evidenceHash: evidenceHash });
            bootstrap.Modal.getInstance(document.getElementById('cert-gap-detail-modal')).hide(); await loadCertificationLearningGap(pageData ? pageData.current : 1);
        } catch (e) { showCertificationError(e, t('certGap.evidence.failed')); }
    };

    window.rejectCertificationRecord = async function (recordId, version) {
        const result = await Swal.fire({ title: t('certGap.rejectCert.title'), input: 'text', inputLabel: t('certGap.reason'), showCancelButton: true, confirmButtonText: t('certGap.reject'), cancelButtonText: t('common.back') });
        if (!result.isConfirmed) return;
        try {
            await SES.api.post('/api/certification-learning-gap/certifications/' + recordId + '/reject', { expectedVersion: version, reason: result.value });
            bootstrap.Modal.getInstance(document.getElementById('cert-gap-detail-modal')).hide(); await loadCertificationLearningGap(pageData ? pageData.current : 1);
        } catch (e) { showCertificationError(e, t('certGap.rejectCert.failed')); }
    };

    window.approveTrainingPlan = async function (planId) {
        try { await SES.api.post('/api/certification-learning-gap/training-plans/' + planId + '/approve', {}); await loadCertificationLearningGap(pageData ? pageData.current : 1); } catch (e) { showCertificationError(e, t('certGap.plan.approveFailed')); }
    };
    window.rejectTrainingPlan = async function (planId) {
        const result = await Swal.fire({ title: t('certGap.plan.rejectTitle'), input: 'text', inputLabel: t('certGap.reason'), showCancelButton: true, confirmButtonText: t('certGap.reject'), cancelButtonText: t('common.back') });
        if (!result.isConfirmed) return;
        try { await SES.api.post('/api/certification-learning-gap/training-plans/' + planId + '/reject', { comment: result.value }); await loadCertificationLearningGap(pageData ? pageData.current : 1); } catch (e) { showCertificationError(e, t('certGap.plan.rejectFailed')); }
    };

    function loadCatalogs() {
        const panel = document.getElementById('cert-gap-catalog-panel');
        if (!panel) return;
        Promise.all([SES.api.get('/api/certification-learning-gap/masters/certifications', { includeInactive: true }), SES.api.get('/api/certification-learning-gap/masters/courses', { includeInactive: true }), SES.api.get('/api/skill-tags')]).then(function (data) {
            certificationMasters = data[0] || []; trainingCourses = data[1] || []; skillTags = data[2] || [];
            renderMasters(); renderCourses();
        }).catch(function (error) { showCertificationError(error, t('certGap.catalog.loadFailed')); document.getElementById('cert-gap-master-body').innerHTML = '<tr><td colspan="5" class="text-danger">' + esc(t('certGap.catalog.unavailable')) + '</td></tr>'; });
    }

    function activeLabel(flag) { return esc(t(flag === 1 ? 'certGap.active' : 'certGap.inactive')); }

    function renderMasters() {
        document.getElementById('cert-gap-master-body').innerHTML = certificationMasters.map(function (item) {
            return '<tr><td>' + esc(item.displayName || '-') + '</td><td>' + esc(item.issuerDisplay || '-') + '</td><td>' + esc(item.expiryType || 'NONE') + (item.expiryMonths ? ' / ' + esc(t('certGap.master.months', item.expiryMonths)) : '') + '</td><td>' + activeLabel(item.activeFlag) + '</td><td><div class="d-flex flex-wrap justify-content-end align-items-center gap-1"><button class="btn btn-sm btn-outline-info" onclick="openCertificationMasterForm(' + item.id + ')">' + esc(t('common.edit')) + '</button>' + (item.activeFlag === 1 ? '<button class="btn btn-sm btn-outline-danger" onclick="deactivateCertificationMaster(' + item.id + ',' + item.version + ')">' + esc(t('certGap.deactivate')) + '</button>' : '') + '</div></td></tr>';
        }).join('') || '<tr><td colspan="5" class="text-muted">' + esc(t('certGap.master.empty')) + '</td></tr>';
    }

    function renderCourses() {
        document.getElementById('cert-gap-course-body').innerHTML = trainingCourses.map(function (item) {
            return '<tr><td>' + esc(item.name || '-') + '</td><td>' + esc(item.provider || '-') + '</td><td>' + esc(item.costJpy == null ? '-' : t('certGap.course.yen', item.costJpy)) + '</td><td>' + (item.skills || []).map(function (skill) { return esc(skill.skillName || ('ID:' + skill.skillId)); }).join(', ') + '</td><td><div class="d-flex flex-wrap justify-content-end align-items-center gap-1"><button class="btn btn-sm btn-outline-info" onclick="openTrainingCourseForm(' + item.id + ')">' + esc(t('common.edit')) + '</button>' + (item.activeFlag === 1 ? '<button class="btn btn-sm btn-outline-danger" onclick="deactivateTrainingCourse(' + item.id + ',' + item.version + ')">' + esc(t('certGap.deactivate')) + '</button>' : '') + '</div></td></tr>';
        }).join('') || '<tr><td colspan="5" class="text-muted">' + esc(t('certGap.course.empty')) + '</td></tr>';
    }

    window.openCertificationMasterForm = async function (id) {
        try {
            editingCertificationVersion = null;
            const item = id ? await SES.api.get('/api/certification-learning-gap/masters/certifications/' + id) : {};
            if (id) editingCertificationVersion = item.version;
            document.getElementById('cert-gap-master-id').value = item.id || '';
            document.getElementById('cert-gap-master-name').value = item.displayName || '';
            document.getElementById('cert-gap-master-issuer').value = item.issuerDisplay || '';
            document.getElementById('cert-gap-master-code').value = item.externalCode || '';
            document.getElementById('cert-gap-master-expiry-type').value = item.expiryType || 'NONE';
            document.getElementById('cert-gap-master-expiry-months').value = item.expiryMonths || '';
            document.getElementById('cert-gap-master-rule-version').value = item.ruleVersion || 1;
            document.getElementById('cert-gap-master-active').value = item.activeFlag == null ? 1 : item.activeFlag;
            bootstrap.Modal.getOrCreateInstance(document.getElementById('cert-gap-master-modal')).show();
        } catch (e) { showCertificationError(e, t('certGap.master.loadFailed')); }
    };

    window.saveCertificationMaster = async function () {
        const id = document.getElementById('cert-gap-master-id').value;
        const expiryType = document.getElementById('cert-gap-master-expiry-type').value;
        const payload = { displayName: document.getElementById('cert-gap-master-name').value, issuerDisplay: document.getElementById('cert-gap-master-issuer').value, externalCode: document.getElementById('cert-gap-master-code').value, expiryType: expiryType, expiryMonths: expiryType === 'FIXED_MONTHS' ? Number(document.getElementById('cert-gap-master-expiry-months').value) : null, ruleVersion: Number(document.getElementById('cert-gap-master-rule-version').value || 1), activeFlag: Number(document.getElementById('cert-gap-master-active').value), expectedVersion: editingCertificationVersion };
        try { if (id) await SES.api.put('/api/certification-learning-gap/masters/certifications/' + id, payload); else await SES.api.post('/api/certification-learning-gap/masters/certifications', payload); bootstrap.Modal.getInstance(document.getElementById('cert-gap-master-modal')).hide(); loadCatalogs(); } catch (e) { showCertificationError(e, t('certGap.master.saveFailed')); }
    };
    window.deactivateCertificationMaster = async function (id, version) { const result = await Swal.fire({ title: t('certGap.master.deactivateTitle'), showCancelButton: true, confirmButtonText: t('certGap.deactivate'), cancelButtonText: t('common.back') }); if (!result.isConfirmed) return; try { await SES.api.delete('/api/certification-learning-gap/masters/certifications/' + id + '?expectedVersion=' + encodeURIComponent(version)); loadCatalogs(); } catch (e) { showCertificationError(e, t('certGap.master.deactivateFailed')); } };

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
        } catch (e) { showCertificationError(e, t('certGap.course.loadFailed')); }
    };
    window.saveTrainingCourse = async function () {
        const id = document.getElementById('cert-gap-course-id').value;
        const payload = { provider: document.getElementById('cert-gap-course-provider').value, name: document.getElementById('cert-gap-course-name').value, description: document.getElementById('cert-gap-course-description').value, costJpy: Number(document.getElementById('cert-gap-course-cost').value), periodDays: document.getElementById('cert-gap-course-period').value ? Number(document.getElementById('cert-gap-course-period').value) : null, capacity: document.getElementById('cert-gap-course-capacity').value ? Number(document.getElementById('cert-gap-course-capacity').value) : null, expectedVersion: editingCourseVersion, requiredSkillIds: Array.from(document.getElementById('cert-gap-course-skills').selectedOptions).map(function (option) { return Number(option.value); }) };
        try { if (id) await SES.api.put('/api/certification-learning-gap/masters/courses/' + id, payload); else await SES.api.post('/api/certification-learning-gap/masters/courses', payload); bootstrap.Modal.getInstance(document.getElementById('cert-gap-course-modal')).hide(); loadCatalogs(); } catch (e) { showCertificationError(e, t('certGap.course.saveFailed')); }
    };
    window.deactivateTrainingCourse = async function (id, version) { const result = await Swal.fire({ title: t('certGap.course.deactivateTitle'), showCancelButton: true, confirmButtonText: t('certGap.deactivate'), cancelButtonText: t('common.back') }); if (!result.isConfirmed) return; try { await SES.api.delete('/api/certification-learning-gap/masters/courses/' + id + '?expectedVersion=' + encodeURIComponent(version)); loadCatalogs(); } catch (e) { showCertificationError(e, t('certGap.course.deactivateFailed')); } };

    window.exportCertificationLearningGap = async function () {
        const query = params(); delete query.current; delete query.size;
        await SES.download('/api/certification-learning-gap/export?' + new URLSearchParams(query).toString(), t('certGap.exportFileName'));
    };

    document.addEventListener('DOMContentLoaded', function () { loadCertificationLearningGap(1); loadCatalogs(); });
}());
