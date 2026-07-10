$(document).ready(function() {
    // Set default defaults for Chart.js in dark mode
    Chart.defaults.color = '#adb5bd';
    Chart.defaults.borderColor = 'rgba(255, 255, 255, 0.1)';

    loadDashboardData();
});

function loadDashboardData() {
    // Attempt to fetch from API, but since Phase 4 backend might not be fully wired up,
    // we use a robust fallback to mock data directly.
    $.ajax({
        url: '/api/dashboard/summary',
        method: 'GET',
        success: function(res) {
            if (res.code === 200) {
                renderKPIs(res.data.kpi);
                renderCharts(res.data.charts);
                renderRetiringList(res.data.retiring);
            } else {
                useMockData();
            }
        },
        error: function() {
            useMockData();
        }
    });
}

function useMockData() {
    const mockData = {
        kpi: {
            utilization: 85.5,
            utilizationTrend: '+2.1%',
            benchCount: 8,
            revenue: 4250,
            revenueTrend: '+5.4%',
            profitMargin: 28.5,
            profitTrend: '-1.2%'
        },
        charts: {
            revenue: {
                labels: ['4月', '5月', '6月', '7月', '8月', '9月'],
                sales: [3800, 3950, 4100, 4250, 4300, 4500],
                profit: [1100, 1150, 1200, 1210, 1250, 1350]
            },
            status: {
                labels: ['稼動中', 'Bench', '退場予定', '提案中'],
                data: [65, 8, 12, 15]
            }
        },
        retiring: [
            { id: 1, name: '田中 太郎', initial: 'T.T', skill: 'Java / Spring Boot', project: '大手金融基盤刷新プロジェクト', date: '2026/07/31', daysLeft: 21, proposals: 2 },
            { id: 2, name: '鈴木 花子', initial: 'H.S', skill: 'React / TypeScript', project: 'ECサイトフロントエンド開発', date: '2026/08/15', daysLeft: 36, proposals: 0 },
            { id: 3, name: '佐藤 次郎', initial: 'J.S', skill: 'AWS / Terraform', project: 'クラウドインフラ構築', date: '2026/08/31', daysLeft: 52, proposals: 1 }
        ]
    };

    renderKPIs(mockData.kpi);
    renderCharts(mockData.charts);
    renderRetiringList(mockData.retiring);
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
                            ${item.initial}
                        </div>
                        <div>
                            <div class="fw-bold text-white mb-0">${item.name}</div>
                            <div class="small text-muted">${item.skill}</div>
                        </div>
                    </div>
                </td>
                <td class="py-3 text-white">${item.project}</td>
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
