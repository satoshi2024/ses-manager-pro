$(document).ready(function() {
    loadProfitData();
});

function loadProfitData() {
    $.ajax({
        url: '/api/dashboard/profit-analysis',
        method: 'GET',
        success: function(res) {
            if (res.code === 200) {
                renderProfitTable(res.data);
            } else {
                Toast.error(res.message || '利益データの取得に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}

function renderProfitTable(data) {
    const tbody = $('#profit-table-body');
    tbody.empty();

    if (!data || data.length === 0) {
        tbody.append('<tr><td colspan="7" class="text-center text-muted py-4">契約データがありません</td></tr>');
        return;
    }

    let html = '';
    data.forEach(function(item) {
        html += '<tr>';
        html += '<td class="px-4 py-3">' + escapeHtml(item.contractNo) + '</td>';
        html += '<td class="py-3">' + escapeHtml(item.engineerName) + '</td>';
        html += '<td class="py-3">' + escapeHtml(item.projectName) + '</td>';
        html += '<td class="py-3 text-end">¥' + item.sellingPrice.toLocaleString() + '</td>';
        html += '<td class="py-3 text-end">¥' + item.costPrice.toLocaleString() + '</td>';
        html += '<td class="py-3 text-end">¥' + item.grossProfitAmount.toLocaleString() + '</td>';
        html += '<td class="px-4 py-3 text-end">' + escapeHtml(item.grossProfitRate) + '</td>';
        html += '</tr>';
    });

    tbody.append(html);
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str).replace(/[&<>"']/g, function(match) {
        const escapeMap = {
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;'
        };
        return escapeMap[match];
    });
}
