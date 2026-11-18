/**
 * 顧客ヘルススコア・CSダッシュボード JS (100点減点モデル)
 * 画面文言は SES.i18n.t（messages の customerSuccess.* 等）から取る（RB-I18N P3-N2-8）。
 */
(function (window, $) {
    'use strict';

    function t(key, ...args) {
        return SES.i18n.t(key, ...args);
    }

    function escapeHtml(str) {
        if (str === null || str === undefined || str === '') return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    function getHealthBadge(status, score) {
        if (status === 'HEALTHY') {
            return `<span class="badge bg-success"><i class="bi bi-check-circle-fill me-1"></i>${escapeHtml(t('customerSuccess.health.badge.healthy', score))}</span>`;
        } else if (status === 'WARNING') {
            return `<span class="badge bg-warning text-dark"><i class="bi bi-exclamation-triangle-fill me-1"></i>${escapeHtml(t('customerSuccess.health.badge.warning', score))}</span>`;
        } else {
            return `<span class="badge bg-danger"><i class="bi bi-exclamation-octagon-fill me-1"></i>${escapeHtml(t('customerSuccess.health.badge.critical', score))}</span>`;
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
        $tbody.html(`<tr><td colspan="9" class="text-center text-muted py-4">${escapeHtml(t('common.loading'))}</td></tr>`);

        $.ajax({
            url: '/api/customer-success/health',
            type: 'GET',
            data: { keyword: keyword, healthStatus: status },
            success: function (res) {
                if (res.code === 200) {
                    renderHealthTable(res.data || []);
                } else {
                    $tbody.html(`<tr><td colspan="9" class="text-center text-danger py-4">${escapeHtml(res.message || t('customerSuccess.health.loadError'))}</td></tr>`);
                }
            },
            error: function () {
                $tbody.html(`<tr><td colspan="9" class="text-center text-danger py-4">${escapeHtml(t('error.networkError'))}</td></tr>`);
            }
        });
    }

    function renderHealthTable(list) {
        const $tbody = $('#healthTableBody').empty();
        if (list.length === 0) {
            $tbody.html(`<tr><td colspan="9" class="text-center text-muted py-4">${escapeHtml(t('customerSuccess.health.empty'))}</td></tr>`);
            return;
        }

        list.forEach(function (d) {
            const scoreBar = `
                <div class="progress" style="height: 20px;">
                    <div class="progress-bar ${d.healthScore >= 80 ? 'bg-success' : (d.healthScore >= 50 ? 'bg-warning text-dark' : 'bg-danger')}"
                         role="progressbar" style="width: ${d.healthScore}%;" aria-valuenow="${d.healthScore}" aria-valuemin="0" aria-valuemax="100">
                        ${escapeHtml(t('customerSuccess.health.points', d.healthScore))}
                    </div>
                </div>
            `;

            const missingBadge = (d.missingInputs && d.missingInputs.length > 0)
                ? `<span class="badge bg-secondary" title="${escapeHtml(t('customerSuccess.health.missingTitle'))}">${escapeHtml(d.missingInputs.join(', '))}</span>`
                : '<span class="text-muted small">-</span>';

            const overdueBadge = d.arOverdueFlag
                ? `<span class="badge bg-danger">${escapeHtml(t('customerSuccess.health.overdue'))}</span>`
                : `<span class="badge bg-success-subtle text-success">${escapeHtml(t('customerSuccess.health.noOverdue'))}</span>`;

            const tr = `
                <tr>
                    <td><strong>${escapeHtml(d.customerName)}</strong></td>
                    <td style="min-width: 140px;">${scoreBar}</td>
                    <td>${getHealthBadge(d.healthStatus, d.healthScore)}</td>
                    <td><span class="badge ${d.openCriticalIssuesCount > 0 ? 'bg-danger' : 'bg-secondary'}">${escapeHtml(t('customerSuccess.health.count', d.openCriticalIssuesCount || 0))}</span></td>
                    <td><span class="badge ${d.slaBreachCount30d > 0 ? 'bg-warning text-dark' : 'bg-secondary'}">${escapeHtml(t('customerSuccess.health.count', d.slaBreachCount30d || 0))}</span></td>
                    <td><strong>${escapeHtml(d.avgCsatScore ? t('customerSuccess.health.csatValue', d.avgCsatScore) : t('customerSuccess.health.csatNone'))}</strong></td>
                    <td>${overdueBadge}</td>
                    <td>${missingBadge}</td>
                    <td class="text-end">
                        <button type="button" class="btn btn-outline-info btn-sm btn-show-factors"
                                data-customer-id="${d.customerId}"
                                data-customer-name="${escapeHtml(d.customerName)}"
                                data-score="${d.healthScore}"
                                data-explanation="${escapeHtml(d.factorsExplanation || '')}"
                                data-breakdown='${escapeHtml(JSON.stringify(d.factorBreakdown || {}))}'>
                            <i class="bi bi-info-circle me-1"></i>${escapeHtml(t('customerSuccess.health.factors'))}
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

            let detailHtml = `<p><strong>${escapeHtml(t('customerSuccess.health.totalScore'))}</strong> ${escapeHtml(t('customerSuccess.health.points', score))}</p>`;
            detailHtml += `<p><strong>${escapeHtml(t('customerSuccess.health.deductions'))}</strong> ${escapeHtml(explanation || t('customerSuccess.health.noDeduction'))}</p>`;
            detailHtml += `<hr/><p class="mb-1 text-muted small"><strong>${escapeHtml(t('customerSuccess.health.detailMetrics'))}</strong></p><ul>`;
            for (let k in breakdown) {
                detailHtml += `<li><code>${escapeHtml(k)}</code>: ${escapeHtml(JSON.stringify(breakdown[k]))}</li>`;
            }
            detailHtml += `</ul>`;

            Swal.fire({
                titleText: t('customerSuccess.health.factorsTitle', String(custName)),
                html: detailHtml,
                icon: 'info',
                confirmButtonText: t('common.close')
            });
        });
    }

    function generateSnapshot() {
        Swal.fire({
            title: t('customerSuccess.health.generateSnapshot'),
            text: t('customerSuccess.snapshot.confirm'),
            icon: 'question',
            showCancelButton: true,
            confirmButtonText: t('customerSuccess.snapshot.create'),
            cancelButtonText: t('common.cancel')
        }).then(function (result) {
            if (result.isConfirmed) {
                $.ajax({
                    url: '/api/customer-success/health/snapshots',
                    type: 'POST',
                    headers: window.SES && window.SES.csrf ? window.SES.csrf.header() : {},
                    success: function (res) {
                        if (res.code === 200) {
                            if (window.Toast) {
                                window.Toast.success(t('customerSuccess.snapshot.created'));
                            } else {
                                Swal.fire(t('customerSuccess.snapshot.done'), t('customerSuccess.snapshot.created'), 'success');
                            }
                            loadHealthData();
                        } else {
                            Swal.fire(t('common.error'), res.message || t('customerSuccess.snapshot.failed'), 'error');
                        }
                    },
                    error: function (xhr) {
                        const msg = xhr.responseJSON ? xhr.responseJSON.message : t('error.networkError');
                        Swal.fire(t('common.error'), msg, 'error');
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
            minutes: $('#qbrMinutes').val(),
            actionItems: $('#qbrActionItems').val()
        };

        $.ajax({
            url: '/api/customer-success/qbrs',
            type: 'POST',
            contentType: 'application/json',
            headers: window.SES && window.SES.csrf ? window.SES.csrf.header() : {},
            data: JSON.stringify(payload),
            success: function (res) {
                if (res.code === 200) {
                    $('#qbrModal').modal('hide');
                    $('#qbrForm')[0].reset();
                    if (window.Toast) {
                        window.Toast.success(t('customerSuccess.qbr.saved'));
                    } else {
                        Swal.fire(t('customerSuccess.qbr.savedTitle'), t('customerSuccess.qbr.saved'), 'success');
                    }
                    loadHealthData();
                } else {
                    Swal.fire(t('common.error'), res.message || t('error.saveFailed'), 'error');
                }
            },
            error: function (xhr) {
                const msg = xhr.responseJSON ? xhr.responseJSON.message : t('error.networkError');
                Swal.fire(t('common.error'), msg, 'error');
            }
        });
    }

    $(document).ready(init);
})(window, jQuery);
