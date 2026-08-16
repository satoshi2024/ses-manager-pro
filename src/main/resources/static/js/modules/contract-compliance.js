/**
 * 契約詳細（T063 A1: compliance profile / findings）。
 * GET /api/contracts/{id}/compliance-profile で取得し、role別mask済みの値を表示する。
 * - 派遣/準委任/請負で表示sectionを切替える。
 * - maskLevel != FULL のsensitive field（cpp-sensitive）は編集不可（値は非表示）。
 * - 保存はfull DTOのPUT。masked roleはsensitive keyを省略する（サーバが現値維持）。
 * - findingsはサーバがcompliance menu権限を持つ場合のみ返す（design §5.3）。
 */
const ContractCompliance = (function () {
    let contractId = null;
    let detail = null;

    function init() {
        const match = location.pathname.match(/^\/contract\/detail\/(\d+)/);
        if (!match) {
            showError(SES.i18n.t('error.scope.notFound', '対象が存在しません'));
            return;
        }
        contractId = match[1];
        load();
    }

    function load() {
        $.ajax({
            url: '/api/contracts/' + contractId + '/compliance-profile',
            method: 'GET',
            success: function (res) {
                if (res.code !== 200 || !res.data) {
                    showError(res.message || SES.i18n.t('error.scope.notFound', '対象が存在しません'));
                    return;
                }
                detail = res.data;
                render();
            },
            error: function (err) {
                showError((err.responseJSON && err.responseJSON.message)
                    || SES.i18n.t('error.scope.notFound', '対象が存在しません'));
            }
        });
    }

    function showError(message) {
        $('#contract-detail-loading').addClass('d-none');
        $('#contract-detail-error-message').text(message);
        $('#contract-detail-error').removeClass('d-none');
    }

    function render() {
        $('#contract-detail-loading').addClass('d-none');
        $('#contract-detail-content').removeClass('d-none');

        const contractNo = detail.contractNo != null ? detail.contractNo : ('C-' + detail.contractId);
        $('#cd-contractNo').text(contractNo);
        $('#cd-contractType').text(detail.contractType || '');
        $('#cd-subtitle').text([detail.customerName, detail.projectName, detail.engineerName]
            .filter(v => v != null && v !== '').join(' / '));

        fillWorkplaceOptions(detail.workplaces || []);

        const profile = detail.profile || {};
        $('[data-key]').each(function () {
            const key = $(this).data('key');
            const value = profile[key];
            if ($(this).is(':checkbox')) {
                $(this).prop('checked', value === 1);
            } else {
                $(this).val(value != null ? String(value) : '');
            }
        });

        $('#cd-profile-version').text(profile.version != null ? ('v' + profile.version) : '');
        if (!detail.profileExists) {
            $('#cpp-no-profile').removeClass('d-none');
        }

        applyContractType(detail.contractType);
        applyMask(detail.maskLevel, detail.canEdit);

        renderFindings(detail.findings || []);

        if (detail.canEdit) {
            $('#cd-save-btn').prop('disabled', false);
        }

        // 法定帳票・交付（T064 B1。営業=LIMITEDはcompliance権限なしとして非表示）
        if (detail.maskLevel !== 'LIMITED') {
            $('#cd-documents-card').removeClass('d-none');
            if (detail.canEdit) {
                $('#cd-doc-generate-btn').prop('disabled', false);
                $('#cd-doc-preview-btn').prop('disabled', false);
            }
            loadDocuments();
        }
    }

    function fillWorkplaceOptions(workplaces) {
        const select = $('#cpp-workplaceId');
        select.find('option:not([value=""])').remove();
        workplaces.forEach(function (w) {
            select.append($('<option>').val(w.id).text(w.name));
        });
    }

    function applyContractType(contractType) {
        const isDispatch = contractType === '派遣';
        const isQuasi = contractType === '準委任' || contractType === '請負';
        $('[data-section="dispatch"]').toggle(isDispatch);
        $('[data-section="quasi"]').toggle(isQuasi);
    }

    function applyMask(maskLevel, canEdit) {
        if (maskLevel === 'MASK') {
            $('#cd-mask-note').removeClass('d-none');
            $('#cpp-form .cpp-sensitive input').each(function () {
                $(this).prop('disabled', true).attr('placeholder', '—');
            });
        }
        if (!canEdit) {
            $('#cpp-form input, #cpp-form select, #cpp-form textarea').prop('disabled', true);
        }
    }

    function renderFindings(findings) {
        if (!findings || findings.length === 0) {
            $('#cpp-findings-empty').removeClass('d-none');
            return;
        }
        $('#cpp-findings-empty').addClass('d-none');
        $('#cpp-findings-table-wrap').removeClass('d-none');
        const body = $('#cpp-findings-body');
        body.empty();
        const canAct = detail && detail.canEdit && detail.maskLevel !== 'LIMITED';
        findings.forEach(function (f) {
            const statusBadge = '<span class="badge ' + (f.status === 'OPEN' ? 'bg-danger' : f.status === 'ACK' || f.status === 'IN_PROGRESS' ? 'bg-warning text-dark' : 'bg-success') + '">'
                + SES.escapeHtml(f.status || '') + '</span>';
            let actions = '';
            if (canAct) {
                const canAck = f.status === 'OPEN' || f.status === 'IN_PROGRESS';
                const canResolve = f.status !== 'RESOLVED';
                const canException = f.status === 'OPEN' || f.status === 'ACKNOWLEDGED' || f.status === 'IN_PROGRESS';
                if (canAck) {
                    actions += '<button type="button" class="btn btn-sm btn-outline-warning py-0 px-2 me-1" title="' + SES.i18n.t('cpp.finding.ack', '対応開始') + '" onclick="ContractCompliance.ackFinding(' + f.id + ')"><i class="bi bi-check2-square"></i></button>';
                }
                if (canResolve) {
                    actions += '<button type="button" class="btn btn-sm btn-outline-success py-0 px-2 me-1" title="' + SES.i18n.t('cpp.finding.resolve', '解消') + '" onclick="ContractCompliance.resolveFinding(' + f.id + ')"><i class="bi bi-check-circle"></i></button>';
                }
                if (canException) {
                    actions += '<button type="button" class="btn btn-sm btn-outline-info py-0 px-2" title="' + SES.i18n.t('cpp.finding.exception', '例外承認') + '" onclick="ContractCompliance.exceptionFinding(' + f.id + ')"><i class="bi bi-shield-check"></i></button>';
                }
            }
            body.append(
                '<tr>' +
                '<td class="px-3 py-2">' + statusBadge + '</td>' +
                '<td class="py-2 small"><code class="text-accent-blue">' + SES.escapeHtml(f.code || '') + '</code></td>' +
                '<td class="px-3 py-2 small text-muted">' + SES.escapeHtml(f.dueDate || '—') + '</td>' +
                '<td class="px-3 py-2 text-end text-nowrap">' + actions + '</td>' +
                '</tr>');
        });
    }

    function buildPayload() {
        const payload = { version: detail.profile ? detail.profile.version : null };
        $('[data-key]').each(function () {
            const key = $(this).data('key');
            if (detail.maskLevel !== 'FULL' && $(this).closest('.cpp-sensitive').length > 0) {
                return;
            }
            const input = $(this);
            if (input.is(':checkbox')) {
                payload[key] = input.is(':checked') ? 1 : 0;
            } else if (input.is('select')) {
                const v = input.val();
                payload[key] = v !== '' && v != null ? parseInt(v, 10) : null;
            } else if (input.attr('type') === 'number') {
                const v = input.val();
                payload[key] = v !== '' && v != null ? (key === 'dispatchFeeAmount' ? parseFloat(v) : parseInt(v, 10)) : null;
            } else if (input.attr('type') === 'date') {
                const v = input.val();
                payload[key] = v !== '' && v != null ? v : null;
            } else {
                const v = input.val();
                payload[key] = v !== '' && v != null ? v : null;
            }
        });
        return payload;
    }

    function save() {
        $('#cd-save-btn').prop('disabled', true);
        $.ajax({
            url: '/api/contracts/' + contractId + '/compliance-profile',
            method: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify(buildPayload()),
            success: function (res) {
                if (res.code === 200) {
                    Toast.success(SES.i18n.t('cpp.saved', '保存しました'));
                    load();
                } else {
                    Toast.error(res.message || SES.i18n.t('js.common.error_network', '通信エラー'));
                    $('#cd-save-btn').prop('disabled', false);
                }
            },
            error: function (err) {
                const msg = err.responseJSON && err.responseJSON.message
                    ? err.responseJSON.message : SES.i18n.t('js.common.error_network', '通信エラー');
                Toast.error(msg);
                $('#cd-save-btn').prop('disabled', false);
            }
        });
    }

    // ===== 法定帳票・交付（T064 B1） =====

    function loadDocuments() {
        $.ajax({
            url: '/api/contracts/' + contractId + '/compliance-documents',
            method: 'GET',
            success: function (res) {
                if (res.code !== 200) {
                    return;
                }
                renderDocuments(res.data || []);
            },
            error: function () {
                // 一覧取得失敗はカードを空のままにする（画面は継続）
            }
        });
    }

    function renderDocuments(deliveries) {
        if (!deliveries || deliveries.length === 0) {
            $('#cd-doc-empty').removeClass('d-none');
            $('#cd-doc-table-wrap').addClass('d-none');
            return;
        }
        $('#cd-doc-empty').addClass('d-none');
        $('#cd-doc-table-wrap').removeClass('d-none');
        const body = $('#cd-doc-body');
        body.empty();
        deliveries.forEach(function (d) {
            const typeLabel = SES.i18n.t('doc.title.' + d.documentType, d.documentType);
            const statusBadge = d.deliveryStatus === 'DELIVERED'
                ? '<span class="badge bg-success">' + SES.escapeHtml(d.deliveryStatus) + '</span>'
                : '<span class="badge bg-secondary">' + SES.escapeHtml(d.deliveryStatus || '') + '</span>';
            const confirmed = d.confirmedAt
                ? '<span class="text-success small"><i class="bi bi-check-circle"></i> ' + SES.escapeHtml(String(d.confirmedAt).substring(0, 16)) + '</span>'
                : '<button type="button" class="btn btn-sm btn-outline-success py-0 px-2" onclick="ContractCompliance.confirmDelivery(' + d.id + ')">'
                    + SES.i18n.t('cpp.document.confirm', '受領確認') + '</button>';
            body.append(
                '<tr>' +
                '<td class="px-3 py-2 small">' + SES.escapeHtml(typeLabel) + '</td>' +
                '<td class="py-2 small text-muted">v' + SES.escapeHtml(String(d.templateVersion)) + '</td>' +
                '<td class="py-2">' + statusBadge + '</td>' +
                '<td class="py-2 small text-muted">' + SES.escapeHtml(d.deliveredAt ? String(d.deliveredAt).substring(0, 16) : '—') + '</td>' +
                '<td class="py-2 small">' + confirmed + '</td>' +
                '<td class="px-3 py-2 text-end text-nowrap">' +
                '<button type="button" class="btn btn-outline-info btn-sm" title="' + SES.i18n.t('cpp.document.download', 'ダウンロード') + '" onclick="ContractCompliance.downloadDelivery(' + d.id + ')"><i class="bi bi-download"></i></button>' +
                '</td>' +
                '</tr>');
        });
    }

    function generateDocument() {
        const documentType = $('#cd-doc-type').val();
        const deliveryMethod = $('#cd-doc-method').val();
        $('#cd-doc-generate-btn').prop('disabled', true);
        $.ajax({
            url: '/api/contracts/' + contractId + '/compliance-documents/generate',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ documentType: documentType, deliveryMethod: deliveryMethod, recipientContactId: null }),
            success: function (res) {
                if (res.code === 200) {
                    Toast.success(SES.i18n.t('cpp.documents.generated', '帳票を生成し交付記録を作成しました'));
                    loadDocuments();
                } else {
                    Toast.error(res.message || SES.i18n.t('js.common.error_network', '通信エラー'));
                }
                $('#cd-doc-generate-btn').prop('disabled', false);
            },
            error: function (err) {
                Toast.error((err.responseJSON && err.responseJSON.message)
                    || SES.i18n.t('js.common.error_network', '通信エラー'));
                $('#cd-doc-generate-btn').prop('disabled', false);
            }
        });
    }

    function confirmDelivery(deliveryId) {
        $.ajax({
            url: '/api/contracts/' + contractId + '/compliance-documents/' + deliveryId + '/confirm',
            method: 'POST',
            success: function (res) {
                if (res.code === 200) {
                    Toast.success(SES.i18n.t('cpp.documents.confirmed', '受領確認を記録しました'));
                    loadDocuments();
                } else {
                    Toast.error(res.message || SES.i18n.t('js.common.error_network', '通信エラー'));
                }
            },
            error: function (err) {
                Toast.error((err.responseJSON && err.responseJSON.message)
                    || SES.i18n.t('js.common.error_network', '通信エラー'));
            }
        });
    }

