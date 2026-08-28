/* 要員本人の資格申請・学習計画。engineerIdはAPIへ送信しない。 */
(function () {
    'use strict';
    const base = '/api/my/certification-learning-gap';
    let current = null;
    const esc = value => SES.escapeHtml(value == null ? '' : String(value));
    const showError = error => { const el = document.getElementById('my-cert-error'); el.textContent = error?.message || '処理に失敗しました'; el.classList.remove('d-none'); };
    const hideError = () => document.getElementById('my-cert-error').classList.add('d-none');

    async function load() {
        hideError();
        try { current = await SES.api.get(base); render(current); } catch (e) { showError(e); }
    }
    function render(data) {
        const certifications = data?.certifications || [];
        const rows = document.getElementById('my-cert-rows');
        document.getElementById('my-cert-empty').classList.toggle('d-none', certifications.length > 0);
        rows.innerHTML = certifications.map(item => {
            const record = item.record || {};
            return '<tr><td>' + esc(record.certificationDisplayName || '-') + '</td><td>' + esc(record.acquiredOn || '-') + '</td><td>' + esc(record.expiresOn || '-') + '</td><td>' + esc(record.recordState || '-') + '</td><td class="optional-column">' + ((item.evidences || []).length) + '件</td><td><button class="btn btn-sm btn-outline-info" onclick="showMyCertification(' + record.id + ')">詳細</button></td></tr>';
        }).join('');
        const plans = data?.learningPlans || [];
        document.getElementById('my-plan-empty').classList.toggle('d-none', plans.length > 0);
        document.getElementById('my-plan-list').innerHTML = plans.map(item => {
            const plan = item.plan || {};
            return '<div class="border-bottom border-secondary py-2"><div class="fw-semibold text-light">' + esc(plan.title || '-') + '</div><div class="small text-muted">' + esc(plan.status || '-') + ' / ' + esc(plan.plannedCostJpy == null ? '-' : plan.plannedCostJpy + '円') + '</div><div class="small text-secondary">受講 ' + ((item.enrollments || []).length) + '件</div></div>';
        }).join('');
    }
    window.openMyCertificationApply = () => bootstrap.Modal.getOrCreateInstance(document.getElementById('my-cert-apply-modal')).show();
    window.submitMyCertificationApply = async function () {
        try {
            await SES.api.post(base + '/certifications', { certificationId: Number(document.getElementById('my-certification-id').value), acquiredOn: document.getElementById('my-cert-acquired-on').value, expiresOn: document.getElementById('my-cert-expires-on').value, certificateNumber: document.getElementById('my-cert-number').value });
            bootstrap.Modal.getInstance(document.getElementById('my-cert-apply-modal')).hide(); await load();
        } catch (e) { showError(e); }
    };
    window.showMyCertification = async function (id) { try { const item = await SES.api.get(base + '/certifications/' + encodeURIComponent(id)); const record = item.record || {}; const evidenceHtml = (item.evidences || []).map(e => '<a href="' + base + '/certifications/' + encodeURIComponent(id) + '/evidence/' + encodeURIComponent(e.documentId) + '/versions/' + encodeURIComponent(e.versionNo) + '/download">' + esc(e.originalName || '証憑') + '</a>').join('<br>') || 'なし'; Swal.fire({ title: esc(record.certificationDisplayName || '資格詳細'), html: '<p>状態: ' + esc(record.recordState || '-') + '</p><p>取得日: ' + esc(record.acquiredOn || '-') + '</p><p>期限: ' + esc(record.expiresOn || '-') + '</p><p>証憑: ' + evidenceHtml + '</p>', confirmButtonText: '閉じる' }); } catch (e) { showError(e); } };
    window.openMyPlanForm = () => bootstrap.Modal.getOrCreateInstance(document.getElementById('my-plan-modal')).show();
    window.submitMyPlan = async function () { try { await SES.api.post(base + '/learning-plans', { title: document.getElementById('my-plan-title').value, goalDescription: document.getElementById('my-plan-goal').value, attainmentCriteria: document.getElementById('my-plan-criteria').value, plannedCostJpy: Number(document.getElementById('my-plan-cost').value || 0) }); bootstrap.Modal.getInstance(document.getElementById('my-plan-modal')).hide(); await load(); } catch (e) { showError(e); } };
    window.exportMyCertificationLearningGap = () => SES.download(base + '/export', 'my-certification-learning-gap.csv');
    document.addEventListener('DOMContentLoaded', load);
}());
