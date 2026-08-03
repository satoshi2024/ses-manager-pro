/**
 * 法定文書台帳モジュール JS (T024)
 */
$(document).ready(function () {
    if ($('#documentTable').length) {
        loadDocuments(1);

        $('#searchForm').on('submit', function (e) {
            e.preventDefault();
            loadDocuments(1);
        });
    }

    if ($('#documentId').length && $('#documentId').val()) {
        loadDocumentDetail($('#documentId').val());
    }
});

function loadDocuments(page) {
    const formData = $('#searchForm').serializeArray();
    let queryParams = { page: page, pageSize: 20 };
    $.each(formData, function (_, item) {
        if (item.value) {
            queryParams[item.name] = item.value;
        }
    });

    $.ajax({
        url: '/api/documents',
        type: 'GET',
        data: queryParams,
        success: function (res) {
            if (res.code === 200) {
                renderTable(res.data);
            } else {
                SES.toast.error(res.message || 'データの取得に失敗しました');
            }
        },
        error: function () {
            SES.toast.error('通信エラーが発生しました');
        }
    });
}

function renderTable(pageData) {
    const $tbody = $('#documentTableBody');
    $tbody.empty();

    if (!pageData.records || pageData.records.length === 0) {
        $tbody.append('<tr><td colspan="10" class="text-center py-4 text-muted">条件に該当する文書が見つかりません</td></tr>');
        $('#paginationInfo').text('全 0 件');
        $('#paginationNav').empty();
        return;
    }

    $.each(pageData.records, function (_, doc) {
        const typeBadge = '<span class="badge bg-secondary">' + (doc.documentTypeName || doc.documentType) + '</span>';
        const statusBadge = getStatusBadge(doc.status);
        const holdBadge = doc.legalHoldFlag === 1
            ? '<span class="badge bg-warning text-dark"><i class="bi bi-shield-lock me-1"></i>Hold中</span>'
            : '<span class="badge bg-light text-dark">なし</span>';

        const amountFormatted = doc.amount ? '¥' + Number(doc.amount).toLocaleString() : '-';

        const tr = `
            <tr>
                <td>${doc.id}</td>
                <td>${typeBadge}</td>
                <td>
                    <div class="fw-bold">${escapeHtml(doc.title || '-')}</div>
                    <small class="text-muted">${escapeHtml(doc.documentNo || '')}</small>
                </td>
                <td>${escapeHtml(doc.counterpartyNameSnapshot || '-')}</td>
                <td>${doc.transactionDate || '-'}</td>
                <td class="fw-bold">${amountFormatted}</td>
                <td>${statusBadge}</td>
                <td>${doc.retentionUntil || '<span class="text-muted small">未確定</span>'}</td>
                <td>${holdBadge}</td>
                <td class="text-center">
                    <a href="/document/detail/${doc.id}" class="btn btn-outline-primary btn-sm"><i class="bi bi-eye"></i> 詳細</a>
                </td>
            </tr>
        `;
        $tbody.append(tr);
    });

    $('#paginationInfo').text(`全 ${pageData.total} 件 (${pageData.current} / ${pageData.pages} ページ)`);
    renderPagination(pageData);
}

function getStatusBadge(status) {
    switch (status) {
        case 'DRAFT': return '<span class="badge bg-info text-dark">下書き</span>';
        case 'CONFIRMED': return '<span class="badge bg-success">確定</span>';
        case 'AMENDED': return '<span class="badge bg-warning text-dark">訂正済</span>';
        case 'CANCELLED': return '<span class="badge bg-secondary">取消</span>';
        case 'DISPOSED': return '<span class="badge bg-dark">廃棄済</span>';
        default: return '<span class="badge bg-secondary">' + status + '</span>';
    }
}

function renderPagination(pageData) {
    const $nav = $('#paginationNav');
    $nav.empty();
    if (pageData.pages <= 1) return;

    let $ul = $('<ul class="pagination pagination-sm mb-0"></ul>');

    // 前へ
    let prevDisabled = pageData.current === 1 ? 'disabled' : '';
    $ul.append(`<li class="page-item ${prevDisabled}"><a class="page-link" href="#" onclick="loadDocuments(${pageData.current - 1}); return false;">前へ</a></li>`);

    for (let i = 1; i <= pageData.pages; i++) {
        let active = i === pageData.current ? 'active' : '';
        $ul.append(`<li class="page-item ${active}"><a class="page-link" href="#" onclick="loadDocuments(${i}); return false;">${i}</a></li>`);
    }

    // 次へ
    let nextDisabled = pageData.current === pageData.pages ? 'disabled' : '';
    $ul.append(`<li class="page-item ${nextDisabled}"><a class="page-link" href="#" onclick="loadDocuments(${pageData.current + 1}); return false;">次へ</a></li>`);

    $nav.append($ul);
}

function resetSearch() {
    $('#searchForm')[0].reset();
    loadDocuments(1);
}

function loadDocumentDetail(id) {
    $.ajax({
        url: '/api/documents/' + id,
        type: 'GET',
        success: function (res) {
            if (res.code === 200) {
                renderDetail(res.data);
            } else {
                SES.toast.error(res.message || '詳細データの取得に失敗しました');
            }
        },
        error: function () {
            SES.toast.error('通信エラーが発生しました');
        }
    });
}

