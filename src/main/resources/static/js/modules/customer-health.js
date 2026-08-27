/**
 * 顧客ヘルススコア・CSダッシュボード JS
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
        } else if (status === 'NEUTRAL') {
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
        $tbody.html('<tr><td colspan="8" class="text-center text-muted py-4">読み込み中...</td></tr>');

        $.ajax({
            url: '/api/customer-success/health',
            type: 'GET',
            data: { keyword: keyword, healthStatus: status },
            success: function (res) {
                if (res.code === 200) {
                    renderHealthTable(res.data || []);
                } else {
                    $tbody.html(`<tr><td colspan="8" class="text-center text-danger py-4">${escapeHtml(res.message || 'データ取得エラー')}</td></tr>`);
                }
            },
            error: function () {
                $tbody.html('<tr><td colspan="8" class="text-center text-danger py-4">通信エラーが発生しました</td></tr>');
            }
        });
    }

    function renderHealthTable(list) {
        const $tbody = $('#healthTableBody').empty();
        if (list.length === 0) {
            $tbody.html('<tr><td colspan="8" class="text-center text-muted py-4">該当する顧客データがありません</td></tr>');
            return;
        }

        list.forEach(function (d) {
            const scoreBar = `
                <div class="progress" style="height: 20px;">
                    <div class="progress-bar ${d.healthScore >= 80 ? 'bg-success' : (d.healthScore >= 60 ? 'bg-warning' : 'bg-danger')}" 
                         role="progressbar" style="width: ${d.healthScore}%;" aria-valuenow="${d.healthScore}" aria-valuemin="0" aria-valuemax="100">
                        ${d.healthScore}点
                    </div>
                </div>
            `;

            const tr = `
                <tr>
                    <td><strong>${escapeHtml(d.customerName)}</strong></td>
                    <td style="min-width: 140px;">${scoreBar}</td>
                    <td>${getHealthBadge(d.healthStatus, d.healthScore)}</td>
                    <td><span class="badge bg-secondary">${d.slaComplianceScore || 0} / 30</span></td>
                    <td><span class="badge bg-secondary">${d.csatScore || 0} / 25</span></td>
                    <td><span class="badge bg-secondary">${d.engagementScore || 0} / 25</span></td>
                    <td><span class="badge bg-secondary">${d.communicationScore || 0} / 20</span></td>
                    <td>
                        <button type="button" class="btn btn-sm btn-outline-info btn-open-qbr-for-cust" data-cust-id="${d.customerId}">
                            <i class="bi bi-calendar-event me-1"></i>定例会
                        </button>
                    </td>
                </tr>
            `;
            $tbody.append(tr);
        });

        $('.btn-open-qbr-for-cust').on('click', function () {
            const custId = $(this).data('cust-id');
            $('#qbrCustomerId').val(custId);
            $('#qbrMeetingDate').val(new Date().toISOString().substring(0, 10));
            new bootstrap.Modal($('#qbrModal')[0]).show();
        });
    }

    function generateSnapshot() {
        SES.swalConfirm('月次スナップショット作成', '現在のヘルススコアを月次スナップショットとして保存しますか？', function () {
            $.ajax({
                url: '/api/customer-success/health/snapshots',
                type: 'POST',
                headers: SES.csrf.header(),
                success: function (res) {
                    if (res.code === 200) {
                        Toast.success('月次スナップショットを作成しました');
                    } else {
                        Toast.error(res.message || '作成に失敗しました');
                    }
                },
                error: function () {
                    Toast.error('通信エラーが発生しました');
                }
            });
        });
    }

    function saveQbr() {
        const payload = {
            customerId: $('#qbrCustomerId').val(),
            meetingDate: $('#qbrMeetingDate').val(),
            title: $('#qbrTitle').val(),
            attendees: $('#qbrAttendees').val(),
            csatScore: $('#qbrCsatScore').val() ? parseInt($('#qbrCsatScore').val(), 10) : null,
            agenda: $('#qbrAgenda').val(),
            minutes: $('#qbrMinutes').val(),
            actionItems: $('#qbrActionItems').val()
        };

        $('#btnSaveQbr').prop('disabled', true);
        $('#qbrModalError').addClass('d-none').text('');

        $.ajax({
            url: '/api/customer-success/qbrs',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            headers: SES.csrf.header(),
            success: function (res) {
                $('#btnSaveQbr').prop('disabled', false);
                if (res.code === 200) {
                    bootstrap.Modal.getInstance($('#qbrModal')[0]).hide();
                    $('#qbrForm')[0].reset();
                    Toast.success('定例会記録を保存しました');
                    loadHealthData();
                } else {
                    $('#qbrModalError').removeClass('d-none').text(res.message || '保存に失敗しました');
                }
            },
            error: function (xhr) {
                $('#btnSaveQbr').prop('disabled', false);
                const msg = xhr.responseJSON ? xhr.responseJSON.message : 'エラーが発生しました';
                $('#qbrModalError').removeClass('d-none').text(msg);
            }
        });
    }

    $(document).ready(init);
})(window, jQuery);
