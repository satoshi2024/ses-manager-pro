/* 資格・学習・skill gap管理。検索条件はlist/detail/count/exportで共有する。 */
(function () {
    'use strict';

    const pageSize = 10;
    let pageData = null;

    function value(id) { return document.getElementById(id)?.value || ''; }

    function params() {
        const result = { current: pageData ? pageData.current : 1, size: pageSize };
        const fields = { engineerName: value('cert-gap-name'), engineerStatus: value('cert-gap-status'), lifecycleState: value('cert-gap-lifecycle'), asOf: value('cert-gap-asof') };
        Object.keys(fields).forEach(function (key) { if (fields[key]) result[key] = fields[key]; });
        return result;
    }

    function esc(text) {
        return SES.escapeHtml(text == null ? '' : String(text));
    }

    window.loadCertificationLearningGap = async function (page) {
        const query = params(); query.current = page || 1;
        try {
            const data = await SES.api.get('/api/certification-learning-gap', query);
            pageData = data;
            render(data);
        } catch (e) {
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
                    + '<td class="text-end pe-4"><button class="btn btn-sm btn-outline-info" onclick="showCertificationLearningGapDetail(' + row.engineerId + ')"><i class="bi bi-eye"></i><span class="visually-hidden">詳細</span></button></td></tr>';
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
            document.getElementById('cert-gap-detail-title').textContent = esc(row.engineerName) + ' - 資格・学習・gap詳細';
            document.getElementById('cert-gap-detail-body').innerHTML = detailHtml(row);
            bootstrap.Modal.getOrCreateInstance(document.getElementById('cert-gap-detail-modal')).show();
        } catch (e) { /* 共通APIエラーを表示済み */ }
    };

    function detailHtml(row) {
        const certs = (row.certifications || []).map(function (item) {
            const number = item.certificateNumber ? esc(item.certificateNumber) : esc(item.certificateNumberMasked || '未登録');
            return '<tr><td>' + esc(item.certificationDisplayName || '-') + '</td><td>' + esc(item.effectiveState || item.recordState || '-') + '</td><td>' + esc(item.expiresOn || '-') + '</td><td>' + number + '</td></tr>';
        }).join('');
        const plans = (row.trainings || []).map(function (item) { return '<tr><td>' + esc(item.title || '-') + '</td><td>' + esc(item.status || '-') + '</td><td>' + esc(item.courseName || '-') + '</td></tr>'; }).join('');
        const gaps = (row.skillGaps || []).map(function (item) { return '<li>' + esc(item.canonicalName || item.requestedName || item.key) + '：' + (item.gap ? 'gap' : '充足') + (item.unknown ? '（未知skill）' : '') + '</li>'; }).join('');
        return '<p class="text-muted">状態: ' + esc(row.engineerStatus || '-') + ' / ライフサイクル: ' + lifecycleLabel(row.lifecycleState) + '</p>'
            + '<h6>資格履歴</h6><div class="table-responsive"><table class="table table-dark table-sm"><thead><tr><th>資格</th><th>状態</th><th>期限</th><th>番号</th></tr></thead><tbody>' + (certs || '<tr><td colspan="4">資格履歴なし</td></tr>') + '</tbody></table></div>'
            + '<h6 class="mt-4">学習計画・受講</h6><div class="table-responsive"><table class="table table-dark table-sm"><thead><tr><th>計画</th><th>状態</th><th>コース</th></tr></thead><tbody>' + (plans || '<tr><td colspan="3">学習計画なし</td></tr>') + '</tbody></table></div>'
            + '<h6 class="mt-4">skill gap</h6><ul>' + (gaps || '<li>gapデータなし</li>') + '</ul>';
    }

    window.exportCertificationLearningGap = async function () {
        const query = params(); delete query.current; delete query.size;
        await SES.download('/api/certification-learning-gap/export?' + new URLSearchParams(query).toString(), '資格・学習・skill-gap.csv');
    };

    document.addEventListener('DOMContentLoaded', function () { loadCertificationLearningGap(1); });
}());