function renderDetail(doc) {
    $('#docTitleHeader').text(doc.title || '文書詳細');
    $('#detailDocId').text(doc.id);
    $('#detailDocType').html('<span class="badge bg-secondary">' + (doc.documentTypeName || doc.documentType) + '</span>');
    $('#detailDocNo').text(doc.documentNo || '-');
    $('#detailTitle').text(doc.title || '-');
    $('#detailCounterparty').text(doc.counterpartyNameSnapshot || '-');
    $('#detailTransactionDate').text(doc.transactionDate || '-');
    $('#detailAmount').text(doc.amount ? '¥' + Number(doc.amount).toLocaleString() : '-');
    $('#detailStatus').html(getStatusBadge(doc.status));
    $('#detailRetentionUntil').text(doc.retentionUntil || '未確定 (確定後に自動算出)');
    $('#detailLegalHold').html(doc.legalHoldFlag === 1 ? '<span class="badge bg-warning text-dark"><i class="bi bi-shield-lock me-1"></i>Hold中</span>' : '<span class="badge bg-light text-dark">なし</span>');

    // ボタンの状態制御
    if (doc.legalHoldFlag === 1) {
        $('#toggleHoldBtn').removeClass('btn-warning').addClass('btn-outline-warning').html('<i class="bi bi-shield-slash me-1"></i>法的Hold解除');
        $('#requestDisposalBtn').prop('disabled', true);
    } else {
        $('#toggleHoldBtn').removeClass('btn-outline-warning').addClass('btn-warning').html('<i class="bi bi-shield-lock me-1"></i>法的Hold設定');
        $('#requestDisposalBtn').prop('disabled', doc.status === 'DISPOSED');
    }

    // 版履歴レンダリング
    const $vBody = $('#versionTableBody');
    $vBody.empty();
    if (doc.versions && doc.versions.length > 0) {
        $.each(doc.versions, function (_, v) {
            const scanBadge = v.scanStatus === 'CLEAN' ? '<span class="badge bg-success">CLEAN</span>' : '<span class="badge bg-danger">' + v.scanStatus + '</span>';
            const tr = `
                <tr>
                    <td class="fw-bold">v${v.versionNo}</td>
                    <td>${escapeHtml(v.originalName || '-')}</td>
                    <td>${formatBytes(v.sizeBytes)}</td>
                    <td><code class="small" title="${v.sha256}">${v.sha256 ? v.sha256.substring(0, 12) + '...' : '-'}</code></td>
                    <td><span class="badge bg-light text-dark">${v.sourceType || '-'}</span></td>
                    <td>${scanBadge}</td>
                    <td class="text-center">
                        <a href="/api/documents/${doc.id}/versions/${v.versionNo}/download" class="btn btn-outline-success btn-sm"><i class="bi bi-download"></i> DL</a>
                    </td>
                </tr>
            `;
            $vBody.append(tr);
        });
    } else {
        $vBody.append('<tr><td colspan="7" class="text-center py-3 text-muted">版履歴がありません</td></tr>');
    }

    // 業務リンク
    const $linkList = $('#linkList');
    $linkList.empty();
    if (doc.links && doc.links.length > 0) {
        $.each(doc.links, function (_, link) {
            $linkList.append(`<li class="list-group-item d-flex justify-content-between align-items-center">
                <span><i class="bi bi-link me-2 text-primary"></i>${link.targetType} (ID: ${link.targetId})</span>
                <span class="badge bg-light text-dark small">${link.createdAt ? link.createdAt.substring(0, 10) : ''}</span>
            </li>`);
        });
    } else {
        $linkList.append('<li class="list-group-item text-muted small">紐付け情報がありません</li>');
    }
}

function toggleLegalHold() {
    const id = $('#documentId').val();
    const isHold = $('#toggleHoldBtn').text().includes('設定');
    const actionText = isHold ? '法的ホールドを設定' : '法的ホールドを解除';

    Swal.fire({
        title: actionText + 'しますか？',
        text: isHold ? 'ホールド中の文書は保存期限が過ぎても自動廃棄されません。' : 'ホールドを解除します。',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: '実行',
        cancelButtonText: 'キャンセル'
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: `/api/documents/${id}/legal-hold?hold=${isHold}&reason=画面操作`,
                type: 'POST',
                success: function (res) {
                    if (res.code === 200) {
                        SES.toast.success(actionText + 'しました');
                        loadDocumentDetail(id);
                    } else {
                        SES.toast.error(res.message || '操作に失敗しました');
                    }
                },
                error: function () {
                    SES.toast.error('通信エラーが発生しました');
                }
            });
        }
    });
}

function openDisposalModal() {
    $('#disposalReason').val('');
    const modal = new bootstrap.Modal(document.getElementById('disposalModal'));
    modal.show();
}

function submitDisposalRequest() {
    const id = $('#documentId').val();
    const reason = $('#disposalReason').val().trim();
    if (!reason) {
        SES.toast.error('廃棄申請理由を入力してください');
        return;
    }

    $.ajax({
        url: `/api/documents/${id}/disposal?reason=${encodeURIComponent(reason)}`,
        type: 'POST',
        success: function (res) {
            if (res.code === 200) {
                bootstrap.Modal.getInstance(document.getElementById('disposalModal')).hide();
                SES.toast.success('廃棄申請を送信しました');
                loadDocumentDetail(id);
            } else {
                SES.toast.error(res.message || '申請送信に失敗しました');
            }
        },
        error: function () {
            SES.toast.error('通信エラーが発生しました');
        }
    });
}

function formatBytes(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
