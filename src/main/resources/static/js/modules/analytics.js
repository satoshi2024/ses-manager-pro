let utilizationChartInstance = null;
let allBenchList = [];

$(document).ready(function() {
    loadUtilizationTrend();
    loadBenchList();

    // テーマ変更時にチャートを再配色する（発火元: SES.theme.applyTheme）
    document.addEventListener('ses:theme-changed', function() {
        SES.theme.applyChartTheme(utilizationChartInstance);
    });

    $('#bench-sales-filter').on('change', function() {
        const val = $(this).val();
        if (!val) {
            renderBenchTable(allBenchList);
        } else {
            const filtered = allBenchList.filter(item => item.primarySalesUserId == val);
            renderBenchTable(filtered);
        }
    });
});

function loadUtilizationTrend() {
    $.ajax({
        url: '/api/analytics/utilization-trend',
        method: 'GET',
        data: { months: 12 },
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderUtilizationChart(res.data);
            } else {
                Toast.error(res.message || SES.i18n.t('analytics.msg.chartFetchFail'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('common.msg.networkError'));
        }
    });
}

function loadBenchList() {
    $.ajax({
        url: '/api/analytics/bench',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                allBenchList = res.data;
                populateSalesFilter(allBenchList);
                renderBenchTable(allBenchList);
            } else {
                Toast.error(res.message || SES.i18n.t('analytics.msg.benchFetchFail'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('common.msg.networkError'));
        }
    });
}

function renderUtilizationChart(points) {
    if (utilizationChartInstance) {
        utilizationChartInstance.destroy();
    }

    const labels = points.map(p => p.label);
    const activeData = points.map(p => p.activeCount);
    const benchData = points.map(p => p.benchCount);
    const rateData = points.map(p => p.utilizationRate);

    const theme = SES.theme.chartColors();
    const ctx = document.getElementById('utilizationChart').getContext('2d');
    utilizationChartInstance = new Chart(ctx, {
        data: {
            labels: labels,
            datasets: [
                {
                    type: 'bar',
                    label: SES.i18n.t('analytics.chart.activeCount'),
                    data: activeData,
                    backgroundColor: 'rgba(32, 201, 151, 0.7)',
                    borderColor: '#20c997',
                    borderWidth: 1,
                    borderRadius: 4,
                    stack: 'headcount',
                    yAxisID: 'yCount'
                },
                {
                    type: 'bar',
                    label: SES.i18n.t('analytics.chart.benchCount'),
                    data: benchData,
                    backgroundColor: 'rgba(220, 53, 69, 0.7)',
                    borderColor: '#dc3545',
                    borderWidth: 1,
                    borderRadius: 4,
                    stack: 'headcount',
                    yAxisID: 'yCount'
                },
                {
                    type: 'line',
                    label: SES.i18n.t('analytics.chart.rate'),
                    data: rateData,
                    borderColor: '#0dcaf0',
                    backgroundColor: 'rgba(13, 202, 240, 0.2)',
                    borderWidth: 2,
                    tension: 0.3,
                    pointBackgroundColor: '#0dcaf0',
                    yAxisID: 'yRate'
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
                }
            },
            scales: {
                x: {
                    ticks: { color: theme.textColor },
                    grid: { color: theme.gridColor }
                },
                yCount: {
                    type: 'linear',
                    position: 'left',
                    stacked: true,
                    beginAtZero: true,
                    title: { display: true, text: SES.i18n.t('analytics.chart.yCount'), color: theme.textColor },
                    ticks: { color: theme.textColor },
                    grid: { color: theme.gridColor }
                },
                yRate: {
                    type: 'linear',
                    position: 'right',
                    beginAtZero: true,
                    max: 100,
                    grid: { drawOnChartArea: false, color: theme.gridColor },
                    title: { display: true, text: SES.i18n.t('analytics.chart.rate'), color: theme.textColor },
                    ticks: { color: theme.textColor }
                }
            }
        }
    });
}

function populateSalesFilter(list) {
    const filter = $('#bench-sales-filter');
    filter.find('option:not(:first)').remove();
    
    const uniqueSales = new Set();
    list.forEach(item => {
        if (item.primarySalesUserId && item.primarySalesUserName) {
            uniqueSales.add(JSON.stringify({id: item.primarySalesUserId, name: item.primarySalesUserName}));
        }
    });
    
    const sorted = Array.from(uniqueSales).map(json => JSON.parse(json))
        .sort((a,b) => a.name.localeCompare(b.name));
        
    sorted.forEach(s => {
        filter.append(`<option value="${escapeHtml(s.id)}">${escapeHtml(s.name)}</option>`);
    });
}

// ユーザー入力由来の値(要員名・スキル名など)をHTMLとして挿入する前に必ずエスケープする
// (XSS対策。氏名は要員登録フォームから任意の文字列が入りうるため必須)
function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function renderBenchTable(list) {
    const tbody = $('#bench-table-body');
    tbody.empty();

    if (!list || list.length === 0) {
        tbody.append(`<tr><td colspan="7" class="text-center text-muted py-4">${SES.i18n.t('analytics.bench.empty')}</td></tr>`);
        return;
    }

    list.forEach(item => {
        let daysColor = 'text-white';
        if (item.benchDays > 60) daysColor = 'text-danger';
        else if (item.benchDays > 30) daysColor = 'text-accent-yellow';

        const priceStr = item.expectedUnitPrice ? Number(item.expectedUnitPrice).toLocaleString() + SES.i18n.t('common.unit.yen') : '-';
        const availableStr = escapeHtml(item.availableDate || '-');
        const skillStr = (item.skillNames && item.skillNames.length > 0)
            ? escapeHtml(item.skillNames.join(', '))
            : '-';
        const nameStr = escapeHtml(item.fullName || '-');
        const salesStr = escapeHtml(item.primarySalesUserName || '—');
        const engineerId = encodeURIComponent(item.engineerId);

        const tr = `
            <tr>
                <td class="px-4 py-3">
                    <div class="d-flex align-items-center">
                        <div class="avatar bg-gradient-blue text-white rounded-circle d-flex justify-content-center align-items-center me-3" style="width: 32px; height: 32px; font-size: 0.8rem;">
                            ${escapeHtml(item.initial || '?')}
                        </div>
                        <div class="fw-bold">${nameStr}</div>
                    </div>
                </td>
                <td class="py-3">
                    <span class="${daysColor} fw-bold"><i class="bi bi-clock me-1"></i>${Number(item.benchDays)}${SES.i18n.t('common.unit.days')}</span>
                </td>
                <td class="py-3">${priceStr}</td>
                <td class="py-3">${availableStr}</td>
                <td class="py-3 text-muted small">${skillStr}</td>
                <td class="py-3 text-muted small">${salesStr}</td>
                <td class="px-4 py-3 text-end">
                    <div class="btn-group btn-group-sm" role="group">
                        <a href="/engineer/detail?id=${engineerId}" class="btn btn-outline-secondary border-secondary"><i class="bi bi-eye me-1"></i>${SES.i18n.t('common.btn.detail')}</a>
                        <a href="/ai/matching?engineerId=${engineerId}" class="btn btn-outline-info text-info border-info"><i class="bi bi-robot me-1"></i>${SES.i18n.t('analytics.btn.matching')}</a>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}
