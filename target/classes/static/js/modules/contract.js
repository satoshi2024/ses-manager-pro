$(document).ready(function() {
    loadContracts();
});

function loadContracts() {
    const keyword = $('input[name="keyword"]').val();
    const status = $('select[name="status"]').val();
    const customerName = $('input[name="customerName"]').val();
    
    // In a real app, pass these as query params
    
    $('#contract-table-body').html('<tr><td colspan="7" class="text-center text-muted py-4"><div class="spinner-border spinner-border-sm me-2"></div>読み込み中...</td></tr>');
    
    $.ajax({
        url: '/api/contracts',
        method: 'GET',
        success: function(res) {
            if (res.code === 200) {
                renderContracts(res.data);
            } else {
                Toast.error(res.message);
                renderContracts(getMockData()); // Fallback to mock
            }
        },
        error: function(err) {
            console.error(err);
            renderContracts(getMockData());
        }
    });
}

function renderContracts(list) {
    const tbody = $('#contract-table-body');
    tbody.empty();
    
    if (!list || list.length === 0) {
        tbody.append('<tr><td colspan="7" class="text-center text-muted py-4">データが見つかりません</td></tr>');
        return;
    }
    
    list.forEach(c => {
        const tr = `
            <tr>
                <td class="px-4 py-3"><span class="font-monospace text-muted">${c.contractNo || 'C-' + c.id}</span></td>
                <td class="py-3">
                    <div class="fw-bold text-white">${c.engineerName || 'エンジニア名'}</div>
                    <div class="small text-muted">${c.contractType || '準委任'}</div>
                </td>
                <td class="py-3">
                    <div class="text-white">${c.customerName || '顧客名'}</div>
                    <div class="small text-muted text-truncate" style="max-width:200px;">${c.projectName || '案件名'}</div>
                </td>
                <td class="py-3 text-white small">
                    <div><i class="bi bi-play-circle text-success me-1"></i>${c.startDate || '2026/04/01'}</div>
                    <div><i class="bi bi-stop-circle text-danger me-1"></i>${c.endDate || '2026/09/30'}</div>
                </td>
                <td class="py-3">
                    <div class="text-accent-green fw-bold">¥${c.sellingPrice ? c.sellingPrice.toLocaleString() : '---'}万</div>
                    <div class="small text-muted">¥${c.costPrice ? c.costPrice.toLocaleString() : '---'}万</div>
                </td>
                <td class="py-3">
                    ${getStatusBadge(c.status)}
                </td>
                <td class="px-4 py-3 text-end">
                    <button class="btn btn-sm btn-outline-secondary text-muted hover-text-white border-dark" onclick="window.location.href='/contract/form?id=${c.id}'">詳細/編集</button>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function getStatusBadge(status) {
    let bg = 'bg-secondary';
    if(status === '稼動中') bg = 'bg-success';
    if(status === '準備中') bg = 'bg-primary';
    if(status === '終了') bg = 'bg-secondary';
    if(status === '解約') bg = 'bg-danger';
    return `<span class="badge ${bg}">${status || '準備中'}</span>`;
}

function getMockData() {
    return [
        { id: 1, contractNo: 'CNT-2026-001', engineerName: '田中 太郎', contractType: '準委任', customerName: 'メガバンク', projectName: '勘定系システム移行', startDate: '2026-04-01', endDate: '2026-09-30', sellingPrice: 85, costPrice: 65, status: '稼動中' },
        { id: 2, contractNo: 'CNT-2026-002', engineerName: '鈴木 花子', contractType: '派遣', customerName: 'ECソリューション', projectName: 'バックオフィス開発', startDate: '2026-06-01', endDate: '2026-11-30', sellingPrice: 70, costPrice: 50, status: '準備中' }
    ];
}
