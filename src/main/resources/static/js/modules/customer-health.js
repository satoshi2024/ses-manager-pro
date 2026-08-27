/**
 * 顧客ヘルススコア・CSダッシュボード JS (100点減点モデル: WIP-3)
 */
(function (window, $) {
    'use strict';

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    function getHealthBadge(status, score) {
        if (status === 'HEALTHY') {
            return `<span class="badge bg-success"><i class="bi bi-check-circle-fill me-1"></i>健全 (${score}点)</span>`;
        } else if (status === 'WARNING') {
            return `<span class="badge bg-warning text-dark"><i class="bi bi-exclamation-triangle-fill me-1"></i>注意 (${score}点)</span>`;
        } else {
            return `<span class="badge bg-danger"><i class="bi bi-exclamation-octagon-fill me-1"></i>危険 (${score}点)</span>`;
        }
    }

    function init() {
        loadHealthData();

        $('#btnSearchHealth').on('click', function () {
            loadHealthData();
        });

        $('#btnGenerateSnapshot').on('click', function () {
            generateSnapshot();
        });

        $('#qbrForm').on('submit', function (e) {
            e.preventDefault();
            saveQbr();
        });
    }

    function loadHealthData() {
        const keyword = $('#healthKeyword').val();
        const status = $('#healthStatusFilter').val();
        const $tbody = $('#healthTableBody');
        $tbody.html('<tr><td colspan="9" class="text-center text-muted py-4">読み込み中...</td></tr>');

        $.ajax({
            url: '/api/customer-success/health',
            type: 'GET',
            data: { keyword: keyword, healthStatus: status },
            success: function (res) {
                if (res.code === 200) {
                    renderHealthTable(res.data || []);
                } else {
                    $tbody.html(`<tr><td colspan="9" class="text-center text-danger py-4">${escapeHtml(res.message || 'データ取得エラー')}</td></tr>`);
                }
            },
            error: function () {
                $tbody.html('<tr><td colspan="9" class="text-center text-danger py-4">通信エラーが発生しました</td></tr>');
            }
        });
    }

    function renderHealthTable(list) {
        const $tbody = $('#healthTableBody').empty();
        if (list.length === 0) {
            $tbody.html('<tr><td colspan="9" class="text-center text-muted py-4">該当する顧客データがありません</td></tr>');
            return;
        }

        list.forEach(function (d) {
            const scoreBar = `
                <div class="progress" style="height: 20px;">
                    <div class="progress-bar ${d.healthScore >= 80 ? 'bg-success' : (d.healthScore >= 50 ? 'bg-warning text-dark' : 'bg-danger')}" 
                         role="progressbar" style="width: ${d.healthScore}%;" aria-valuenow="${d.healthScore}" aria-valuemin="0" aria-valuemax="100">
                        ${d.healthScore}点
                    </div>
                </div>
            `;

            const missingBadge = (d.missingInputs && d.missingInputs.length > 0)
                ? `<span class="badge bg-secondary" title="欠損項目">${escapeHtml(d.missingInputs.join(', '))}</span>`
                : '<span class="text-muted small">-</span>';

            const overdueBadge = d.arOverdueFlag
                ? '<span class="badge bg-danger">延滞あり</span>'
                : '<span class="badge bg-success-subtle text-success">正常</span>';

            const tr = `
                <tr>
                    <td><strong>${escapeHtml(d.customerName)}</strong></td>
                    <td style="min-width: 140px;">${scoreBar}</td>
                    <td>${getHealthBadge(d.healthStatus, d.healthScore)}</td>
                    <td><span class="badge ${d.openCriticalIssuesCount > 0 ? 'bg-danger' : 'bg-secondary'}">${d.openCriticalIssuesCount || 0} 件</span></td>
                    <td><span class="badge ${d.slaBreachCount30d > 0 ? 'bg-warning text-dark' : 'bg-secondary'}">${d.slaBreachCount30d || 0} 件</span></td>
                    <td><strong>${d.avgCsatScore ? d.avgCsatScore + ' / 5.0' : '未回答'}</strong></td>
                    <td>${overdueBadge}</td>
                    <td>${missingBadge}</td>
                    <td class="text-end">
                        <button type="button" class="btn btn-outline-info btn-sm btn-show-factors" 
                                data-customer-id="${d.customerId}" 
                                data-customer-name="${escapeHtml(d.customerName)}"
                                data-score="${d.healthScore}"
                                data-explanation="${escapeHtml(d.factorsExplanation || '')}"
                                data-breakdown='${JSON.stringify(d.factorBreakdown || {})}'>
                            <i class="bi bi-info-circle me-1"></i>要因内訳
                        </button>
                    </td>
                </tr>
            `;
            $tbody.append(tr);
        });

        $('.btn-show-factors').on('click', function () {
            const custName = $(this).data('customer-name');
            const score = $(this).data('score');
            const explanation = $(this).data('explanation');
            const breakdown = $(this).data('breakdown');

            let detailHtml = `<p><strong>総合スコア:</strong> ${score} 点</p>`;
            detailHtml += `<p><strong>減点要因:</strong> ${explanation || '減点なし（健全）'}</p>`;
            detailHtml += `<hr/><p class="mb-1 text-muted small"><strong>詳細指標:</strong></p><ul>`;
            for (let k in breakdown) {
                detailHtml += `<li><code>${escapeHtml(k)}</code>: ${escapeHtml(JSON.stringify(breakdown[k]))}</li>`;
            }
            detailHtml += `</ul>`;

            Swal.fire({
                title: `${custName} - ヘルススコア要因内訳`,
                html: detailHtml,
                icon: 'info',
                confirmButtonText: '閉じる'
            });
        });
    }

    function generateSnapshot() {
        Swal.fire({
            title: '月次スナップショット作成',
            text: '全顧客の最新ヘルススコアを計算し、今月のスナップショットを記録・更新しますか？',
            icon: 'question',
            showCancelButton: true,
            confirmButtonText: '作成する',
            cancelButtonText: 'キャンセル'
        }).then(function (result) {
            if (result.isConfirmed) {
                $.ajax({
                    url: '/api/customer-success/health/snapshot',
                    type: 'POST',
                    success: function (res) {
                        if (res.code === 200) {
                            Toast.fire({ icon: 'success', title: 'スナップショットを作成しました' });
                            loadHealthData();
                        } else {
                            Swal.fire('エラー', res.message || '作成失敗', 'error');
                        }
                    },
                    error: function () {
                        Swal.fire('エラー', '通信エラーが発生しました', 'error');
                    }
                });
            }
        });
    }

    function saveQbr() {
        const payload = {
            customerId: $('#qbrCustomerId').val(),
            title: $('#qbrTitle').val(),
            meetingDate: $('#qbrMeetingDate').val(),
            attendees: $('#qbrAttendees').val(),
            agenda: $('#qbrAgenda').val(),
            discussion: $('#qbrDiscussion').val(),
            decisions: $('#qbrDecisions').val(),
            nextMeetingDate: $('#qbrNextMeetingDate').val() || null
        };

        $.ajax({
            url: '/api/customer-success/qbrs',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            success: function (res) {
                if (res.code === 200) {
                    $('#qbrModal').modal('hide');
                    $('#qbrForm')[0].reset();
                    Toast.fire({ icon: 'success', title: '定例会(QBR)記録を保存しました' });
                    loadHealthData();
                } else {
                    Swal.fire('エラー', res.message || '保存失敗', 'error');
                }
            },
            error: function () {
                Swal.fire('エラー', '通信エラーが発生しました', 'error');
            }
        });
    }

    $(document).ready(init);
})(window, jQuery);
