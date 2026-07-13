let utilizationChartInstance = null;

$(document).ready(function() {
    loadUtilizationTrend();
    loadBenchList();

    $('#theme-toggle-btn').on('click', function() {
        setTimeout(function() {
            if (utilizationChartInstance) {
                const theme = getChartTheme();
                utilizationChartInstance.options.color = theme.textColor;
                utilizationChartInstance.options.plugins.legend.labels.color = theme.textColor;
                utilizationChartInstance.options.scales.x.ticks.color = theme.textColor;
                utilizationChartInstance.options.scales.x.grid.color = theme.gridColor;
                utilizationChartInstance.options.scales.y.ticks.color = theme.textColor;
                utilizationChartInstance.options.scales.y.grid.color = theme.gridColor;
                utilizationChartInstance.update();
            }
        }, 50);
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
                Toast.error(res.message || '稼動率推移の取得に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}

function loadBenchList() {
    $.ajax({
        url: '/api/analytics/bench',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderBenchTable(res.data);
            } else {
                Toast.error(res.message || 'Bench一覧の取得に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}

function getChartTheme() {
    const isLight = document.documentElement.getAttribute('data-bs-theme') === 'light';
    return {
        textColor: isLight ? '#475569' : '#e8eaed',
        gridColor: isLight ? 'rgba(0, 0, 0, 0.1)' : 'rgba(255, 255, 255, 0.1)'
    };
}

function renderUtilizationChart(points) {
    if (utilizationChartInstance) {
        utilizationChartInstance.destroy();
    }

    const labels = points.map(p => p.label);
    const activeData = points.map(p => p.activeCount);
    const benchData = points.map(p => p.benchCount);
    const rateData = points.map(p => p.utilizationRate);

    const theme = getChartTheme();
    const ctx = document.getElementById('utilizationChart').getContext('2d');
    utilizationChartInstance = new Chart(ctx, {
        data: {
            labels: labels,
            datasets: [
                {
                    type: 'bar',
                    label: '稼動要員数',
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
                    label: 'Bench数',
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
                    label: '稼動率(%)',
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
                    title: { display: true, text: '要員数', color: theme.textColor },
                    ticks: { color: theme.textColor },
                    grid: { color: theme.gridColor }
                },
                yRate: {
                    type: 'linear',
                    position: 'right',
                    beginAtZero: true,
                    max: 100,
                    grid: { drawOnChartArea: false, color: theme.gridColor },
                    title: { display: true, text: '稼動率(%)', color: theme.textColor },
                    ticks: { color: theme.textColor }
                }
            }
        }
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
        tbody.append('<tr><td colspan="6" class="text-center text-muted py-4">Bench中の要員はいません</td></tr>');
        return;
    }

    list.forEach(item => {
        let daysColor = 'text-white';
        if (item.benchDays > 60) daysColor = 'text-danger';
        else if (item.benchDays > 30) daysColor = 'text-accent-yellow';

        const priceStr = item.expectedUnitPrice ? Number(item.expectedUnitPrice).toLocaleString() + '円' : '-';
        const availableStr = escapeHtml(item.availableDate || '-');
        const skillStr = (item.skillNames && item.skillNames.length > 0)
            ? escapeHtml(item.skillNames.join(', '))
            : '-';
        const nameStr = escapeHtml(item.fullName || '-');
        const engineerId = encodeURIComponent(item.engineerId);

        const tr = `
            <tr>
                <td class="px-4 py-3">
                    <div class="d-flex align-items-center">
                        <div class="avatar bg-gradient-blue text-white rounded-circle d-flex justify-content-center align-items-center me-3" style="width: 32px; height: 32px; font-size: 0.8rem;">
                            ${item.initial || '?'}
                        </div>
                        <div class="fw-bold">${nameStr}</div>
                    </div>
                </td>
                <td class="py-3">
                    <span class="${daysColor} fw-bold"><i class="bi bi-clock me-1"></i>${item.benchDays}日</span>
                </td>
                <td class="py-3">${priceStr}</td>
                <td class="py-3">${availableStr}</td>
                <td class="py-3 text-muted small">${skillStr}</td>
                <td class="px-4 py-3 text-end">
                    <div class="btn-group btn-group-sm" role="group">
                        <a href="/engineer/detail?id=${engineerId}" class="btn btn-outline-secondary border-secondary"><i class="bi bi-eye me-1"></i>詳細</a>
                        <a href="/ai/matching?engineerId=${engineerId}" class="btn btn-outline-info text-info border-info"><i class="bi bi-robot me-1"></i>マッチング</a>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}
