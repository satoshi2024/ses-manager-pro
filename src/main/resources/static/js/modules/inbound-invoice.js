document.addEventListener('DOMContentLoaded', function() {
    loadInboundInvoices(1);
});

function loadInboundInvoices(page = 1) {
    SES.api.get(`/api/inbound-invoices?current=${page}&size=10`).then(pageData => {
        if (pageData) {
            const tbody = document.querySelector('#inboundInvoiceTable tbody');
            tbody.innerHTML = '';
            
            if (SES.pagination) {
                SES.pagination.render('pagination', pageData.current, pageData.pages, p => loadInboundInvoices(p));
            }
            
            pageData.records.forEach(inv => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${SES.escapeHtml(inv.messageId)}</td>
                    <td>${SES.escapeHtml(inv.providerMessageId || '-')}</td>
                    <td>${inv.receivedAt || '-'}</td>
                    <td><span class="badge bg-${getStatusColor(inv.status)}">${SES.escapeHtml(inv.status)}</span></td>
                    <td><div class="d-flex flex-wrap justify-content-end align-items-center gap-1">${inv.status === 'PENDING_REVIEW' ? `
                            <button type="button" class="btn btn-sm btn-success" onclick="reviewInvoice(${inv.id}, 'ACCEPT')" title="インボイスを承認" aria-label="インボイスを承認">承認</button>
                            <button type="button" class="btn btn-sm btn-danger" onclick="reviewInvoice(${inv.id}, 'REJECT')" title="インボイスを差し戻す" aria-label="インボイスを差し戻す">差戻し</button>
                        ` : ''}
                    </div></td>
                `;
                tbody.appendChild(tr);
            });
        }
    }).catch(error => {
        console.error(error);
        console.error(error.message || '受信請求書の取得に失敗しました');
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
            SES.api.post(`/api/inbound-invoices/${encodeURIComponent(id)}/review?action=${encodeURIComponent(action)}`).then(() => {
                SES.toast.success(`${actionText}しました`);
                return loadInboundInvoices(1);
            }).catch(error => {
                console.error(error);
                console.error(error.message || `${actionText}に失敗しました`);
            });
        }
    });
}
