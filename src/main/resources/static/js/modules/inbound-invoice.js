document.addEventListener('DOMContentLoaded', function() {
    loadInboundInvoices(1);
});

function loadInboundInvoices(page = 1) {
    SES.api.get(`/api/inbound-invoices?current=${page}&size=10`).then(res => {
        if (res.code === 200) {
            const tbody = document.querySelector('#inboundInvoiceTable tbody');
            tbody.innerHTML = '';
            
            if (SES.pagination) {
                SES.pagination.render('pagination', res.data.current, res.data.pages, p => loadInboundInvoices(p));
            }
            
            res.data.records.forEach(inv => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${SES.escapeHtml(inv.messageId)}</td>
                    <td>${SES.escapeHtml(inv.providerMessageId || '-')}</td>
                    <td>${inv.receivedAt || '-'}</td>
                    <td><span class="badge bg-${getStatusColor(inv.status)}">${SES.escapeHtml(inv.status)}</span></td>
                    <td>
                        ${inv.status === 'PENDING_REVIEW' ? `
                            <button class="btn btn-sm btn-success" onclick="reviewInvoice(${inv.id}, 'ACCEPT')">承認</button>
                            <button class="btn btn-sm btn-danger" onclick="reviewInvoice(${inv.id}, 'REJECT')">差戻し</button>
                        ` : ''}
                    </td>
                `;
                tbody.appendChild(tr);
            });
        }
    });
}

function getStatusColor(status) {
    switch(status) {
        case 'PENDING_REVIEW': return 'warning';
        case 'ACCEPTED': return 'success';
        case 'REJECTED_AUTO':
        case 'REJECTED_MANUAL': return 'danger';
        default: return 'secondary';
    }
}

function reviewInvoice(id, action) {
    const actionText = action === 'ACCEPT' ? '承認' : '差戻し';
    Swal.fire({
        title: '確認',
        text: `このインボイスを${actionText}しますか？`,
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: actionText + 'する',
        cancelButtonText: 'キャンセル',
        ... (SES.swal && typeof SES.swal.themeConfig === 'function' ? SES.swal.themeConfig() : {})
    }).then(result => {
        if (result.isConfirmed) {
            SES.api.post(`/api/inbound-invoices/${id}/review?action=${action}`).then(res => {
                if (res.code === 200) {
                    SES.toast.success(`${actionText}しました`);
                    loadInboundInvoices(1);
                } else {
                    Swal.fire({ icon: 'error', title: 'エラー', text: res.message, ...(SES.swal && typeof SES.swal.themeConfig === 'function' ? SES.swal.themeConfig() : {}) });
                }
            });
        }
    });
}