function downloadDelivery(deliveryId) {
window.open('/api/contracts/' + contractId + '/compliance-documents/' + deliveryId + '/download', '_blank');
}

// P1-6: watermark preview（archive 0・delivery 0・新規タブでinline表示）
function previewDocument() {
const documentType = $('#cd-doc-type').val();
if (!documentType) {
Toast.warning(SES.i18n.t('cpp.document.selectType', '帳票種別を選択してください'));
return;
}
$.ajax({
url: '/api/contracts/' + contractId + '/compliance-documents/preview',
method: 'POST',
contentType: 'application/json',
data: JSON.stringify({ documentType: documentType, deliveryMethod: 'NONE' }),
xhrFields: { responseType: 'blob' },
success: function (blob, status, xhr) {
const url = URL.createObjectURL(blob);
window.open(url, '_blank');
setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
},
error: function (err) {
Toast.error((err.responseJSON && err.responseJSON.message)
|| SES.i18n.t('js.common.error_network', '通信エラー'));
}
});
}

    // ===== finding対応（T065 B2） =====

    function findingAction(url, body, successKey) {
        $.ajax({
            url: url,
            method: 'POST',
            contentType: 'application/json',
            data: body ? JSON.stringify(body) : '{}',
            success: function (res) {
                if (res.code === 200) {
                    Toast.success(SES.i18n.t(successKey, '記録しました'));
                    load();
                } else {
                    Toast.error(res.message || SES.i18n.t('js.common.error_network', '通信エラー'));
                }
            },
            error: function (err) {
                Toast.error((err.responseJSON && err.responseJSON.message)
                    || SES.i18n.t('js.common.error_network', '通信エラー'));
            }
        });
    }

    function ackFinding(findingId) {
        findingAction('/api/contracts/' + contractId + '/compliance-findings/' + findingId + '/ack',
            null, 'cpp.finding.ackDone');
    }

    function resolveFinding(findingId) {
        Swal.fire({
            title: SES.i18n.t('cpp.finding.resolve', '解消'),
            input: 'textarea',
            inputLabel: SES.i18n.t('cpp.finding.notePrompt', '対応内容・根拠'),
            inputPlaceholder: SES.i18n.t('cpp.finding.notePrompt', '対応内容・根拠'),
            inputAttributes: { required: 'required' },
            showCancelButton: true,
            confirmButtonText: SES.i18n.t('common.register', '登録'),
            cancelButtonText: SES.i18n.t('common.cancel', 'キャンセル')
        }).then(function (result) {
            if (!result.isConfirmed || !result.value) {
                return;
            }
            findingAction('/api/contracts/' + contractId + '/compliance-findings/' + findingId + '/resolve',
                { note: result.value, evidenceDocumentId: null }, 'cpp.finding.resolveDone');
        });
    }

    function exceptionFinding(findingId) {
        Swal.fire({
            title: SES.i18n.t('cpp.finding.exception', '例外承認'),
            input: 'textarea',
            inputLabel: SES.i18n.t('cpp.finding.notePrompt', '対応内容・根拠'),
            inputPlaceholder: SES.i18n.t('cpp.finding.notePrompt', '対応内容・根拠'),
            showCancelButton: true,
            confirmButtonText: SES.i18n.t('common.register', '登録'),
            cancelButtonText: SES.i18n.t('common.cancel', 'キャンセル')
        }).then(function (result) {
            if (!result.isConfirmed || !result.value) {
                return;
            }
            const expiresAt = prompt(SES.i18n.t('cpp.finding.expiresPrompt', '有効期限（例: 2026-12-31T23:59）'));
            if (!expiresAt) {
                return;
            }
            findingAction('/api/contracts/' + contractId + '/compliance-findings/' + findingId + '/exception',
                { note: result.value, expiresAt: expiresAt }, 'cpp.finding.exceptionDone');
        });
    }

return {
init: init, save: save,
generateDocument: generateDocument, previewDocument: previewDocument,
confirmDelivery: confirmDelivery, downloadDelivery: downloadDelivery,
ackFinding: ackFinding, resolveFinding: resolveFinding, exceptionFinding: exceptionFinding
};
})();

$(function () {
    ContractCompliance.init();
});
