let allTemplates = [];
let allContracts = [];

$(document).ready(function() {
    loadContracts();
    loadTemplates();
    
    // Check if contractId is in URL
    const urlParams = new URLSearchParams(window.location.search);
    const contractId = urlParams.get('contractId');
    if (contractId) {
        setTimeout(() => {
            $('#contractIdSelect').val(contractId);
            loadDocuments();
        }, 800);
    }
});

function loadContracts() {
    $.get('/api/contracts/options', function(res) {
        if (res.code === 200) {
            allContracts = res.data;
            let options = '<option value="">契約を選択...</option>';
            allContracts.forEach(c => {
                options += `<option value="${c.id}">${SES.escapeHtml(c.name)}</option>`;
            });
            $('#contractIdSelect').html(options);
            $('#newDocContractId').html(options);
        }
    });
}

function loadTemplates() {
    $.get('/api/contract-documents/templates', function(res) {
        if (res.code === 200) {
            allTemplates = res.data;
            let options = '<option value="">選択してください</option>';
            let trs = '';
            allTemplates.forEach(t => {
                options += `<option value="${t.id}">${SES.escapeHtml(t.name)} (Ver ${SES.escapeHtml(String(t.version))})</option>`;
                // XSS対策: サーバー側値を innerHTML へ挿入する際は escapeHtml 必須（A7-10）
                trs += `<tr>
                    <td>${t.id}</td>
                    <td>${SES.escapeHtml(t.name)}</td>
                    <td>${SES.escapeHtml(t.contractType)}</td>
                    <td>${SES.escapeHtml(String(t.version))}</td>
                </tr>`;
            });
            $('#newDocTemplateId').html(options);
            $('#template-table-body').html(trs);
        }
    });
}

function loadDocuments() {
    const contractId = $('#contractIdSelect').val();
    if (!contractId) {
        $('#document-table-body').html('<tr><td colspan="6" class="text-center text-muted py-4">契約を選択してください</td></tr>');
        return;
    }
    $.get(`/api/contract-documents/contract/${contractId}`, function(res) {
        if (res.code === 200) {
            if (res.data.length === 0) {
                $('#document-table-body').html('<tr><td colspan="6" class="text-center text-muted py-4">ドキュメントがありません</td></tr>');
                return;
            }
            let html = '';
            res.data.forEach(d => {
                const sentAt = d.sentAt ? d.sentAt.replace('T', ' ').substring(0,16) : '-';
                // XSS対策: 受信者名・メール・ステータスはサーバー値のため escapeHtml 必須（A7-10）
                html += `<tr>
                    <td class="px-4">${d.id}</td>
                    <td>${SES.escapeHtml(d.recipientName)}</td>
                    <td>${SES.escapeHtml(d.recipientEmail)}</td>
                    <td><span class="badge bg-secondary">${SES.escapeHtml(d.status)}</span></td>
                    <td>${sentAt}</td>
                    <td class="px-4 text-end">
                        <button class="btn btn-sm btn-outline-info me-1" onclick="syncDoc(${d.id})"><i class="bi bi-arrow-repeat"></i> 同期</button>
                        <button class="btn btn-sm btn-outline-success me-1" onclick="sendDoc(${d.id})"><i class="bi bi-send"></i> 送信</button>
                        <button class="btn btn-sm btn-outline-primary" onclick="downloadDoc(${d.id})"><i class="bi bi-download"></i> PDF</button>
                    </td>
                </tr>`;
            });
            $('#document-table-body').html(html);
        }
    });
}

function openCreateModal() {
    const cid = $('#contractIdSelect').val();
    if (cid) {
        $('#newDocContractId').val(cid);
    }
    $('#newDocTemplateId').val('');
    $('#newDocRecipientName').val('');
    $('#newDocRecipientEmail').val('');
    new bootstrap.Modal(document.getElementById('createDocModal')).show();
}

function createDocument() {
    const cid = $('#newDocContractId').val();
    const tid = $('#newDocTemplateId').val();
    const rname = $('#newDocRecipientName').val();
    const remail = $('#newDocRecipientEmail').val();
    
    if(!cid || !tid || !rname || !remail) {
        // A7-02: SES.toast はオブジェクト。SES.toast('warning', ...) の関数呼び出しは誤り
        SES.toast.warning('すべての項目を入力してください');
        return;
    }
    
    $.post(`/api/contract-documents?contractId=${cid}&templateId=${tid}&recipientName=${encodeURIComponent(rname)}&recipientEmail=${encodeURIComponent(remail)}`, function(res) {
        if (res.code === 200) {
            bootstrap.Modal.getInstance(document.getElementById('createDocModal')).hide();
            SES.toast.success('契約書を作成しました');
            $('#contractIdSelect').val(cid);
            loadDocuments();
        }
    });
}

function sendDoc(id) {
    Swal.fire({
        title: '送信確認',
        text: 'この契約書を相手に送信しますか？',
        icon: 'question',
        showCancelButton: true
    }).then(res => {
        if (res.isConfirmed) {
            $.post(`/api/contract-documents/${id}/send`, function(r) {
                if (r.code === 200) {
                    SES.toast.success('送信しました');
                    loadDocuments();
                }
            });
        }
    });
}

function syncDoc(id) {
    $.post(`/api/contract-documents/${id}/sync`, function(r) {
        if (r.code === 200) {
            SES.toast.success('同期しました');
            loadDocuments();
        }
    });
}

function downloadDoc(id) {
    window.open(`/api/contract-documents/${id}/download`, '_blank');
}

function openTemplateModal() {
    new bootstrap.Modal(document.getElementById('templatesModal')).show();
}

function showCreateTemplateForm() {
    $('#createTemplateFormArea').show();
}
function hideCreateTemplateForm() {
    $('#createTemplateFormArea').hide();
}

function createTemplate() {
    const data = {
        name: $('#newTplName').val(),
        contractType: $('#newTplType').val(),
        htmlContent: $('#newTplHtml').val()
    };
    
    if (!data.name || !data.contractType || !data.htmlContent) {
        SES.toast.warning('すべての項目を入力してください');
        return;
    }
    
    $.ajax({
        url: '/api/contract-documents/templates',
        type: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                SES.toast.success('テンプレートを作成しました');
                hideCreateTemplateForm();
                loadTemplates();
            }
        }
    });
}
