let revenueChartInstance = null;
let statusChartInstance = null;
let cashflowChartInstance = null;
let utilizationForecastChartInstance = null;
let cashflowLoaded = false;

$(document).ready(function() {
    $('#btn-print-report').on('click', function() { window.print(); });
    $('#btn-export-revenue').on('click', function() { exportRevenue(); });

    // テーマ変更時にチャートを再配色する（発火元: SES.theme.applyTheme）
    document.addEventListener('ses:theme-changed', function() {
        SES.theme.applyChartTheme(revenueChartInstance);
        SES.theme.applyChartTheme(statusChartInstance);
        SES.theme.applyChartTheme(cashflowChartInstance);
        SES.theme.applyChartTheme(utilizationForecastChartInstance);
    });

    // 退場予定者リストのAIマッチングボタン（行はAjaxで再描画されるため委譲で捕捉）
    $('#retiring-table-body').on('click', '.btn-ai-match', function() {
        matchAI(Number($(this).data('id')), String($(this).data('name')));
    });

    const currentFiscalYear = getCurrentFiscalYear();
    populateFiscalYearSelector(currentFiscalYear);
    $('#fiscal-year-selector').val(currentFiscalYear);

    loadDashboardData(currentFiscalYear);
    loadUtilizationForecast(3);

    $('#fiscal-year-selector').on('change', function() {
        loadDashboardData($(this).val());
    });

    $('#forecast-months-selector').on('change', function() {
        loadUtilizationForecast(Number($(this).val()));
    });

    $('button[data-bs-toggle="tab"]').on('shown.bs.tab', function (e) {
        if (e.target.id === 'cashflow-tab') {
            $('#btn-export-cashflow').removeClass('d-none');
            $('#fiscal-year-selector').addClass('d-none');
            if (!cashflowLoaded) {
                loadCashflowData();
            }
        } else {
            $('#btn-export-cashflow').addClass('d-none');
            $('#fiscal-year-selector').removeClass('d-none');
        }
    });

    $('#btn-export-cashflow').on('click', function() {
        // Just triggers CSV download via API (add endpoint if needed, or JS export)
        exportCashflowCsv();
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
        selector.append(`<option value="${y}">${SES.i18n.t('dashboard.fiscalYear', [y])}</option>`);
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

function loadCashflowData() {
    $.ajax({
        url: '/api/cashflow/forecast',
        method: 'GET',
        data: { months: 6 },
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderCashflowChart(res.data.months, res.data.alertThreshold);
                renderCashflowReconcileNote(res.data.reconciliation);
                cashflowLoaded = true;
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
    
    $('#kpi-revenue').text('¥' + kpi.revenue.toLocaleString());
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
            ].concat(chartsData.revenue.forecast ? [{
                    // 売上着地予測（パイプライン加重）: 確定ベースに提案パイプラインを重ねた参考系列
                    type: 'line',
                    label: SES.i18n.t('dashboard.chart.forecast_label', '着地予測'),
                    data: chartsData.revenue.forecast,
                    borderColor: 'rgba(59, 130, 246, 0.6)',
                    borderDash: [6, 4],
                    borderWidth: 2,
                    pointRadius: 2,
                    fill: false
                }] : [])
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
                                label += '¥' + Number(context.parsed.y).toLocaleString();
                            }
                            // 予測系列は内訳（確定ベース + パイプライン加重）を表示する
                            if (chartsData.revenue.forecast && context.dataset.type === 'line') {
                                const base = chartsData.revenue.sales[context.dataIndex] || 0;
                                const pipeline = (context.parsed.y || 0) - base;
                                return label + ' (確定 ¥' + base.toLocaleString()
                                    + ' + パイプライン ¥' + pipeline.toLocaleString()
                                    + '／' + (chartsData.revenue.forecastPipelineCount || 0) + '件加重)';
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

    const forecastNote = document.getElementById('forecastNote');
    if (forecastNote) {
        forecastNote.textContent = chartsData.revenue.forecast
            ? SES.i18n.t('dashboard.forecast.note',
                '※着地予測は確定契約に加え、オープン提案の提示単価×ステージ確率を翌月以降に加重した参考値です。')
            : '';
    }

    // Status Chart
    const statusCtx = document.getElementById('statusChart').getContext('2d');
    statusChartInstance = new Chart(statusCtx, {
        type: 'doughnut',
        data: {
            labels: chartsData.status.labels.map(l => SES.i18n.e('engineerStatus', l)),
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

// 起点月の売上口径を全社KPIと突合した結果を1行で表示する。
// 差分が出るのは実績未確定・未請求の売上があるときで、その分は入金予定に現れていない。
function renderCashflowReconcileNote(rec) {
    const el = document.getElementById('cashflowReconcileNote');
    if (!el) return;
    if (!rec) {
        el.textContent = '';
        return;
    }
    const diff = Number(rec.difference);
    el.textContent = SES.i18n.t('dashboard.cashflow.reconcile', [
        rec.month,
        Number(rec.kpiSales).toLocaleString(),
        Number(rec.invoicedSubtotal).toLocaleString(),
        diff.toLocaleString()
    ]);
    el.classList.toggle('text-warning', diff !== 0);
    el.classList.toggle('text-muted', diff === 0);
}

function renderCashflowChart(monthsData, alertThreshold) {
    const theme = SES.theme.chartColors();
    if (cashflowChartInstance) {
        cashflowChartInstance.destroy();
    }

    const labels = monthsData.map(m => m.month);
    const inflowData = monthsData.map(m => m.inflow);
    const outflowData = monthsData.map(m => m.outflow);
    const balanceData = monthsData.map(m => m.balance);

    const threshold = (alertThreshold !== undefined && alertThreshold !== null) ? alertThreshold : 0;
    // Alert colors for balance line points if it drops below the threshold
    const pointColors = balanceData.map(val => val < threshold ? '#dc3545' : '#17a2b8');

    const ctx = document.getElementById('cashflowChart').getContext('2d');
    
    const balanceLabel = SES.i18n.t('dashboard.cashflow.balance', '残高見込み');
    const inflowLabel = SES.i18n.t('dashboard.cashflow.inflow', '入金予定');
    const outflowLabel = SES.i18n.t('dashboard.cashflow.outflow', '支払予定');
    
    cashflowChartInstance = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [
                {
                    type: 'line',
                    label: balanceLabel,
                    data: balanceData,
                    borderColor: '#17a2b8',
                    backgroundColor: '#17a2b8',
                    pointBackgroundColor: pointColors,
                    borderWidth: 2,
                    pointRadius: 4,
                    fill: false,
                    yAxisID: 'y'
                },
                {
                    type: 'bar',
                    label: inflowLabel,
                    data: inflowData,
                    backgroundColor: 'rgba(32, 201, 151, 0.7)',
                    borderColor: '#20c997',
                    borderWidth: 1,
                    yAxisID: 'y'
                },
                {
                    type: 'bar',
                    label: outflowLabel,
                    data: outflowData,
                    backgroundColor: 'rgba(220, 53, 69, 0.7)',
                    borderColor: '#dc3545',
                    borderWidth: 1,
                    yAxisID: 'y'
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
                            if (label) label += ': ';
                            if (context.parsed.y !== null) {
                                label += '¥' + Number(context.parsed.y).toLocaleString();
                            }
                            
                            // Add breakdown if available
                            if (context.dataset.type === 'bar' && context.dataset.label === outflowLabel) {
                                const dataObj = monthsData[context.dataIndex];
                                const payrollLbl = SES.i18n.t('dashboard.cashflow.payroll', '給与');
                                const fixedLbl = SES.i18n.t('dashboard.cashflow.fixed', '固定');
                                label += ` (BP: ¥${Number(dataObj.bpPaymentTotal).toLocaleString()}, ${payrollLbl}: ¥${Number(dataObj.payrollTotal).toLocaleString()}, ${fixedLbl}: ¥${Number(dataObj.fixedCost).toLocaleString()})`;
                            }
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

function exportCashflowCsv() {
    window.location.href = '/api/cashflow/export?months=6';
}

// Reuse the modal from Phase 3 if available, or redirect
function matchAI(engineerId, name) {
    if (typeof showAiMatchModal === 'function') {
        showAiMatchModal(engineerId, name);
    } else {
        window.location.href = `/engineer/detail?id=${engineerId}&action=ai-match`;
    }
}

function loadUtilizationForecast(months) {
    $.ajax({
        url: '/api/dashboard/utilization-forecast',
        method: 'GET',
        data: { months: months || 3 },
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderUtilizationForecastChart(res.data.monthlyForecasts);
                renderRolloffList(res.data.rolloffEngineers);
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

function renderUtilizationForecastChart(monthlyData) {
    const theme = SES.theme.chartColors();
    if (utilizationForecastChartInstance) {
        utilizationForecastChartInstance.destroy();
    }
    const canvas = document.getElementById('utilizationForecastChart');
    if (!canvas) return;

    const labels = monthlyData.map(m => m.month);
    const rates = monthlyData.map(m => m.utilizationRate);
    const workingCounts = monthlyData.map(m => m.workingCount);
    const benchCounts = monthlyData.map(m => m.benchCount);

    const personUnit = SES.i18n.t('dashboard.kpi.person_unit', '名');

    utilizationForecastChartInstance = new Chart(canvas.getContext('2d'), {
        type: 'bar',
        data: {
            labels: labels,
            datasets: [
                {
                    type: 'line',
                    label: SES.i18n.t('dashboard.utilizationForecast.rateLabel', '稼働率見込み (%)'),
                    data: rates,
                    borderColor: '#3b82f6',
                    backgroundColor: '#3b82f6',
                    borderWidth: 2,
                    pointRadius: 4,
                    fill: false,
                    yAxisID: 'yRate'
                },
                {
                    type: 'bar',
                    label: SES.i18n.t('dashboard.utilizationForecast.workingLabel', '稼働見込み (名)'),
                    data: workingCounts,
                    backgroundColor: 'rgba(32, 201, 151, 0.7)',
                    borderColor: '#20c997',
                    borderWidth: 1,
                    yAxisID: 'yCount'
                },
                {
                    type: 'bar',
                    label: SES.i18n.t('dashboard.utilizationForecast.benchLabel', 'Bench見込み (名)'),
                    data: benchCounts,
                    backgroundColor: 'rgba(220, 53, 69, 0.7)',
                    borderColor: '#dc3545',
                    borderWidth: 1,
                    yAxisID: 'yCount'
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
                            if (label) label += ': ';
                            if (context.parsed.y !== null) {
                                label += context.dataset.yAxisID === 'yRate' ? context.parsed.y + '%' : context.parsed.y + personUnit;
                            }
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
                yRate: {
                    type: 'linear',
                    position: 'left',
                    min: 0,
                    max: 100,
                    ticks: {
                        color: theme.textColor,
                        callback: function(value) { return value + '%'; }
                    },
                    grid: { color: theme.gridColor }
                },
                yCount: {
                    type: 'linear',
                    position: 'right',
                    beginAtZero: true,
                    ticks: {
                        color: theme.textColor,
                        precision: 0,
                        callback: function(value) { return value + personUnit; }
                    },
                    grid: { drawOnChartArea: false }
                }
            }
        }
    });
}

function renderRolloffList(list) {
    const tbody = $('#rolloff-table-body');
    tbody.empty();

    if (!list || list.length === 0) {
        tbody.append(`<tr><td colspan="4" class="text-center text-muted py-4">${SES.i18n.t('dashboard.utilizationForecast.noRolloff', 'ロールオフ予定の要員はいません')}</td></tr>`);
        return;
    }

    list.forEach(item => {
        const engineerName = SES.escapeHtml(item.engineerName || '');
        const initial = SES.escapeHtml(item.initialName || '?');
        const projectName = SES.escapeHtml(item.projectName || '-');
        const salesUserName = SES.escapeHtml(item.salesUserName || '-');
        const endDate = SES.escapeHtml(item.endDate || '-');

        const tr = `
            <tr>
                <td class="px-3 py-2">
                    <div class="d-flex align-items-center">
                        <div class="avatar bg-gradient-purple text-white rounded-circle d-flex justify-content-center align-items-center me-2" style="width: 28px; height: 28px; font-size: 0.75rem;">
                            ${initial}
                        </div>
                        <div>
                            <a href="/engineer/detail?id=${Number(item.engineerId)}" class="fw-bold text-white text-decoration-none hover-text-blue small">${engineerName}</a>
                        </div>
                    </div>
                </td>
                <td class="py-2">
                    <div class="small fw-bold text-white">${projectName}</div>
                    <div class="small text-accent-yellow"><i class="bi bi-clock me-1"></i>${endDate}</div>
                </td>
                <td class="py-2">
                    <span class="small text-muted">${salesUserName}</span>
                </td>
                <td class="px-3 py-2 text-end">
                    <a href="/contract/renewal-calendar" class="btn btn-sm btn-outline-info border-0 rounded-pill px-2 py-0" title="${SES.i18n.t('dashboard.utilizationForecast.renewalCalendar', '更新カレンダー')}">
                        <i class="bi bi-calendar-check me-1"></i><span class="small">${SES.i18n.t('contract.renewalCalendar.btn', '更新')}</span>
                    </a>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}
