// 案件詳細画面
$(function () {
    const projectId = new URLSearchParams(window.location.search).get('id');
    if (!projectId) {
        showError('案件IDが指定されていません');
        return;
    }

    // staffing-capacity-planning（T077）: 募集ポジションボード
    if (window.SES_staffing) {
        SES_staffing.initProjectBoard(projectId);
    }

    $.ajax({
        url: '/api/projects/' + projectId,
        method: 'GET',
        success: function (res) {
            if (res.code !== 200 || !res.data) {
                showError('案件が見つかりません');
                return;
            }
            render(res.data);
        },
        error: function (xhr) {
            showError(xhr.status === 404 || xhr.status === 403 ? '案件が見つかりません' : '案件情報の取得に失敗しました');
        }
    });

    function render(p) {
        $('#project-detail-loading').addClass('d-none');
        $('#project-detail-error').addClass('d-none');
        $('#pd-name').text(p.projectName || '—');
        $('#pd-status').text(p.status || '—');
        $('#pd-priority').text(p.priority || '—');
        $('#pd-customerId').text(p.customerId != null ? '#' + p.customerId : '—');
        $('#pd-flow').text(p.commercialFlow || '—');
        $('#pd-opportunity').text(p.sourceOpportunityId != null ? '#' + p.sourceOpportunityId : '—');
        $('#pd-unitMin').text(p.unitPriceMin != null ? '¥' + Number(p.unitPriceMin).toLocaleString() : '—');
        $('#pd-unitMax').text(p.unitPriceMax != null ? '¥' + Number(p.unitPriceMax).toLocaleString() : '—');
        $('#pd-count').text(p.requiredCount != null ? p.requiredCount + '人' : '—');
        $('#pd-location').text(p.workLocation || '—');
        $('#pd-remote').text(p.remoteType || '—');
        $('#pd-period').text([p.startDate, p.endDate].filter(Boolean).join(' 〜 ') || '—');
        $('#pd-description').text(p.description || '—');
        $('#pd-remarks').text(p.remarks || '—');
        $('#project-detail-body').removeClass('d-none');
    }

    function showError(message) {
        $('#project-detail-loading').addClass('d-none');
        $('#project-detail-error').text(SES.escapeHtml(message)).removeClass('d-none');
    }
});
