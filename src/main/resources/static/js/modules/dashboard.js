let revenueChartInstance = null;
let statusChartInstance = null;

$(document).ready(function() {
    $('#btn-print-report').on('click', function() { window.print(); });
    $('#btn-export-revenue').on('click', function() { exportRevenue(); });

    // テーマ変更時にチャートを再配色する（発火元: SES.theme.applyTheme）
    document.addEventListener('ses:theme-changed', function() {
        SES.theme.applyChartTheme(revenueChartInstance);
        SES.theme.applyChartTheme(statusChartInstance);
    });

    // 退場予定者リストのAIマッチングボタン（行はAjaxで再描画されるため委譲で捕捉）
    $('#retiring-table-body').on('click', '.btn-ai-match', function() {
        matchAI(Number($(this).data('id')), String($(this).data('name')));
    });

    const currentFiscalYear = getCurrentFiscalYear();
    populateFiscalYearSelector(currentFiscalYear);
    $('#fiscal-year-selector').val(currentFiscalYear);

    loadDashboardData(currentFiscalYear);

    $('#fiscal-year-selector').on('change', function() {
        loadDashboardData($(this).val());
    });
});

function getCurrentFiscalYear() {
    const now = new Date();
    const month = now.getMonth() + 1; // 1-12
    return month >= 4 ? now.getFullYear() : now.getFullYear() - 1;
}

function populateFiscalYearSelector(currentYear) {
    const selector = $('#fiscal-year-selector');
    selector.empty();
    for (let i = 0; i < 5; i++) {
        const y = currentYear - i;
        selector.append(`<option value="${y}">${y}年度</option>`);
    }
}

function loadDashboardData(year) {
    $.ajax({
        url: '/api/dashboard/summary',
        method: 'GET',
        data: { year: year },
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderKPIs(res.data.kpi);
                renderCharts(res.data.charts);
                renderRetiringList(res.data.retiring);
            } else {
                Toast.error(res.message || SES.i18n.t('dashboard.error.fetch_failed'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('dashboard.error.network'));
        }
    });
}



function renderKPIs(kpi) {
    $('#kpi-utilization').text(kpi.utilization + '%');
    updateTrendBadge($('#kpi-utilization-trend'), kpi.utilizationTrend);
    
    $('#kpi-bench-count').text(kpi.benchCount);
    
    $('#kpi-revenue').text('¥' + kpi.revenue.toLocaleString() + '万');
    updateTrendBadge($('#kpi-revenue-trend'), kpi.revenueTrend);
    
    $('#kpi-profit-margin').text(kpi.profitMargin + '%');
    updateTrendBadge($('#kpi-profit-trend'), kpi.profitTrend);
}

function updateTrendBadge($element, value) {
    const $badge = $element.closest('.badge');
    if (value == null) {
        $badge.addClass('d-none');
    } else {
        $badge.removeClass('d-none');
        $element.text(value);
        
        const $icon = $badge.find('i');
        $icon.removeClass('bi-arrow-up-right bi-arrow-down-right bi-dash');
        $badge.removeClass('bg-success bg-danger bg-secondary');
        
        if (value.startsWith('+')) {
            $badge.addClass('bg-success');
            $icon.addClass('bi-arrow-up-right');
        } else if (value.startsWith('-')) {
            $badge.addClass('bg-danger');
            $icon.addClass('bi-arrow-down-right');
        } else {
            $badge.addClass('bg-secondary');
            $icon.addClass('bi-dash');
        }
    }
}

