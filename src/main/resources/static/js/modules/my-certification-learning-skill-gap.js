/* 要員本人の資格申請・証憑・学習計画。engineerIdはAPIへ送信しない。 */
(function () {
    'use strict';
    const base = '/api/my/certification-learning-gap';
    let current = null;
    let catalogs = { certifications: [], courses: [] };
    let uploadRecordId = null;
    const esc = value => SES.escapeHtml(value == null ? '' : String(value));
    const showError = error => { const el = document.getElementById('my-cert-error'); el.textContent = error?.message || '処理に失敗しました'; el.classList.remove('d-none'); };
    const hideError = () => document.getElementById('my-cert-error').classList.add('d-none');
    const today = () => { const date = new Date(); return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0'); };

    // これはSES.apiではなくmultipart fetchのレスポンスラッパーを検証する。
    async function uploadCertificationEvidence(recordId, file) {
        const form = new FormData();
        form.append('file', file);
        const response = await fetch(base + '/certifications/' + encodeURIComponent(recordId) + '/evidence', { method: 'POST', body: form, headers: { 'X-XSRF-TOKEN': SES.csrf.token() }, credentials: 'same-origin', cache: 'no-store' });
        const result = await response.json();
        if (!response.ok || result.code !== 200) throw new Error(result.message || '証憑アップロードに失敗しました');
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
        select.innerHTML = '<option value="">資格を選択</option>' + catalogs.certifications.map(item => '<option value="' + esc(item.id) + '">' + esc(item.displayName || '-') + (item.issuerDisplay ? '（' + esc(item.issuerDisplay) + '）' : '') + '</option>').join('');
    }

    function render(data) {
        const certifications = data?.certifications || [];
        const rows = document.getElementById('my-cert-rows');
        document.getElementById('my-cert-empty').classList.toggle('d-none', certifications.length > 0);
        rows.innerHTML = certifications.map(item => {
            const record = item.record || {};
            const state = record.recordState || '';
            const actions = '<button class="btn btn-sm btn-outline-info me-1" onclick="showMyCertification(' + record.id + ')">詳細</button>'
                + ((state !== 'CANCELLED' && state !== 'REJECTED') ? '<button class="btn btn-sm btn-outline-primary me-1" onclick="openMyEvidenceUpload(' + record.id + ')">証憑</button>' : '')
                + ((state === 'DRAFT' || state === 'SUBMITTED' || state === 'VERIFIED' || state === 'ACTIVE') ? '<button class="btn btn-sm btn-outline-danger" onclick="withdrawMyCertification(' + record.id + ',' + (record.version ?? 0) + ')">取消</button>' : '')
                + ((state === 'REJECTED' || state === 'CANCELLED') ? '<button class="btn btn-sm btn-outline-warning" onclick="resubmitMyCertification(' + record.id + ')">再申請</button>' : '');
            return '<tr><td>' + esc(record.certificationDisplayName || '-') + '</td><td>' + esc(record.acquiredOn || '-') + '</td><td>' + esc(record.expiresOn || '-') + '</td><td>' + esc(state || '-') + '</td><td class="optional-column">' + ((item.evidences || []).length) + '件</td><td>' + actions + '</td></tr>';
        }).join('');
        const plans = data?.learningPlans || [];
        document.getElementById('my-plan-empty').classList.toggle('d-none', plans.length > 0);
        document.getElementById('my-plan-list').innerHTML = plans.map(renderPlan).join('');
    }

    function renderPlan(item) {
        const plan = item.plan || {};
        const status = plan.status || '';
        let actions = '';
        if (status === 'DRAFT') actions += '<button class="btn btn-sm btn-outline-primary me-1" onclick="submitMyPlanById(' + plan.id + ',' + (plan.version ?? 0) + ')">申請</button>';
        if (status === 'SUBMITTED') actions += '<button class="btn btn-sm btn-outline-danger me-1" onclick="withdrawMyPlan(' + plan.id + ',' + (plan.version ?? 0) + ')">申請取消</button>';
        if (status === 'REJECTED' || status === 'CANCELLED') actions += '<button class="btn btn-sm btn-outline-warning me-1" onclick="resubmitMyPlan(' + plan.id + ')">再申請</button>';
        const canEnroll = status === 'APPROVED' || status === 'IN_PROGRESS';
        const courseSelect = canEnroll ? '<select id="my-plan-course-' + plan.id + '" class="form-select form-select-sm d-inline-block me-1" style="max-width:220px">' + courseOptions() + '</select><button class="btn btn-sm btn-outline-success" onclick="enrollMyPlan(' + plan.id + ')">受講登録</button>' : '';
        const enrollments = (item.enrollments || []).map(renderEnrollment).join('');
        return '<div class="border-bottom border-secondary py-2"><div class="fw-semibold text-light">' + esc(plan.title || '-') + '</div><div class="small text-muted">状態: ' + esc(status || '-') + ' / 予定費用: ' + esc(plan.plannedCostJpy == null ? '-' : plan.plannedCostJpy + '円') + '</div><div class="my-plan-actions mt-2">' + actions + courseSelect + '</div><div class="small text-secondary mt-2">' + (enrollments || '受講登録なし') + '</div></div>';
    }

    function courseOptions() { return '<option value="">courseを選択</option>' + catalogs.courses.map(course => '<option value="' + esc(course.id) + '">' + esc(course.name || '-') + ' / ' + esc(course.provider || '-') + '</option>').join(''); }
    function renderEnrollment(enrollment) {
        const status = enrollment.status || '';
        let action = '';
        if (status === 'PLANNED') action = '<button class="btn btn-sm btn-outline-primary ms-1" onclick="startMyEnrollment(' + enrollment.id + ',' + (enrollment.version ?? 0) + ')">開始</button>';
        if (status === 'STARTED') action = '<button class="btn btn-sm btn-outline-success ms-1" onclick="completeMyEnrollment(' + enrollment.id + ',' + (enrollment.version ?? 0) + ')">完了</button>';
        if (status === 'PLANNED' || status === 'STARTED') action += '<button class="btn btn-sm btn-outline-danger ms-1" onclick="cancelMyEnrollment(' + enrollment.id + ',' + (enrollment.version ?? 0) + ')">取消</button>';
        return '<span class="d-block">受講: ' + esc(enrollment.courseName || '-') + ' / ' + esc(status || '-') + action + '</span>';
    }

    window.openMyCertificationApply = () => bootstrap.Modal.getOrCreateInstance(document.getElementById('my-cert-apply-modal')).show();
    window.submitMyCertificationApply = async function () {
        try {
            const certificationId = Number(document.getElementById('my-certification-id').value);
            if (!certificationId) throw new Error('資格を選択してください');
            await SES.api.post(base + '/certifications', { certificationId: certificationId, acquiredOn: document.getElementById('my-cert-acquired-on').value, expiresOn: document.getElementById('my-cert-expires-on').value || null, certificateNumber: document.getElementById('my-cert-number').value });
            bootstrap.Modal.getInstance(document.getElementById('my-cert-apply-modal')).hide(); await load();
        } catch (e) { showError(e); }
    };
    window.openMyEvidenceUpload = id => { uploadRecordId = id; document.getElementById('my-cert-evidence-file').click(); };
    window.uploadMyCertificationEvidence = async function (input) {
        const file = input.files && input.files[0];
        if (!file || !uploadRecordId) return;
        try {
            await uploadCertificationEvidence(uploadRecordId, file);
            SES.toast.success('証憑を登録しました'); await load();
        } catch (e) { showError(e); } finally { input.value = ''; uploadRecordId = null; }
    };
    window.showMyCertification = async function (id) {
        try {
            const item = await SES.api.get(base + '/certifications/' + encodeURIComponent(id)); const record = item.record || {};
            const evidenceHtml = (item.evidences || []).map(e => '<a href="' + base + '/certifications/' + encodeURIComponent(id) + '/evidence/' + encodeURIComponent(e.documentId) + '/versions/' + encodeURIComponent(e.versionNo) + '/download">' + esc(e.originalName || '証憑') + '</a>').join('<br>') || 'なし';
            const controls = '<button class="btn btn-sm btn-outline-primary me-1" onclick="openMyEvidenceUpload(' + id + '); Swal.close();">証憑upload</button>' + ((record.recordState === 'DRAFT' || record.recordState === 'SUBMITTED' || record.recordState === 'VERIFIED' || record.recordState === 'ACTIVE') ? '<button class="btn btn-sm btn-outline-warning" onclick="correctMyCertification(' + id + ',' + (record.version ?? 0) + ',\'' + esc(record.expiresOn || '') + '\')">訂正</button>' : '');
            Swal.fire({ title: String(record.certificationDisplayName || '資格詳細'), html: '<p>状態: ' + esc(record.recordState || '-') + ' / version: ' + esc(record.version ?? '-') + '</p><p>取得日: ' + esc(record.acquiredOn || '-') + '</p><p>期限: ' + esc(record.expiresOn || '-') + '</p><p>証憑: ' + evidenceHtml + '</p><div>' + controls + '</div>', confirmButtonText: '閉じる' });
        } catch (e) { showError(e); }
    };
    window.withdrawMyCertification = async function (id, version) { const result = await Swal.fire({ title: '資格申請を取消', input: 'text', inputLabel: '理由', showCancelButton: true, confirmButtonText: '取消', cancelButtonText: '戻る' }); if (!result.isConfirmed) return; try { await SES.api.post(base + '/certifications/' + id + '/withdraw', { expectedVersion: version, reason: result.value }); await load(); } catch (e) { showError(e); } };
    window.correctMyCertification = async function (id, version, expiresOn) { const date = await Swal.fire({ title: '取得日を訂正', input: 'date', inputValue: today(), showCancelButton: true, confirmButtonText: '次へ', cancelButtonText: '戻る' }); if (!date.isConfirmed) return; const reason = await Swal.fire({ title: '訂正理由', input: 'text', showCancelButton: true, confirmButtonText: '訂正', cancelButtonText: '戻る' }); if (!reason.isConfirmed) return; try { await SES.api.post(base + '/certifications/' + id + '/correct', { expectedVersion: version, acquiredOn: date.value, expiresOn: expiresOn || null, reason: reason.value }); await load(); } catch (e) { showError(e); } };
    window.resubmitMyCertification = async function (id) { const result = await Swal.fire({ title: '資格を再申請', input: 'text', inputLabel: '資格番号（任意）', showCancelButton: true, confirmButtonText: '再申請', cancelButtonText: '戻る' }); if (!result.isConfirmed) return; try { await SES.api.post(base + '/certifications/' + id + '/resubmit', { certificateNumber: result.value }); await load(); } catch (e) { showError(e); } };
    window.openMyPlanForm = () => bootstrap.Modal.getOrCreateInstance(document.getElementById('my-plan-modal')).show();
    window.submitMyPlan = async function () { try { await SES.api.post(base + '/learning-plans', { title: document.getElementById('my-plan-title').value, goalDescription: document.getElementById('my-plan-goal').value, attainmentCriteria: document.getElementById('my-plan-criteria').value, plannedStartOn: document.getElementById('my-plan-start').value || null, plannedEndOn: document.getElementById('my-plan-end').value || null, plannedCostJpy: Number(document.getElementById('my-plan-cost').value || 0) }); bootstrap.Modal.getInstance(document.getElementById('my-plan-modal')).hide(); await load(); } catch (e) { showError(e); } };
    window.submitMyPlanById = async function (id, version) { const reason = await Swal.fire({ title: '0円の場合は理由を入力', input: 'text', showCancelButton: true, confirmButtonText: '申請', cancelButtonText: '戻る' }); if (!reason.isConfirmed) return; try { await SES.api.post(base + '/learning-plans/' + id + '/submit', { expectedVersion: version, zeroCostReason: reason.value }); await load(); } catch (e) { showError(e); } };
    window.withdrawMyPlan = async function (id, version) { const reason = await Swal.fire({ title: '学習計画を取消', input: 'text', showCancelButton: true, confirmButtonText: '取消', cancelButtonText: '戻る' }); if (!reason.isConfirmed) return; try { await SES.api.post(base + '/learning-plans/' + id + '/withdraw', { expectedVersion: version, reason: reason.value }); await load(); } catch (e) { showError(e); } };
    window.resubmitMyPlan = async function (id) { try { await SES.api.post(base + '/learning-plans/' + id + '/resubmit', {}); await load(); } catch (e) { showError(e); } };
    window.enrollMyPlan = async function (id) { const courseId = Number(document.getElementById('my-plan-course-' + id).value); if (!courseId) return; try { await SES.api.post(base + '/learning-plans/' + id + '/enrollments', { courseId: courseId }); await load(); } catch (e) { showError(e); } };
    window.startMyEnrollment = async function (id, version) { try { await SES.api.post(base + '/enrollments/' + id + '/start', { expectedVersion: version }); await load(); } catch (e) { showError(e); } };
    window.completeMyEnrollment = async function (id, version) { const result = await Swal.fire({ title: '受講完了日', input: 'date', inputValue: today(), showCancelButton: true, confirmButtonText: '完了', cancelButtonText: '戻る' }); if (!result.isConfirmed) return; try { await SES.api.post(base + '/enrollments/' + id + '/complete', { expectedVersion: version, completedOn: result.value }); await load(); } catch (e) { showError(e); } };
    window.cancelMyEnrollment = async function (id, version) { const reason = await Swal.fire({ title: '受講を取消', input: 'text', showCancelButton: true, confirmButtonText: '取消', cancelButtonText: '戻る' }); if (!reason.isConfirmed) return; try { await SES.api.post(base + '/enrollments/' + id + '/cancel', { expectedVersion: version, reason: reason.value }); await load(); } catch (e) { showError(e); } };
    window.exportMyCertificationLearningGap = () => SES.download(base + '/export', 'my-certification-learning-gap.csv');
    document.addEventListener('DOMContentLoaded', load);
}());
