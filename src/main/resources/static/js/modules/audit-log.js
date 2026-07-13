// 監査ログ画面

$(document).ready(function() {
    loadAuditLogs(1);
});

function loadAuditLogs(page = 1) {
    const data = {
        current: page,
        size: 10,
        username: $('#searchUsername').val(),
        method: $('#searchMethod').val()
    };

    $.ajax({
        url: '/api/audit-logs',
        method: 'GET',
        data: data,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderAuditLogs(res.data.records || res.data);
                if (res.data.total !== undefined) {
                    renderPagination(res.data);
                }
            } else {
                Toast.error('データの取得に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}

function renderAuditLogs(records) {
    const tbody = $('#audit-log-table-body');
    tbody.empty();

    if (!records || records.length === 0) {
        tbody.append('<tr><td colspan="5" class="text-center text-muted py-4">データがありません</td></tr>');
        return;
    }

    records.forEach(log => {
        const statusClass = log.status >= 200 && log.status < 300 ? 'text-accent-green' : 'text-danger';
        const tr = `
            <tr>
                <td class="ps-4 py-2 text-muted small">${SES.escapeHtml(log.createdAt || '-')}</td>
                <td class="py-2">${SES.escapeHtml(log.username || '-')}</td>
                <td class="py-2"><span class="badge bg-secondary">${SES.escapeHtml(log.method)}</span></td>
                <td class="py-2 font-monospace small">${SES.escapeHtml(log.uri)}</td>
                <td class="py-2 ${statusClass} fw-bold">${log.status}</td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function renderPagination(pageData) {
    const container = $('.card-footer');
    if (pageData.total === 0) {
        container.html('<div class="text-muted small ps-2">全 0 件</div>');
        return;
    }

    const start = (pageData.current - 1) * pageData.size + 1;
    const end = Math.min(pageData.current * pageData.size, pageData.total);

    let html = `<div class="text-muted small ps-2">全 ${pageData.total} 件中 ${start}-${end} 件を表示</div>
        <nav aria-label="Page navigation"><ul class="pagination pagination-sm mb-0 pe-2">`;

    if (pageData.current > 1) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="loadAuditLogs(${pageData.current - 1})"><i class="bi bi-chevron-left"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-muted" href="javascript:void(0)"><i class="bi bi-chevron-left"></i></a></li>`;
    }
    for (let i = 1; i <= pageData.pages; i++) {
        if (i === pageData.current) {
            html += `<li class="page-item active"><a class="page-link bg-info border-info text-dark fw-bold" href="javascript:void(0)">${i}</a></li>`;
        } else if (i <= 3 || i >= pageData.pages - 2 || Math.abs(i - pageData.current) <= 1) {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="loadAuditLogs(${i})">${i}</a></li>`;
        }
    }
    if (pageData.current < pageData.pages) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="loadAuditLogs(${pageData.current + 1})"><i class="bi bi-chevron-right"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)"><i class="bi bi-chevron-right"></i></a></li>`;
    }
    html += `</ul></nav>`;
    container.html(html);
}