function renderCharts(chartsData) {
    const theme = SES.theme.chartColors();

    if (revenueChartInstance) {
        revenueChartInstance.destroy();
    }
    if (statusChartInstance) {
        statusChartInstance.destroy();
    }

    // Revenue Chart
    const revCtx = document.getElementById('revenueChart').getContext('2d');
    revenueChartInstance = new Chart(revCtx, {
        type: 'bar',
        data: {
            labels: chartsData.revenue.labels,
            datasets: [
                {
                    label: SES.i18n.t('dashboard.chart.sales_label'),
                    data: chartsData.revenue.sales,
                    backgroundColor: 'rgba(59, 130, 246, 0.7)',
                    borderColor: '#3b82f6',
                    borderWidth: 1,
                    borderRadius: 4
                },
                {
                    label: SES.i18n.t('dashboard.chart.profit_label'),
                    data: chartsData.revenue.profit,
                    backgroundColor: 'rgba(32, 201, 151, 0.7)',
                    borderColor: '#20c997',
                    borderWidth: 1,
                    borderRadius: 4
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            color: theme.textColor,
            plugins: {
                legend: { 
                    position: 'top',
                    labels: { color: theme.textColor }
                },
                tooltip: {
                    callbacks: {
                        label: function(context) {
                            let label = context.dataset.label || '';
                            if (label) {
                                label += ': ';
                            }
                            if (context.parsed.y !== null) {
                                label += context.parsed.y;
                            }
                            const isActual = chartsData.revenue.isActual && chartsData.revenue.isActual[context.dataIndex];
                            label += isActual ? ' (' + SES.i18n.t('dashboard.chart.actual') + ')' : ' (' + SES.i18n.t('dashboard.chart.estimate') + ')';
                            return label;
                        }
                    }
                }
            },
            scales: {
                x: {
                    ticks: { color: theme.textColor },
                    grid: { color: theme.gridColor }
                },
                y: { 
                    beginAtZero: true,
                    ticks: { color: theme.textColor },
                    grid: { color: theme.gridColor }
                }
            }
        }
    });

    // Status Chart
    const statusCtx = document.getElementById('statusChart').getContext('2d');
    statusChartInstance = new Chart(statusCtx, {
        type: 'doughnut',
        data: {
            labels: chartsData.status.labels,
            datasets: [{
                data: chartsData.status.data,
                backgroundColor: [
                    '#20c997', // 稼動中 (Green)
                    '#dc3545', // Bench (Red)
                    '#ffc107', // 退場予定 (Yellow)
                    '#0dcaf0'  // 提案中 (Blue)
                ],
                borderWidth: 0,
                hoverOffset: 4
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            color: theme.textColor,
            plugins: {
                legend: { 
                    position: 'bottom',
                    labels: { color: theme.textColor }
                }
            },
            cutout: '70%'
        }
    });
}

function renderRetiringList(list) {
    const tbody = $('#retiring-table-body');
    tbody.empty();
    
    if (!list || list.length === 0) {
        tbody.append(`<tr><td colspan="5" class="text-center text-muted py-4"></td></tr>`);
        return;
    }
    
    list.forEach(item => {
        const propBadgeClass = item.proposals > 0 ? 'bg-primary' : 'bg-secondary';
        const propText = item.proposals > 0 ? SES.i18n.t('dashboard.list.proposing', item.proposals) : SES.i18n.t('dashboard.list.not_proposed');

        let daysColor = 'text-accent-yellow';
        if (item.daysLeft <= 14) daysColor = 'text-danger';
        else if (item.daysLeft > 30) daysColor = 'text-muted';

        // 氏名・スキル・案件名等は要員登録フォーム由来の任意文字列のため必ずエスケープする（XSS対策）
        const nameStr = SES.escapeHtml(item.name);

        const tr = `
            <tr>
                <td class="px-4 py-3">
                    <div class="d-flex align-items-center">
                        <div class="avatar bg-gradient-purple text-white rounded-circle d-flex justify-content-center align-items-center me-3" style="width: 32px; height: 32px; font-size: 0.8rem;">
                            ${SES.escapeHtml(item.initial || '?')}
                        </div>
                        <div>
                            <div class="fw-bold mb-0">${nameStr}</div>
                            <div class="small text-muted">${SES.escapeHtml(item.skill || SES.i18n.t('dashboard.list.skill_missing'))}</div>
                        </div>
                    </div>
                </td>
                <td class="py-3">${SES.escapeHtml(item.project || SES.i18n.t('dashboard.list.project_missing'))}</td>
                <td class="py-3">
                    <span class="${daysColor} fw-bold"><i class="bi bi-clock me-1"></i>${SES.escapeHtml(item.date)}</span>
                    <div class="small text-muted">${SES.i18n.t('dashboard.list.days_left', Number(item.daysLeft))}</div>
                </td>
                <td class="py-3">
                    <span class="badge ${propBadgeClass}"><i class="bi bi-file-earmark-person me-1"></i>${propText}</span>
                </td>
                <td class="px-4 py-3 text-end">
                    <button class="btn btn-sm btn-primary bg-gradient-blue border-0 rounded-pill px-3 shadow-sm btn-ai-match" data-id="${Number(item.id)}" data-name="${nameStr}">
                        <i class="bi bi-robot me-1"></i>${SES.i18n.t('dashboard.list.ai_match')}
                    </button>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

// 現在選択中の対象年度を反映して月次売上レポートをExcel出力する。
// バイナリレスポンスのため $.ajax ではなく window.location.href で直接ダウンロードさせる
// (common.js の ajaxSetup complete ハンドラが非JSONレスポンスをセッション切れと誤検知するのを避けるため)
function exportRevenue() {
    const fiscalYear = $('#fiscal-year-selector').val();
    window.location.href = '/api/dashboard/revenue-export?fiscalYear=' + encodeURIComponent(fiscalYear);
}

// Reuse the modal from Phase 3 if available, or redirect
function matchAI(engineerId, name) {
    if (typeof showAiMatchModal === 'function') {
        showAiMatchModal(engineerId, name);
    } else {
        window.location.href = `/engineer/detail?id=${engineerId}&action=ai-match`;
    }
}
