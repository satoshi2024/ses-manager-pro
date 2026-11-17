/* 要員本人の資格申請・証憑・学習計画。engineerIdはAPIへ送信しない。画面文言は SES.i18n.t（myCertGap.* / certGap.*）から取る。 */
(function () {
    'use strict';
    const base = '/api/my/certification-learning-gap';
    let current = null;
    let catalogs = { certifications: [], courses: [] };
    let uploadRecordId = null;
    const t = (key, ...args) => SES.i18n.t(key, ...args);
    const esc = value => SES.escapeHtml(value == null ? '' : String(value));
    const showError = error => { const el = document.getElementById('my-cert-error'); el.textContent = error?.message || t('myCertGap.msg.failed'); el.classList.remove('d-none'); };
    const hideError = () => document.getElementById('my-cert-error').classList.add('d-none');
    const today = () => { const date = new Date(); return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0'); };
    // 資格番号（CERTIFICATION_PII）の入力可否は、templateが入力欄を出したかどうかだけで判定する（nf03-certification-pii）。
    // OFFの間は申請・再申請とも資格番号を送らない（APIも資格番号付きの要求を拒否する）。
    const certificateNumberInput = () => document.getElementById('my-cert-number');
    const certificateNumberEntryEnabled = () => certificateNumberInput() !== null;
    const isActiveState = state => state === 'DRAFT' || state === 'SUBMITTED' || state === 'VERIFIED' || state === 'ACTIVE';

    // これはSES.apiではなくmultipart fetchのレスポンスラッパーを検証する。
    async function uploadCertificationEvidence(recordId, file) {
        const form = new FormData();
        form.append('file', file);
        const response = await fetch(base + '/certifications/' + encodeURIComponent(recordId) + '/evidence', { method: 'POST', body: form, headers: { 'X-XSRF-TOKEN': SES.csrf.token() }, credentials: 'same-origin', cache: 'no-store' });
        const result = await response.json();
        if (!response.ok || result.code !== 200) throw new Error(result.message || t('myCertGap.msg.uploadFailed'));
    }

    async function load() {
        hideError();
        try {
            const result = await Promise.all([SES.api.get(base), SES.api.get(base + '/catalog/certifications'), SES.api.get(base + '/catalog/courses')]);
            current = result[0]; catalogs = { certifications: result[1] || [], courses: result[2] || [] };
            renderCatalogs(); render(current);
        } catch (e) { showError(e); }
    }

    function renderCatalogs() {
        const select = document.getElementById('my-certification-id');
        select.innerHTML = '<option value="">' + esc(t('myCertGap.selectCertification')) + '</option>' + catalogs.certifications.map(item => '<option value="' + esc(item.id) + '">' + esc(item.displayName || '-') + (item.issuerDisplay ? ' (' + esc(item.issuerDisplay) + ')' : '') + '</option>').join('');
    }

    function render(data) {
        const certifications = data?.certifications || [];
        const rows = document.getElementById('my-cert-rows');
        document.getElementById('my-cert-empty').classList.toggle('d-none', certifications.length > 0);
        rows.innerHTML = certifications.map(item => {
            const record = item.record || {};
            const state = record.recordState || '';
            const actions = '<button class="btn btn-sm btn-outline-info me-1" onclick="showMyCertification(' + record.id + ')">' + esc(t('common.detail')) + '</button>'
                + ((state !== 'CANCELLED' && state !== 'REJECTED') ? '<button class="btn btn-sm btn-outline-primary me-1" onclick="openMyEvidenceUpload(' + record.id + ')">' + esc(t('myCertGap.evidenceButton')) + '</button>' : '')
                + (isActiveState(state) ? '<button class="btn btn-sm btn-outline-danger" onclick="withdrawMyCertification(' + record.id + ',' + (record.version ?? 0) + ')">' + esc(t('myCertGap.withdraw')) + '</button>' : '')
                + ((state === 'REJECTED' || state === 'CANCELLED') ? '<button class="btn btn-sm btn-outline-warning" onclick="resubmitMyCertification(' + record.id + ',' + (record.version ?? 0) + ')">' + esc(t('myCertGap.resubmit')) + '</button>' : '');
            return '<tr><td>' + esc(record.certificationDisplayName || '-') + '</td><td>' + esc(record.acquiredOn || '-') + '</td><td>' + esc(record.expiresOn || '-') + '</td><td>' + esc(state || '-') + '</td><td class="optional-column">' + esc(t('certGap.count', (item.evidences || []).length)) + '</td><td>' + actions + '</td></tr>';
        }).join('');
        const plans = data?.learningPlans || [];
        document.getElementById('my-plan-empty').classList.toggle('d-none', plans.length > 0);
        document.getElementById('my-plan-list').innerHTML = plans.map(renderPlan).join('');
    }

    function renderPlan(item) {
        const plan = item.plan || {};
        const status = plan.status || '';
        let actions = '';
        if (status === 'DRAFT') actions += '<button class="btn btn-sm btn-outline-primary me-1" onclick="submitMyPlanById(' + plan.id + ',' + (plan.version ?? 0) + ')">' + esc(t('myCertGap.applySubmit')) + '</button>';
        if (status === 'SUBMITTED') actions += '<button class="btn btn-sm btn-outline-danger me-1" onclick="withdrawMyPlan(' + plan.id + ',' + (plan.version ?? 0) + ')">' + esc(t('myCertGap.plan.withdrawSubmit')) + '</button>';
        if (status === 'REJECTED' || status === 'CANCELLED') actions += '<button class="btn btn-sm btn-outline-warning me-1" onclick="resubmitMyPlan(' + plan.id + ',' + (plan.version ?? 0) + ')">' + esc(t('myCertGap.resubmit')) + '</button>';
        const canEnroll = status === 'APPROVED' || status === 'IN_PROGRESS';
        const courseSelect = canEnroll ? '<select id="my-plan-course-' + plan.id + '" class="form-select form-select-sm d-inline-block me-1" style="max-width:220px">' + courseOptions() + '</select><button class="btn btn-sm btn-outline-success" onclick="enrollMyPlan(' + plan.id + ')">' + esc(t('myCertGap.enroll')) + '</button>' : '';
        const enrollments = (item.enrollments || []).map(renderEnrollment).join('');
        const cost = plan.plannedCostJpy == null ? '-' : t('certGap.course.yen', plan.plannedCostJpy);
        return '<div class="border-bottom border-secondary py-2"><div class="fw-semibold text-light">' + esc(plan.title || '-') + '</div><div class="small text-muted">' + esc(t('myCertGap.plan.summary', status || '-', cost)) + '</div><div class="my-plan-actions mt-2">' + actions + courseSelect + '</div><div class="small text-secondary mt-2">' + (enrollments || esc(t('myCertGap.noEnrollment'))) + '</div></div>';
    }

    function courseOptions() { return '<option value="">' + esc(t('myCertGap.selectCourse')) + '</option>' + catalogs.courses.map(course => '<option value="' + esc(course.id) + '">' + esc(course.name || '-') + ' / ' + esc(course.provider || '-') + '</option>').join(''); }
    function renderEnrollment(enrollment) {
        const status = enrollment.status || '';
        let action = '';
        if (status === 'PLANNED') action = '<button class="btn btn-sm btn-outline-primary ms-1" onclick="startMyEnrollment(' + enrollment.id + ',' + (enrollment.version ?? 0) + ')">' + esc(t('myCertGap.enrollment.start')) + '</button>';
        if (status === 'STARTED') action = '<button class="btn btn-sm btn-outline-success ms-1" onclick="completeMyEnrollment(' + enrollment.id + ',' + (enrollment.version ?? 0) + ')">' + esc(t('myCertGap.enrollment.complete')) + '</button>';
        if (status === 'PLANNED' || status === 'STARTED') action += '<button class="btn btn-sm btn-outline-danger ms-1" onclick="cancelMyEnrollment(' + enrollment.id + ',' + (enrollment.version ?? 0) + ')">' + esc(t('myCertGap.withdraw')) + '</button>';
        return '<span class="d-block">' + esc(t('myCertGap.enrollment.line', enrollment.courseName || '-', status || '-')) + action + '</span>';
    }

    window.openMyCertificationApply = () => bootstrap.Modal.getOrCreateInstance(document.getElementById('my-cert-apply-modal')).show();
    window.submitMyCertificationApply = async function () {
        try {
            const certificationId = Number(document.getElementById('my-certification-id').value);
            if (!certificationId) throw new Error(t('myCertGap.selectCertificationRequired'));
            await SES.api.post(base + '/certifications', { certificationId: certificationId, acquiredOn: document.getElementById('my-cert-acquired-on').value, expiresOn: document.getElementById('my-cert-expires-on').value || null, certificateNumber: certificateNumberEntryEnabled() ? certificateNumberInput().value : null });
            bootstrap.Modal.getInstance(document.getElementById('my-cert-apply-modal')).hide(); await load();
        } catch (e) { showError(e); }
    };
    window.openMyEvidenceUpload = id => { uploadRecordId = id; document.getElementById('my-cert-evidence-file').click(); };
    window.uploadMyCertificationEvidence = async function (input) {
        const file = input.files && input.files[0];
        if (!file || !uploadRecordId) return;
        try {
            await uploadCertificationEvidence(uploadRecordId, file);
            SES.toast.success(t('myCertGap.evidenceRegistered')); await load();
        } catch (e) { showError(e); } finally { input.value = ''; uploadRecordId = null; }
    };
    window.showMyCertification = async function (id) {
        try {
            const item = await SES.api.get(base + '/certifications/' + encodeURIComponent(id)); const record = item.record || {};
            const evidenceLabel = e => e.originalName || t('myCertGap.col.evidence');
            const evidenceHtml = (record.recordState === 'ACTIVE'
                ? (item.evidences || []).map(e => '<a href="' + base + '/certifications/' + encodeURIComponent(id) + '/evidence/' + encodeURIComponent(e.documentId) + '/versions/' + encodeURIComponent(e.versionNo) + '/download">' + esc(evidenceLabel(e)) + '</a>').join('<br>')
                : (item.evidences || []).map(e => '<span class="text-muted">' + esc(t('certGap.evidence.pending', evidenceLabel(e))) + '</span>').join('<br>')) || esc(t('myCertGap.none'));
            const controls = '<button class="btn btn-sm btn-outline-primary me-1" onclick="openMyEvidenceUpload(' + id + '); Swal.close();">' + esc(t('myCertGap.evidenceUpload')) + '</button>' + (isActiveState(record.recordState) ? '<button class="btn btn-sm btn-outline-warning" onclick="correctMyCertification(' + id + ',' + (record.version ?? 0) + ',\'' + esc(record.expiresOn || '') + '\')">' + esc(t('myCertGap.correct')) + '</button>' : '');
            Swal.fire({ titleText: String(record.certificationDisplayName || t('myCertGap.detailTitle')), html: '<p>' + esc(t('myCertGap.detail.state', record.recordState || '-', record.version ?? '-')) + '</p><p>' + esc(t('myCertGap.detail.acquiredOn', record.acquiredOn || '-')) + '</p><p>' + esc(t('myCertGap.detail.expiresOn', record.expiresOn || '-')) + '</p><p>' + esc(t('myCertGap.detail.evidence')) + ' ' + evidenceHtml + '</p><div>' + controls + '</div>', confirmButtonText: t('common.close') });
        } catch (e) { showError(e); }
    };
    window.withdrawMyCertification = async function (id, version) { const result = await Swal.fire({ title: t('myCertGap.withdrawTitle'), input: 'text', inputLabel: t('certGap.reason'), showCancelButton: true, confirmButtonText: t('myCertGap.withdraw'), cancelButtonText: t('common.back') }); if (!result.isConfirmed) return; try { await SES.api.post(base + '/certifications/' + id + '/withdraw', { expectedVersion: version, reason: result.value }); await load(); } catch (e) { showError(e); } };
    window.correctMyCertification = async function (id, version, expiresOn) { const date = await Swal.fire({ title: t('myCertGap.correctDateTitle'), input: 'date', inputValue: today(), showCancelButton: true, confirmButtonText: t('myCertGap.next'), cancelButtonText: t('common.back') }); if (!date.isConfirmed) return; const reason = await Swal.fire({ title: t('myCertGap.correctReasonTitle'), input: 'text', showCancelButton: true, confirmButtonText: t('myCertGap.correct'), cancelButtonText: t('common.back') }); if (!reason.isConfirmed) return; try { await SES.api.post(base + '/certifications/' + id + '/correct', { expectedVersion: version, acquiredOn: date.value, expiresOn: expiresOn || null, reason: reason.value }); await load(); } catch (e) { showError(e); } };
    window.resubmitMyCertification = async function (id, version) { const withNumber = certificateNumberEntryEnabled(); const result = await Swal.fire(withNumber ? { title: t('myCertGap.resubmitTitle'), input: 'text', inputLabel: t('myCertGap.numberPlaceholder'), showCancelButton: true, confirmButtonText: t('myCertGap.resubmit'), cancelButtonText: t('common.back') } : { title: t('myCertGap.resubmitTitle'), text: t('myCertGap.resubmitConfirm'), showCancelButton: true, confirmButtonText: t('myCertGap.resubmit'), cancelButtonText: t('common.back') }); if (!result.isConfirmed) return; try { await SES.api.post(base + '/certifications/' + id + '/resubmit', { expectedVersion: version, certificateNumber: withNumber ? result.value : null }); await load(); } catch (e) { showError(e); } };
    window.openMyPlanForm = () => bootstrap.Modal.getOrCreateInstance(document.getElementById('my-plan-modal')).show();
    window.submitMyPlan = async function () { try { await SES.api.post(base + '/learning-plans', { title: document.getElementById('my-plan-title').value, goalDescription: document.getElementById('my-plan-goal').value, attainmentCriteria: document.getElementById('my-plan-criteria').value, plannedStartOn: document.getElementById('my-plan-start').value || null, plannedEndOn: document.getElementById('my-plan-end').value || null, plannedCostJpy: Number(document.getElementById('my-plan-cost').value || 0) }); bootstrap.Modal.getInstance(document.getElementById('my-plan-modal')).hide(); await load(); } catch (e) { showError(e); } };
    window.submitMyPlanById = async function (id, version) { const reason = await Swal.fire({ title: t('myCertGap.plan.zeroCostReason'), input: 'text', showCancelButton: true, confirmButtonText: t('myCertGap.applySubmit'), cancelButtonText: t('common.back') }); if (!reason.isConfirmed) return; try { await SES.api.post(base + '/learning-plans/' + id + '/submit', { expectedVersion: version, zeroCostReason: reason.value }); await load(); } catch (e) { showError(e); } };
    window.withdrawMyPlan = async function (id, version) { const reason = await Swal.fire({ title: t('myCertGap.plan.withdrawTitle'), input: 'text', showCancelButton: true, confirmButtonText: t('myCertGap.withdraw'), cancelButtonText: t('common.back') }); if (!reason.isConfirmed) return; try { await SES.api.post(base + '/learning-plans/' + id + '/withdraw', { expectedVersion: version, reason: reason.value }); await load(); } catch (e) { showError(e); } };
    window.resubmitMyPlan = async function (id, version) { try { await SES.api.post(base + '/learning-plans/' + id + '/resubmit', { expectedVersion: version }); await load(); } catch (e) { showError(e); } };
    // 計画のversionは表示中の一覧（load()の応答）から取る。未定義のstateを参照してReferenceErrorになっていた（RB-I18N作業中に発見）。
    window.enrollMyPlan = async function (id) { const courseId = Number(document.getElementById('my-plan-course-' + id).value); if (!courseId) return; const plan = ((current?.learningPlans) || []).map(item => item.plan || {}).find(p => p.id === id); try { await SES.api.post(base + '/learning-plans/' + id + '/enrollments', { expectedVersion: plan?.version, courseId: courseId }); await load(); } catch (e) { showError(e); } };
    window.startMyEnrollment = async function (id, version) { try { await SES.api.post(base + '/enrollments/' + id + '/start', { expectedVersion: version }); await load(); } catch (e) { showError(e); } };
    window.completeMyEnrollment = async function (id, version) { const result = await Swal.fire({ title: t('myCertGap.enrollment.completedOnTitle'), input: 'date', inputValue: today(), showCancelButton: true, confirmButtonText: t('myCertGap.enrollment.complete'), cancelButtonText: t('common.back') }); if (!result.isConfirmed) return; try { await SES.api.post(base + '/enrollments/' + id + '/complete', { expectedVersion: version, completedOn: result.value }); await load(); } catch (e) { showError(e); } };
    window.cancelMyEnrollment = async function (id, version) { const reason = await Swal.fire({ title: t('myCertGap.enrollment.cancelTitle'), input: 'text', showCancelButton: true, confirmButtonText: t('myCertGap.withdraw'), cancelButtonText: t('common.back') }); if (!reason.isConfirmed) return; try { await SES.api.post(base + '/enrollments/' + id + '/cancel', { expectedVersion: version, reason: reason.value }); await load(); } catch (e) { showError(e); } };
    window.exportMyCertificationLearningGap = () => SES.download(base + '/export', 'my-certification-learning-gap.csv');
    document.addEventListener('DOMContentLoaded', load);
}());
