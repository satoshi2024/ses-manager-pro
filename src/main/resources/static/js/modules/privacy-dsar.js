/**
 * NF07 DSAR 画面（/privacy/dsar 一覧・受付、/privacy/dsar/{id} 詳細・本人確認・名寄せ・範囲確定・終了、
 * A2b の検索・草案・承認・専用ダウンロード・交付の記録）。
 * 表示は GET /api/privacy/dsar(/{id}(/delivery))、状態の変更は POST・PUT /api/privacy/dsar/**。
 * 開示資料は GET /api/privacy/dsar/{id}/package/download だけから取得する（汎用の /api/files は使わない）。
 *
 * - subject 参照は要求本文だけで送る（URL に入れない）。
 * - 状態を変える要求は表示時の rowVersion を送る（別の操作が先に進めていれば 409 になり、再読み込みを促す）。
 * - 候補は subject 参照・裁定・記録の件数だけを表示する（API も記録の内容を返さない）。
 * - 依頼者の連絡先・本人確認書類の本体は入力させない（外部の記録の参照 ID だけ）。
 */
(function () {
    'use strict';

    var REFERENCE = /^[A-Za-z0-9][A-Za-z0-9._:#\/-]{0,127}$/;
    /** 書類の番号・電話番号に見える参照（server の PrivacyDsarVocabulary.looksLikeDocumentNumber と同じ規則）。 */
    var DOCUMENT_NUMBER_LIKE = [/^[0-9]{10,}$/, /^[0-9]{4}-[0-9]{4}-[0-9]{4}$/, /^0[0-9]{1,4}-[0-9]{1,4}-[0-9]{3,4}$/,
        /^[A-Za-z]{2}[0-9]{7}$/];
    var POSITIVE_ID = /^[1-9][0-9]{0,17}$/;
    var list = {page: 1, size: 20, total: 0};
    var detail = null;
    /** 開示資料の区画（GET /api/privacy/dsar/{id}/delivery。記録の値・保存名は含まない）。 */
    var section = null;

    function t(key) {
        return SES.i18n.t.apply(SES.i18n, arguments);
    }

    function esc(value) {
        return SES.escapeHtml(value === null || value === undefined ? '' : String(value));
    }

    /** 参照の入力の誤り（無ければ null）。書類の番号・電話番号の転記は保存しない。 */
    function referenceError(value) {
        if (!REFERENCE.test(value)) {
            return t('privacyDsar.validation.reference');
        }
        var numberLike = DOCUMENT_NUMBER_LIKE.some(function (pattern) {
            return pattern.test(value);
        });
        return numberLike ? t('privacyDsar.validation.documentNumber') : null;
    }

    /** 列挙値の表示名。辞書に無い値は値そのものを出す。 */
    function label(prefix, value) {
        if (value === null || value === undefined || value === '') {
            return '-';
        }
        return t(prefix + value, String(value));
    }

    function errorMessage(xhr) {
        return (xhr && xhr.responseJSON && xhr.responseJSON.message) || t('privacyDsar.error.load');
    }

    function fetchJson(url, data) {
        return new Promise(function (resolve, reject) {
            $.ajax({
                url: url,
                method: 'GET',
                data: data,
                success: function (res) {
                    if (res.code === 200) {
                        resolve(res.data);
                    } else {
                        reject(res.message || t('privacyDsar.error.load'));
                    }
                },
                error: function (xhr) {
                    reject(errorMessage(xhr));
                }
            });
        });
    }

    function send(method, url, payload) {
        return new Promise(function (resolve, reject) {
            $.ajax({
                url: url,
                method: method,
                contentType: 'application/json',
                data: JSON.stringify(payload),
                headers: SES.csrf.header(),
                success: function (res) {
                    if (res.code === 200) {
                        resolve(res.data);
                    } else {
                        reject(res.message || t('privacyDsar.error.load'));
                    }
                },
                error: function (xhr) {
                    reject(errorMessage(xhr));
                }
            });
        });
    }

    function confirmThen(text, action) {
        Swal.fire({
            title: t('privacyDsar.confirm.title'),
            text: text,
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: t('privacyDsar.confirm.ok'),
            cancelButtonText: t('common.cancel')
        }).then(function (result) {
            if (result.isConfirmed) {
                action();
            }
        });
    }

    function renderReadiness(readiness, identityTarget, dueTarget) {
        if (!readiness) {
            return;
        }
        if (readiness.identityReady) {
            $(identityTarget).text(t('privacyDsar.readiness.ready'));
        } else {
            var missing = (readiness.missing || []).map(function (code) {
                return t('privacyDsar.readiness.' + code, code);
            });
            $(identityTarget).text(t('privacyDsar.readiness.missing', missing.join(', ')));
        }
        if (dueTarget) {
            $(dueTarget).text(readiness.dueRuleConfigured
                ? t('privacyDsar.readiness.dueDays', String(readiness.dueDays))
                : t('privacyDsar.readiness.dueNotConfigured'));
        }
    }

    function dueBadge(item) {
        if (!item.dueDate) {
            return '<span class="text-secondary">' + esc(t('privacyDsar.due.none')) + '</span>';
        }
        var badge = '';
        if (item.overdue) {
            badge = ' <span class="badge bg-danger">' + esc(t('privacyDsar.due.overdue')) + '</span>';
        } else if (item.dueSoon) {
            badge = ' <span class="badge bg-warning text-dark">' + esc(t('privacyDsar.due.soon')) + '</span>';
        }
        return '<span class="text-nowrap">' + esc(item.dueDate) + '</span>' + badge;
    }

    // ------------------------------------------------------------ 一覧・受付

    function loadList() {
        fetchJson('/api/privacy/dsar', {status: $('#dsar-filter-status').val() || '', page: list.page, size: list.size})
            .then(function (data) {
                list.total = data.total || 0;
                renderReadiness(data.readiness, '#privacy-dsar-readiness-identity', '#privacy-dsar-readiness-due');
                renderList(data.requests || []);
            })
            .catch(function (message) {
                SES.toast.error(message);
            });
    }

    function renderList(requests) {
        if (requests.length === 0) {
            $('#privacy-dsar-body').html('<tr><td colspan="6" class="text-center text-secondary">'
                + esc(t('privacyDsar.empty')) + '</td></tr>');
        } else {
            $('#privacy-dsar-body').html(requests.map(function (item) {
                return '<tr>'
                    + '<td class="text-nowrap"><a class="link-light" href="/privacy/dsar/' + encodeURIComponent(item.id) + '">'
                    + esc(item.requestNo) + '</a></td>'
                    + '<td>' + esc(label('privacyDsar.type.', item.requestType)) + '</td>'
                    + '<td>' + esc(label('privacyDsar.status.', item.status)) + '</td>'
                    + '<td>' + esc(label('privacyDsar.channel.', item.channel)) + '</td>'
                    + '<td class="text-nowrap">' + esc(item.receivedAt) + '</td>'
                    + '<td>' + dueBadge(item) + '</td>'
                    + '</tr>';
            }).join(''));
        }
        var first = list.total === 0 ? 0 : (list.page - 1) * list.size + 1;
        var last = Math.min(list.total, list.page * list.size);
        $('#privacy-dsar-pager-summary').text(t('privacyDsar.pager.summary', String(list.total), String(first), String(last)));
        $('#privacy-dsar-prev').prop('disabled', list.page <= 1);
        $('#privacy-dsar-next').prop('disabled', last >= list.total);
    }

    function submitIntake(event) {
        event.preventDefault();
        var ticketRef = $.trim($('#dsar-ticket-ref').val());
        var reopenOf = $.trim($('#dsar-reopen-of').val());
        if (!ticketRef) {
            SES.toast.error(t('privacyDsar.validation.required'));
            return;
        }
        if (referenceError(ticketRef)) {
            SES.toast.error(referenceError(ticketRef));
            return;
        }
        if (reopenOf && !POSITIVE_ID.test(reopenOf)) {
            SES.toast.error(t('privacyDsar.validation.positiveId'));
            return;
        }
        var payload = {
            requestType: $('#dsar-request-type').val(),
            channel: $('#dsar-channel').val(),
            receivedAt: $('#dsar-received-at').val() || null,
            intakeTicketRef: ticketRef,
            reopenOfRequestId: reopenOf ? Number(reopenOf) : null
        };
        confirmThen(t('privacyDsar.confirm.intake'), function () {
            $('#dsar-intake-submit').prop('disabled', true);
            send('POST', '/api/privacy/dsar', payload).then(function (result) {
                SES.toast.success(t('privacyDsar.toast.saved'));
                window.location.href = '/privacy/dsar/' + encodeURIComponent(result.requestId);
            }).catch(function (message) {
                SES.toast.error(message);
            }).then(function () {
                $('#dsar-intake-submit').prop('disabled', false);
            });
        });
    }

    function initList() {
        $('#privacy-dsar-reload').on('click', loadList);
        $('#dsar-filter-status').on('change', function () {
            list.page = 1;
            loadList();
        });
        $('#searchForm').on('submit', function (event) {
            event.preventDefault();
            list.page = 1;
            loadList();
        });
        $('#privacy-dsar-prev').on('click', function () {
            if (list.page > 1) {
                list.page--;
                loadList();
            }
        });
        $('#privacy-dsar-next').on('click', function () {
            if (list.page * list.size < list.total) {
                list.page++;
                loadList();
            }
        });
        $('#privacy-dsar-intake-form').on('submit', submitIntake);
        loadList();
    }

    // ------------------------------------------------------------ 詳細

    function requestId() {
        return $('#privacy-dsar-detail').attr('data-request-id');
    }

    function loadDetail() {
        fetchJson('/api/privacy/dsar/' + encodeURIComponent(requestId()))
            .then(function (data) {
                detail = data;
                renderDetail();
                return fetchJson('/api/privacy/dsar/' + encodeURIComponent(requestId()) + '/delivery');
            })
            .then(function (data) {
                section = data;
                renderDelivery();
            })
            .catch(function (message) {
                SES.toast.error(message);
            });
    }

    // ------------------------------------------------------------ 開示資料（NF07-A2b）

    function sectionCan(action) {
        return section && (section.actions || []).indexOf(action) >= 0;
    }

    function shortHash(value) {
        return value ? String(value).substring(0, 12) : '-';
    }

    /** 開示以外の請求の手動対応の完了の区画（review P2-2）。結果の選択肢は請求の種別に合うものだけを出す。 */
    function renderResolution() {
        var holdText = label('privacyDsar.hold.', section.hold ? section.hold.state : null);
        $('#dsar-resolution-hold').text(holdText);
        var requestType = detail ? detail.requestType : '';
        $('#dsar-resolution-code option').each(function () {
            var types = ($(this).attr('data-types') || '').split(' ');
            var applies = types.indexOf(requestType) >= 0;
            $(this).prop('hidden', !applies).prop('disabled', !applies);
        });
        var first = $('#dsar-resolution-code option:not(:disabled)').first();
        if ($('#dsar-resolution-code option:selected').prop('disabled') && first.length) {
            $('#dsar-resolution-code').val(first.val());
        }
        $('#dsar-resolution-form').toggleClass('d-none', !sectionCan('RECORD_MANUAL_RESOLUTION'));
        if (section.manualResolution) {
            $('#dsar-resolution-record').removeClass('d-none').text(t('privacyDsar.resolution.recorded',
                section.manualResolution.resolvedAt || '-',
                label('privacyDsar.resolution.', section.manualResolution.resolutionCode),
                section.manualResolution.evidenceRef || '-'));
        } else {
            $('#dsar-resolution-record').addClass('d-none').text('');
        }
        renderResolutionHoldWarning();
    }

    /** 選んだ結果が本人の保全 hold（HELD）と矛盾し得るか（判定は server の holdConflictResolutions。HYG-FINAL P3-F）。 */
    function selectedResolutionConflictsWithHold() {
        return sectionCan('RECORD_MANUAL_RESOLUTION')
            && (section.holdConflictResolutions || []).indexOf($('#dsar-resolution-code').val()) >= 0;
    }

    function renderResolutionHoldWarning() {
        var recorded = section && section.manualResolution && section.manualResolution.holdConflict;
        $('#dsar-resolution-hold-warning').toggleClass('d-none', !(recorded || selectedResolutionConflictsWithHold()));
    }

    function renderDelivery() {
        if (!section) {
            return;
        }
        $('#dsar-decision-changed').toggleClass('d-none', !section.decisionChanged);
        // 開示請求は開示資料の区画、それ以外の請求は手動対応の完了の区画を出す
        $('#dsar-delivery-card').toggleClass('d-none', !section.exportSupported);
        $('#dsar-resolution-card').toggleClass('d-none', section.exportSupported);
        if (!section.exportSupported) {
            renderResolution();
            return;
        }
        $('#dsar-delivery-hold').text(label('privacyDsar.hold.', section.hold ? section.hold.state : null));
        $('#dsar-delivery-package-rule').text(section.packageRuleConfigured
            ? t('privacyDsar.readiness.ready') : t('privacyDsar.delivery.packageRuleUnset'));
        var pkg = section.currentPackage;
        if (pkg) {
            var state = label('privacyDsar.packageStatus.', pkg.status);
            if (pkg.expired) {
                state += ' (' + t('privacyDsar.delivery.expired') + ')';
            }
            $('#dsar-delivery-package').text(t('privacyDsar.delivery.packageSummary', state, pkg.createdAt || '-',
                pkg.purgeAfter || '-', String(pkg.sizeBytes), shortHash(pkg.contentSha256)));
        } else {
            $('#dsar-delivery-package').text(t('privacyDsar.delivery.noPackage'));
        }
        $('#dsar-delivery-redaction').text(section.redactionReason
            ? t('privacyDsar.delivery.redactionSummary', label('privacyDsar.redaction.', section.redactionReason),
                String(section.autoReleasedCount === null || section.autoReleasedCount === undefined
                    ? 0 : section.autoReleasedCount))
            : '-');
        $('#dsar-delivery-approval').text(section.approvedAt
            ? t(section.approvedByMe ? 'privacyDsar.delivery.approvedByMe' : 'privacyDsar.delivery.approvedByOther',
                section.approvedAt)
            : '-');

        var notReady = sectionCan('PREVIEW') && !section.previewReady;
        $('#dsar-delivery-not-ready').toggleClass('d-none', !notReady).text(notReady
            ? t(section.decisionChanged ? 'privacyDsar.delivery.decisionChanged'
                : (section.packageRuleConfigured ? 'privacyDsar.delivery.decisionsMissing'
                    : 'privacyDsar.delivery.packageRuleUnset'))
            : '');
        $('#dsar-preview-submit').toggleClass('d-none', !sectionCan('PREVIEW')).prop('disabled', !section.previewReady);
        $('#dsar-approve-submit').toggleClass('d-none', !sectionCan('APPROVE'));
        $('#dsar-review-submit').toggleClass('d-none', !sectionCan('REVIEW_DRAFT'));
        if (!sectionCan('REVIEW_DRAFT')) {
            $('#dsar-review-panel').addClass('d-none');
            $('#dsar-review-content').text('');
        }
        $('#dsar-approve-own').toggleClass('d-none', !(section.status === 'PREVIEWED' && pkg && pkg.status === 'DRAFT'
            && !pkg.expired && (pkg.createdByMe || section.preparedByMe)));
        $('#dsar-download-submit').toggleClass('d-none', !sectionCan('DOWNLOAD'));
        $('#dsar-delivery-form').toggleClass('d-none', !sectionCan('RECORD_DELIVERY'));
        $('#dsar-download-first').toggleClass('d-none', !(sectionCan('DOWNLOAD') && !sectionCan('RECORD_DELIVERY')));
        $('#dsar-downloaded-expired').toggleClass('d-none', !(section.status === 'APPROVED'
            && section.approvedPackageDownloaded && pkg && (pkg.expired || pkg.status === 'PURGED')));

        var providers = section.providers || [];
        $('#dsar-delivery-providers').html(providers.length === 0
            ? '<tr><td colspan="2" class="text-center text-secondary">' + esc(t('privacyDsar.delivery.noSearch')) + '</td></tr>'
            : providers.map(function (entry) {
                return '<tr><td class="text-break"><code>' + esc(entry.providerId) + '</code></td><td>'
                    + esc(t('privacyDsar.candidates.rowCount', String(entry.recordCount))) + '</td></tr>';
            }).join(''));
        var manual = section.manualReview || [];
        $('#dsar-delivery-manual').html(manual.length === 0
            ? '<tr><td colspan="4" class="text-center text-secondary">' + esc(t('privacyDsar.delivery.noManual')) + '</td></tr>'
            : manual.map(function (item) {
                return '<tr><td class="text-break"><code>' + esc(item.providerId || '-') + '</code></td>'
                    + '<td class="text-break"><code>' + esc(item.dataElementId || '-') + '</code></td>'
                    + '<td>' + esc(label('privacyDsar.manualReason.', item.reason)) + '</td>'
                    + '<td>' + esc(item.recordCount === null || item.recordCount === undefined
                        ? t('privacyDsar.delivery.notCounted') : t('privacyDsar.candidates.rowCount', String(item.recordCount)))
                    + '</td></tr>';
            }).join(''));

        if (section.delivery) {
            $('#dsar-delivery-record').removeClass('d-none').text(t('privacyDsar.delivery.recorded',
                section.delivery.deliveredAt || '-', label('privacyDsar.deliveryMethod.', section.delivery.deliveryMethod),
                section.delivery.deliveryEvidenceRef || '-'));
        } else {
            $('#dsar-delivery-record').addClass('d-none').text('');
        }
    }

    function submitPreview() {
        // 期限切れの承認済み資料の作り直しは承認を外すため、交付していないことを確かめてもらう（review P3-4）
        mutate('POST', '/preview', {rowVersion: section.rowVersion}, t(section.status === 'APPROVED'
            ? 'privacyDsar.confirm.redraftApproved' : 'privacyDsar.confirm.preview'));
    }

    /**
     * 承認者による承認前の草案の確認（review P2-3）。内容は text として表示し、保存しない。内容 hash は URL に置かず
     * X-Content-Sha256 header で送る（query は ALB・proxy の access log に残るため。HYG-FINAL P3-C）。
     */
    function submitReview() {
        var pkg = section.currentPackage;
        if (!pkg) {
            return;
        }
        $.ajax({
            url: '/api/privacy/dsar/' + encodeURIComponent(requestId()) + '/approve/preview',
            method: 'GET',
            dataType: 'json',
            cache: false,
            data: {packageId: pkg.id},
            headers: {'X-Content-Sha256': pkg.contentSha256},
            success: function (draft) {
                $('#dsar-review-content').text(JSON.stringify(draft, null, 2));
                $('#dsar-review-panel').removeClass('d-none');
            },
            error: function (xhr) {
                SES.toast.error(errorMessage(xhr));
            }
        });
    }

    function submitResolution(event) {
        event.preventDefault();
        var evidence = $.trim($('#dsar-resolution-evidence').val());
        if (!evidence || referenceError(evidence)) {
            SES.toast.error(evidence ? referenceError(evidence) : t('privacyDsar.validation.required'));
            return;
        }
        // 本人が HELD で削除・利用停止を記録するときは確認の文に hold の警告を足す（記録は止めない。HYG-FINAL P3-F）
        var confirmText = selectedResolutionConflictsWithHold()
            ? t('privacyDsar.resolution.holdWarning') + ' ' + t('privacyDsar.confirm.resolution')
            : t('privacyDsar.confirm.resolution');
        var payload = {
            rowVersion: section.rowVersion,
            resolutionCode: $('#dsar-resolution-code').val(),
            evidenceRef: evidence
        };
        confirmThen(confirmText, function () {
            send('POST', '/api/privacy/dsar/' + encodeURIComponent(requestId()) + '/resolution', payload).then(function (data) {
                // 記録の応答が本人の hold との矛盾の印を返したら、成功の代わりに警告で知らせる（判定は server。記録は済んでいる。
                // HYG-FINAL P3-F の残り）。hold の状態を確かめられなかった（UNKNOWN）ときも成功だけで済ませず、確かめ直すよう知らせる
                // （RB-NF07-HOLD review P3-5）
                if (data && data.holdConflict) {
                    SES.toast.warning(t('privacyDsar.resolution.recordedHoldWarning'));
                } else if (data && data.holdState === 'UNKNOWN') {
                    SES.toast.warning(t('privacyDsar.resolution.recordedHoldUnknown'));
                } else {
                    SES.toast.success(t('privacyDsar.toast.saved'));
                }
            }).catch(function (message) {
                SES.toast.error(message);
            }).then(function () {
                loadDetail();
            });
        });
    }

    function submitApprove() {
        var pkg = section.currentPackage;
        if (!pkg) {
            return;
        }
        mutate('POST', '/approve', {rowVersion: section.rowVersion, packageId: pkg.id, contentSha256: pkg.contentSha256},
            t('privacyDsar.confirm.approve', shortHash(pkg.contentSha256)));
    }

    function submitDownload() {
        var pkg = section.currentPackage;
        if (!pkg) {
            return;
        }
        // 交付の記録は取得の後だけ出る（HYG-FINAL P3-B）ため、取得できたら区画を読み直す
        SES.download('/api/privacy/dsar/' + encodeURIComponent(requestId()) + '/package/download?packageId='
            + encodeURIComponent(pkg.id), (detail ? detail.requestNo : 'dsar') + '.json').then(function (ok) {
            if (ok) {
                loadDetail();
            }
        });
    }

    function submitDelivery(event) {
        event.preventDefault();
        var pkg = section.currentPackage;
        var evidence = $.trim($('#dsar-delivery-evidence').val());
        if (!evidence || referenceError(evidence)) {
            SES.toast.error(evidence ? referenceError(evidence) : t('privacyDsar.validation.required'));
            return;
        }
        if (!pkg) {
            return;
        }
        mutate('POST', '/delivery', {
            rowVersion: section.rowVersion,
            packageId: pkg.id,
            contentSha256: pkg.contentSha256,
            deliveryMethod: $('#dsar-delivery-method').val(),
            deliveryEvidenceRef: evidence
        }, t('privacyDsar.confirm.delivery'));
    }

    function can(action) {
        return detail && (detail.nextActions || []).indexOf(action) >= 0;
    }

    function renderDetail() {
        $('#dsar-detail-request-no').text(detail.requestNo);
        $('#dsar-detail-status').text(label('privacyDsar.status.', detail.status));
        $('#dsar-detail-type').text(label('privacyDsar.type.', detail.requestType));
        $('#dsar-detail-channel').text(label('privacyDsar.channel.', detail.channel));
        $('#dsar-detail-received').text(detail.receivedAt || '-');
        $('#dsar-detail-due').html(dueBadge(detail));
        $('#dsar-detail-ticket').text(detail.intakeTicketRef || '-');
        $('#dsar-detail-reopen').text(detail.reopenOfRequestId ? String(detail.reopenOfRequestId) : '-');
        if (detail.closure) {
            $('#dsar-detail-closure').removeClass('d-none').text(t('privacyDsar.detail.closure',
                label('privacyDsar.status.', detail.closure.state), label('privacyDsar.reason.', detail.closure.reasonCode),
                detail.closure.closedAt || '-'));
        } else {
            $('#dsar-detail-closure').addClass('d-none').text('');
        }

        $('#dsar-identity-state').text(label('privacyDsar.identity.', detail.identityState));
        $('#dsar-identity-evidence').text(detail.identityEvidenceRef || '-');
        $('#dsar-identity-rule').text(detail.identityRuleRef || '-');
        $('#dsar-identity-verified-at').text(detail.identityVerifiedAt || '-');
        var needsDecision = (can('VERIFY_IDENTITY') || can('SCOPE')) && detail.readiness && !detail.readiness.identityReady;
        if (needsDecision) {
            var missing = (detail.readiness.missing || []).map(function (code) {
                return t('privacyDsar.readiness.' + code, code);
            });
            $('#dsar-decision-missing').removeClass('d-none').text(t('privacyDsar.readiness.missing', missing.join(', ')));
        } else {
            $('#dsar-decision-missing').addClass('d-none').text('');
        }
        $('#dsar-identity-form').toggleClass('d-none', !can('VERIFY_IDENTITY'));
        $('#dsar-candidate-form').toggleClass('d-none', !can('ADD_CANDIDATE'));
        renderCandidates();

        if (detail.scope) {
            $('#dsar-scope-record').text(t('privacyDsar.detail.scope.done', detail.scope.asOf || '-',
                String(detail.scope.elementCount === null || detail.scope.elementCount === undefined
                    ? '-' : detail.scope.elementCount)));
        } else {
            $('#dsar-scope-record').text('');
        }
        $('#dsar-scope-submit').toggleClass('d-none', !can('SCOPE')).prop('disabled', detail.selectedCount !== 1);
        $('#dsar-close-card').toggleClass('d-none', !(can('BLOCK') || can('CANCEL')));
        $('#dsar-no-actions').toggleClass('d-none', (detail.nextActions || []).length > 0);
    }

    function renderCandidates() {
        var candidates = detail.candidates || [];
        var resolvable = can('RESOLVE_CANDIDATE');
        if (candidates.length === 0) {
            $('#dsar-candidate-body').html('<tr><td colspan="4" class="text-center text-secondary">'
                + esc(t('privacyDsar.candidates.empty')) + '</td></tr>');
        } else {
            $('#dsar-candidate-body').html(candidates.map(function (candidate) {
                var footprint;
                if (candidate.footprintOmitted) {
                    footprint = '<span class="text-secondary">' + esc(t('privacyDsar.candidates.footprintOmitted')) + '</span>';
                } else if (!candidate.footprintAvailable) {
                    footprint = '<span class="text-warning">' + esc(t('privacyDsar.candidates.footprintUnavailable')) + '</span>';
                } else {
                    footprint = '<ul class="list-unstyled mb-0">' + (candidate.footprint || []).map(function (entry) {
                        return '<li class="text-break"><code>' + esc(entry.dataElementId) + '</code> '
                            + esc(t('privacyDsar.candidates.rowCount', String(entry.rowCount)))
                            + ' <span class="text-secondary">' + esc(label('privacyDsar.delivery.', entry.deliveryClass)) + '</span></li>';
                    }).join('') + '</ul>';
                }
                var operations = '';
                if (resolvable) {
                    operations = '<div class="d-grid gap-1">'
                        + '<button type="button" class="btn btn-sm btn-outline-success dsar-resolve" data-link="' + esc(candidate.linkId)
                        + '" data-state="SELECTED"' + (candidate.linkState === 'SELECTED' ? ' disabled' : '') + '>'
                        + esc(t('privacyDsar.candidates.select')) + '</button>'
                        + '<button type="button" class="btn btn-sm btn-outline-secondary dsar-resolve" data-link="' + esc(candidate.linkId)
                        + '" data-state="REJECTED"' + (candidate.linkState === 'REJECTED' ? ' disabled' : '') + '>'
                        + esc(t('privacyDsar.candidates.reject')) + '</button>'
                        + '</div>';
                }
                return '<tr>'
                    + '<td class="text-break"><code>' + esc(candidate.subjectRef) + '</code><br><span class="text-secondary">'
                    + esc(label('privacyDsar.subjectType.', candidate.subjectType)) + '</span></td>'
                    + '<td>' + esc(label('privacyDsar.link.', candidate.linkState)) + '</td>'
                    + '<td>' + footprint + '</td>'
                    + '<td>' + operations + '</td>'
                    + '</tr>';
            }).join(''));
        }
        $('#dsar-selected-count').text(t('privacyDsar.candidates.selectedCount', String(detail.selectedCount || 0)));
    }

    function mutate(method, path, payload, confirmText) {
        var run = function () {
            send(method, '/api/privacy/dsar/' + encodeURIComponent(requestId()) + path, payload).then(function () {
                SES.toast.success(t('privacyDsar.toast.saved'));
            }).catch(function (message) {
                SES.toast.error(message);
            }).then(function () {
                loadDetail();
            });
        };
        if (confirmText) {
            confirmThen(confirmText, run);
        } else {
            run();
        }
    }

    function submitIdentity(event) {
        event.preventDefault();
        var evidence = $.trim($('#dsar-identity-evidence-ref').val());
        if (!evidence || referenceError(evidence)) {
            SES.toast.error(evidence ? referenceError(evidence) : t('privacyDsar.validation.required'));
            return;
        }
        mutate('POST', '/identity', {rowVersion: detail.rowVersion, identityEvidenceRef: evidence},
            t('privacyDsar.confirm.identity'));
    }

    function submitCandidate(event) {
        event.preventDefault();
        var id = $.trim($('#dsar-candidate-id').val());
        if (!POSITIVE_ID.test(id)) {
            SES.toast.error(t('privacyDsar.validation.positiveId'));
            return;
        }
        mutate('POST', '/candidates', {rowVersion: detail.rowVersion, subjectRef: $('#dsar-candidate-type').val() + ':' + id});
        $('#dsar-candidate-id').val('');
    }

    function resolveCandidate() {
        var button = $(this);
        mutate('PUT', '/candidates/' + encodeURIComponent(button.attr('data-link')),
            {rowVersion: detail.rowVersion, linkState: button.attr('data-state')});
    }

    function submitScope() {
        mutate('POST', '/scope', {rowVersion: detail.rowVersion}, t('privacyDsar.confirm.scope'));
    }

    function submitClose(event) {
        event.preventDefault();
        var option = $('#dsar-close-reason option:selected');
        var evidence = $.trim($('#dsar-close-evidence').val());
        if (evidence && referenceError(evidence)) {
            SES.toast.error(referenceError(evidence));
            return;
        }
        var path = option.attr('data-target') === 'CANCELLED' ? '/cancel' : '/block';
        mutate('POST', path, {rowVersion: detail.rowVersion, reasonCode: option.val(), evidenceRef: evidence || null},
            t('privacyDsar.confirm.close'));
    }

    function initDetail() {
        $('#privacy-dsar-reload').on('click', loadDetail);
        $('#dsar-identity-form').on('submit', submitIdentity);
        $('#dsar-candidate-form').on('submit', submitCandidate);
        $('#dsar-candidate-body').on('click', '.dsar-resolve', resolveCandidate);
        $('#dsar-scope-submit').on('click', submitScope);
        $('#dsar-close-form').on('submit', submitClose);
        $('#dsar-preview-submit').on('click', submitPreview);
        $('#dsar-approve-submit').on('click', submitApprove);
        $('#dsar-download-submit').on('click', submitDownload);
        $('#dsar-delivery-form').on('submit', submitDelivery);
        $('#dsar-review-submit').on('click', submitReview);
        $('#dsar-resolution-form').on('submit', submitResolution);
        $('#dsar-resolution-code').on('change', renderResolutionHoldWarning);
        loadDetail();
    }

    $(function () {
        if ($('#privacy-dsar-detail').length) {
            initDetail();
        } else if ($('#privacy-dsar-list').length) {
            initList();
        }
    });
})();
