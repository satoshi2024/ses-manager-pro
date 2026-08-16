let allTemplates = [];
let allContracts = [];
let sendTarget = null;

const roleFlags = $('#roleFlags');
const CAN_SEND = roleFlags.data('canSend') === true;
const CAN_SYNC = roleFlags.data('canSync') === true;

$(document).ready(function() {
    loadContracts();
    loadTemplates();

    // 結果不明runbookの表示制御
    $('#sendConfirmCheck').on('change', function() {
        $('#sendConfirmButton').prop('disabled', !$(this).prop('checked'));
    });

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
                html += renderRow(d);
            });
            $('#document-table-body').html(html);

            // 結果不明行があればrunbookを表示
            const hasUnknown = res.data.some(d => d.dispatchState === 'RECONCILIATION_REQUIRED');
            $('#reconciliationRunbook').toggle(hasUnknown);
        }
    });
}

function renderRow(d) {
    const sentAt = d.sentAt ? d.sentAt.replace('T', ' ').substring(0, 16) : '-';
    const lastSync = d.lastSyncedAt ? d.lastSyncedAt.replace('T', ' ').substring(0, 16) : '-';

    // 業務状態バッジ（色だけに依存せずtext/iconも併用。HFP-02-AC-10-04）
    let statusBadge = `<span class="badge bg-secondary">${SES.escapeHtml(d.status)}</span>`;
    if (d.status === '締結済') {
        statusBadge = `<span class="badge bg-success"><i class="bi bi-check-circle me-1"></i>${SES.escapeHtml(d.status)}</span>`;
    } else if (d.status === '取消・却下') {
        statusBadge = `<span class="badge bg-danger">${SES.escapeHtml(d.status)}</span>`;
    } else if (d.status === '要確認' || d.dispatchState === 'RECONCILIATION_REQUIRED') {
        statusBadge = `<span class="badge bg-danger"><i class="bi bi-exclamation-triangle me-1"></i>結果不明</span>`;
    } else if (d.status === '先方確認中') {
        statusBadge = `<span class="badge bg-info text-dark"><i class="bi bi-hourglass-split me-1"></i>${SES.escapeHtml(d.status)}</span>`;
    }

    // 配送工程
    const dispatchLabel = dispatchLabelOf(d.dispatchState);
    let dispatchHtml = `<span class="text-muted small">${dispatchLabel}</span>`;
    if (d.dispatchState === 'RECONCILIATION_REQUIRED' && d.operationId) {
        dispatchHtml += `<div class="text-danger small mt-1">OP: ${SES.escapeHtml(d.operationId)}</div>`;
    }

    // 操作ボタン（API認可が正。UIは表示制御のみ）
    let actions = '';
    if (CAN_SEND && d.dispatchState === 'NONE') {
        actions += `<button class="btn btn-sm btn-outline-success me-1" onclick="openSendConfirm(${d.id})"><i class="bi bi-send"></i> 送信</button>`;
    }
    if (CAN_SYNC && (d.dispatchState === 'SENT' || d.dispatchState === 'RECONCILIATION_REQUIRED')) {
        actions += `<button class="btn btn-sm btn-outline-info me-1" onclick="syncDoc(${d.id})"><i class="bi bi-arrow-repeat"></i> 同期</button>`;
    }
    // 三artifact（availabilityごと）
    actions += `<button class="btn btn-sm btn-outline-primary me-1" onclick="downloadArtifact(${d.id},'source')"><i class="bi bi-file-earmark-pdf"></i> 原本</button>`;
    if (d.signedPdfAvailable) {
        actions += `<button class="btn btn-sm btn-outline-primary me-1" onclick="downloadArtifact(${d.id},'signed')"><i class="bi bi-file-earmark-check"></i> 署名済</button>`;
    }
    if (d.certificateAvailable) {
        actions += `<button class="btn btn-sm btn-outline-primary" onclick="downloadArtifact(${d.id},'certificate')"><i class="bi bi-award"></i> 証明書</button>`;
    }

    return `<tr>
        <td class="px-4">${d.id}</td>
        <td>${SES.escapeHtml(d.recipientName)}</td>
        <td>${statusBadge}</td>
        <td>${dispatchHtml}</td>
        <td>${lastSync}</td>
        <td class="px-4 text-end">${actions}</td>
    </tr>`;
}

