// Integration Hub inboundイベント/DLQ管理画面

$(document).ready(function() {
    loadInboundEvents(1);
});

function loadInboundEvents(page = 1) {
    const data = {
        current: page,
        size: 25,
        status: $('#statusFilter').val(),
        providerName: $('#providerFilter').val()
    };
    $.ajax({
        url: '/api/integration-hub/inbound-events',
        method: 'GET',
        data: data,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderInboundEvents(res.data.records || []);
                renderInboundPagination(res.data);
            } else {
                Toast.error('Inboundイベントの取得に失敗しました');
            }
        },
        error: function() { Toast.error('通信に失敗しました'); }
    });
}

function renderInboundEvents(records) {
    const tbody = $('#inbound-event-table-body').empty();
    if (!records.length) {
        tbody.append('<tr><td colspan="6" class="text-center text-muted py-4">データがありません</td></tr>');
        return;
    }
    records.forEach(function(event) {
        const dlq = event.status === 'DLQ';
        const replay = dlq
            ? `<button class="btn btn-sm btn-outline-warning" onclick="replayInboundEvent(${Number(event.id)})">Replay</button>`
            : '<span class="text-muted small">-</span>';
        const statusClass = dlq || event.status === 'CONFLICT' ? 'text-danger' : 'text-info';
        tbody.append(`<tr>
            <td class="ps-4 text-muted small">${SES.escapeHtml(event.receivedAt || '-')}</td>
            <td class="small">${SES.escapeHtml(event.clientId || '-')}</td>
            <td><div>${SES.escapeHtml(event.providerName || '-')}</div><div class="text-muted small font-monospace">${SES.escapeHtml(event.providerEventId || '-')}</div></td>
            <td class="${statusClass} fw-bold">${SES.escapeHtml(event.status || '-')}</td>
            <td class="small">${SES.escapeHtml(event.resultCode || '-')}</td>
            <td>${replay}</td>
        </tr>`);
    });
}

function replayInboundEvent(id) {
    Swal.fire({
        title: 'DLQをReplayしますか？',
        text: '元イベントは変更されず、現在のscopeを再検証します。',
        input: 'text',
        inputLabel: '理由コード（例: INCIDENT_RECOVERY）',
        inputPlaceholder: 'INCIDENT_RECOVERY',
        inputAttributes: { maxlength: 64, autocapitalize: 'characters' },
        showCancelButton: true,
        confirmButtonText: 'Replay',
        cancelButtonText: 'キャンセル',
        inputValidator: value => /^[A-Z][A-Z0-9_]{0,63}$/.test(value || '') ? undefined : '大文字の理由コードを入力してください'
    }).then(function(result) {
        if (!result.isConfirmed) return;
        $.ajax({
            url: '/api/integration-hub/inbound-events/' + encodeURIComponent(id) + '/replay',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({reasonCode: result.value}),
            success: function(res) {
                if (res.code === 200) {
                    Toast.success('Replayを記録しました');
                    loadInboundEvents(1);
                } else { Toast.error('Replayに失敗しました'); }
            },
            error: function() { Toast.error('Replayに失敗しました'); }
        });
    });
}

function renderInboundPagination(pageData) {
    const container = $('#inbound-event-pagination').empty();
    const total = Number(pageData.total || 0);
    const current = Number(pageData.current || 1);
    const size = Number(pageData.size || 25);
    const pages = Math.ceil(total / size);
    container.append(`<span class="text-muted small">全 ${total} 件 / ${current}ページ</span>`);
    if (pages > 1) {
        const nav = $('<nav aria-label="Inbound event pages"><ul class="pagination pagination-sm mb-0"></ul></nav>');
        const ul = nav.find('ul');
        for (let i = 1; i <= pages; i++) {
            if (i > 3 && i < pages - 2 && Math.abs(i - current) > 1) continue;
            const link = $(`<li class="page-item ${i === current ? 'active' : ''}"><a class="page-link bg-dark border-secondary text-light" href="#">${i}</a></li>`);
            link.on('click', function(e) { e.preventDefault(); loadInboundEvents(i); });
            ul.append(link);
        }
        container.append(nav);
    }
}
