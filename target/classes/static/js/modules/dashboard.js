$(document).ready(function() {
    // Set default defaults for Chart.js in dark mode
    Chart.defaults.color = '#adb5bd';
    Chart.defaults.borderColor = 'rgba(255, 255, 255, 0.1)';

    loadDashboardData();
});

function loadDashboardData() {
    $.ajax({
        url: '/api/dashboard/summary',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderKPIs(res.data.kpi);
                renderCharts(res.data.charts);
                renderRetiringList(res.data.retiring);
            } else {
                Toast.error(res.message || 'ダッシュボードデータの取得に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}



function renderKPIs(kpi) {
    $('#kpi-utilization').text(kpi.utilization + '%');
    $('#kpi-utilization-trend').text(kpi.utilizationTrend);
    
    $('#kpi-bench-count').text(kpi.benchCount);
    
    $('#kpi-revenue').text('¥' + kpi.revenue.toLocaleString() + '万');
    $('#kpi-revenue-trend').text(kpi.revenueTrend);
    
    $('#kpi-profit-margin').text(kpi.profitMargin + '%');
    $('#kpi-profit-trend').text(kpi.profitTrend);
}

function renderCharts(chartsData) {
    // Revenue Chart
    const revCtx = document.getElementById('revenueChart').getContext('2d');
    new Chart(revCtx, {
        type: 'bar',
        data: {
            labels: chartsData.revenue.labels,
            datasets: [
                {
                    label: '売上 (万円)',
                    data: chartsData.revenue.sales,
                    backgroundColor: 'rgba(13, 202, 240, 0.7)',
                    borderColor: '#0dcaf0',
                    borderWidth: 1,
                    borderRadius: 4
                },
                {
                    label: '粗利 (万円)',
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
            plugins: {
                legend: { position: 'top' }
            },
            scales: {
                y: { beginAtZero: true }
            }
        }
    });

    // Status Chart
    const statusCtx = document.getElementById('statusChart').getContext('2d');
    new Chart(statusCtx, {
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
            cutout: '70%',
            plugins: {
                legend: { position: 'bottom' }
            }
        }
    });
}

function renderRetiringList(list) {
    const tbody = $('#retiring-table-body');
    tbody.empty();
    
    if (!list || list.length === 0) {
        tbody.append('<tr><td colspan="5" class="text-center text-muted py-4">直近の退場予定者はいません</td></tr>');
        return;
    }
    
    list.forEach(item => {
        const propBadgeClass = item.proposals > 0 ? 'bg-primary' : 'bg-secondary';
        const propText = item.proposals > 0 ? `提案中 (${item.proposals}件)` : '未提案';
        
        let daysColor = 'text-accent-yellow';
        if (item.daysLeft <= 14) daysColor = 'text-danger';
        else if (item.daysLeft > 30) daysColor = 'text-muted';

        const tr = `
            <tr>
                <td class="px-4 py-3">
                    <div class="d-flex align-items-center">
                        <div class="avatar bg-gradient-purple text-white rounded-circle d-flex justify-content-center align-items-center me-3" style="width: 32px; height: 32px; font-size: 0.8rem;">
                            ${item.initial || '?'}
                        </div>
                        <div>
                            <div class="fw-bold text-white mb-0">${item.name}</div>
                            <div class="small text-muted">${item.skill || 'スキル情報なし'}</div>
                        </div>
                    </div>
                </td>
                <td class="py-3 text-white">${item.project || '-'}</td>
                <td class="py-3">
                    <span class="${daysColor} fw-bold"><i class="bi bi-clock me-1"></i>${item.date}</span>
                    <div class="small text-muted">残り ${item.daysLeft} 日</div>
                </td>
                <td class="py-3">
                    <span class="badge ${propBadgeClass}"><i class="bi bi-file-earmark-person me-1"></i>${propText}</span>
                </td>
                <td class="px-4 py-3 text-end">
                    <button class="btn btn-sm btn-primary bg-gradient-blue border-0 rounded-pill px-3 shadow-sm" onclick="matchAI(${item.id}, '${item.name}')">
                        <i class="bi bi-robot me-1"></i>AI案件探索
                    </button>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

// Reuse the modal from Phase 3 if available, or redirect
function matchAI(engineerId, name) {
    if (typeof showAiMatchModal === 'function') {
        showAiMatchModal(engineerId, name);
    } else {
        window.location.href = `/engineer/detail?id=${engineerId}&action=ai-match`;
    }
}