function dispatchLabelOf(state) {
    const labels = {
        NONE: '未送信', QUEUED: '送信待ち', CREATING: '外部準備中', DOCUMENT_CREATED: '外部準備中',
        UPLOADING: '外部準備中', FILE_UPLOADED: '外部準備中', ADDING_PARTICIPANT: '外部準備中',
        READY_TO_SEND: '外部準備中', SENDING: '送信中', SENT: '送信済', COMPLETED: '締結済',
        CANCELED: '取消・却下', RETRY_WAIT: '再試行待ち', FAILED_FINAL: '恒久エラー',
        RECONCILIATION_REQUIRED: '結果不明'
    };
    return labels[state] || SES.escapeHtml(String(state || ''));
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

/** 送信確認modal: 契約番号/原本SHA-256 prefix/宛先名・会社/言語を表示し、確認後durable queueへ。 */
function openSendConfirm(id) {
    $.get(`/api/contract-documents/${id}`, function(res) {
        if (res.code === 200) {
            const d = res.data;
            sendTarget = {
                id: d.id,
                contractNo: d.contractNo || String(d.contractId),
                templateVersion: d.templateVersion,
                recipientName: d.recipientName,
                recipientEmail: d.recipientEmail,
                title: `SES契約書 ${d.contractId}`,
                languageCode: 'ja'
            };
            const hashPrefix = d.sourcePdfSha256 ? d.sourcePdfSha256.substring(0, 8) : '-';
            const company = d.recipientCompany || '-';
            $('#sendConfirmBody').html(`
                <tr><th class="px-3 py-2 text-muted small">契約番号</th><td class="px-3 py-2">${SES.escapeHtml(sendTarget.contractNo)}</td></tr>
                <tr><th class="px-3 py-2 text-muted small">原本SHA-256</th><td class="px-3 py-2"><code>${SES.escapeHtml(hashPrefix)}...</code></td></tr>
                <tr><th class="px-3 py-2 text-muted small">宛先名</th><td class="px-3 py-2">${SES.escapeHtml(d.recipientName)}</td></tr>
                <tr><th class="px-3 py-2 text-muted small">宛先会社</th><td class="px-3 py-2">${SES.escapeHtml(company)}</td></tr>
                <tr><th class="px-3 py-2 text-muted small">宛先メール</th><td class="px-3 py-2">${SES.escapeHtml(d.recipientEmail)}</td></tr>
                <tr><th class="px-3 py-2 text-muted small">送信言語</th><td class="px-3 py-2">日本語（ja）</td></tr>
            `);
            $('#sendConfirmCheck').prop('checked', false);
            $('#sendConfirmButton').prop('disabled', true);
            new bootstrap.Modal(document.getElementById('sendConfirmModal')).show();
        }
    });
}

/** queue受付のみ。provider送信完了を偽装しない（HFP-02-AC-10-02）。 */
function confirmSend() {
    if (!sendTarget) {
        return;
    }
    $.ajax({
        url: `/api/contract-documents/${sendTarget.id}/send`,
        type: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({
            contractNo: sendTarget.contractNo,
            templateVersion: sendTarget.templateVersion,
            recipientName: sendTarget.recipientName,
            recipientEmail: sendTarget.recipientEmail,
            title: sendTarget.title,
            languageCode: sendTarget.languageCode
        }),
        success: function(res) {
            if (res.code === 200) {
                bootstrap.Modal.getInstance(document.getElementById('sendConfirmModal')).hide();
                SES.toast.success(res.message || '送信処理を受け付けました');
                loadDocuments();
            }
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

function downloadArtifact(id, kind) {
    window.open(`/api/contract-documents/${id}/artifacts/${kind}`, '_blank');
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
