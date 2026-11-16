/**
 * NF07 保全 hold 画面（/privacy/holds）。表示は GET /api/privacy/holds、開始は POST /api/privacy/holds。
 *
 * - subject 参照は要求本文だけで送る（URL に入れない）。
 * - SUBJECT hold では、帰属できない data element ごとに「DATA_ELEMENT hold を追加」か「守られないことを受理」を
 *   明示して選ぶ（既定値なし。C-2）。「追加」を選んだ element は、SUBJECT hold の前に DATA_ELEMENT hold を開始する
 *   （その element の purge が解除の開放まで止まることを確認させる）。
 * - 解除（NF07-REL）は POST /api/privacy/holds/{holdId}/release。発行者・承認者が system の外で署名した proof
 *   （binding・issuer・approver の JSON）を貼り付けて送る。画面は署名の対象（hold ID・partition・hold revision・
 *   event hash）を表示するだけで、署名・鍵を扱わない。D-03・D-05 の署名前は入口が閉じている（releaseReady=false）。
 */
(function () {
    'use strict';

    var CHOICE_ADD = 'DATA_ELEMENT_HOLD';
    var CHOICE_ACCEPT = 'ACCEPT_RISK';
    var DATA_ELEMENT_PARTITION_PREFIX = 'DATA_ELEMENT#';
    var overview = null;

    function t(key) {
        return SES.i18n.t.apply(SES.i18n, arguments);
    }

    function esc(value) {
        return SES.escapeHtml(value === null || value === undefined ? '' : String(value));
    }

    function errorMessage(xhr) {
        return (xhr && xhr.responseJSON && xhr.responseJSON.message) || t('privacyHold.error.load');
    }

    function load() {
        $.ajax({
            url: '/api/privacy/holds',
            method: 'GET',
            success: function (res) {
                if (res.code === 200) {
                    overview = res.data;
                    render();
                } else {
                    SES.toast.error(res.message || t('privacyHold.error.load'));
                }
            },
            error: function (xhr) {
                SES.toast.error(errorMessage(xhr));
            }
        });
    }

    function render() {
        renderStatus();
        renderHolds();
        renderForm();
        renderRelease();
        renderNotes();
    }

    function renderStatus() {
        var gate = overview.gateState === 'UNAVAILABLE'
            ? t('privacyHold.gate.UNAVAILABLE', overview.gateReason || '')
            : t('privacyHold.gate.' + overview.gateState);
        $('#privacy-hold-gate').text(gate);
        $('#privacy-hold-journal').text(t('privacyHold.journal.' + overview.journalAvailability));
        if (overview.startReady) {
            $('#privacy-hold-prerequisites').text(t('privacyHold.prereq.ready'));
        } else {
            var missing = (overview.missingPrerequisites || []).map(function (code) {
                return t('privacyHold.prereq.' + code.replace('.', '_'));
            });
            $('#privacy-hold-prerequisites').text(t('privacyHold.prereq.missing', missing.join(', ')));
        }
    }

    function renderHolds() {
        var holds = overview.holds || [];
        if (holds.length === 0) {
            $('#privacy-hold-body').html('<tr><td colspan="9" class="text-center text-secondary">'
                + esc(t('privacyHold.empty')) + '</td></tr>');
            return;
        }
        var rows = holds.map(function (hold) {
            var target = hold.targetType === 'SUBJECT' ? hold.subjectRef : hold.dataElementId;
            var reason = hold.reconciled
                ? '<span class="badge bg-warning text-dark">' + esc(t('privacyHold.reconciled')) + '</span>'
                : esc(hold.reasonCode);
            return '<tr>'
                + '<td class="text-break"><code>' + esc(hold.holdId) + '</code></td>'
                + '<td>' + esc(t('privacyHold.target.' + hold.targetType)) + '</td>'
                + '<td class="text-break">' + esc(target) + '</td>'
                + '<td><span class="badge ' + (hold.status === 'ACTIVE' ? 'bg-success' : 'bg-secondary') + '">'
                + esc(t('privacyHold.status.' + hold.status)) + '</span></td>'
                + '<td>' + reason + '</td>'
                + '<td class="text-break">' + esc(hold.reconciled ? '' : hold.ticketRef) + '</td>'
                + '<td class="text-nowrap">' + esc(hold.requestedAt) + '</td>'
                + '<td>' + esc(hold.journalRevision) + '</td>'
                + '<td class="text-nowrap">' + esc(hold.releasedAt) + '</td>'
                + '</tr>';
        });
        $('#privacy-hold-body').html(rows.join(''));
    }

    function renderForm() {
        var allowed = overview.allowedTargetTypes || [];
        var select = $('#hold-target-type').empty();
        allowed.forEach(function (type) {
            select.append($('<option>').val(type).text(t('privacyHold.target.' + type)));
        });
        var elements = $('#hold-data-element').empty();
        (overview.holdableElements || []).forEach(function (id) {
            elements.append($('<option>').val(id).text(id));
        });
        var rows = (overview.unattributableElements || []).map(function (element, index) {
            var name = 'hold-choice-' + index;
            var count = element.tenantRowCount === null || element.tenantRowCount === undefined
                ? t('privacyHold.countUnknown') : element.tenantRowCount;
            var held = element.dataElementHoldActive ? t('privacyHold.elementHeld') : t('privacyHold.elementNotHeld');
            return '<tr data-element="' + esc(element.dataElementId) + '" data-held="' + (element.dataElementHoldActive ? '1' : '0') + '">'
                + '<td class="text-break"><code>' + esc(element.dataElementId) + '</code></td>'
                + '<td>' + esc(count) + '</td>'
                + '<td>' + esc(held) + '</td>'
                + '<td>'
                + '<div class="form-check"><input class="form-check-input" type="radio" name="' + name + '" id="' + name + '-add" value="' + CHOICE_ADD + '">'
                + '<label class="form-check-label" for="' + name + '-add">' + esc(t('privacyHold.choice.' + CHOICE_ADD)) + '</label></div>'
                + '<div class="form-check"><input class="form-check-input" type="radio" name="' + name + '" id="' + name + '-accept" value="' + CHOICE_ACCEPT + '">'
                + '<label class="form-check-label" for="' + name + '-accept">' + esc(t('privacyHold.choice.' + CHOICE_ACCEPT)) + '</label></div>'
                + '</td></tr>';
        });
        $('#hold-unattributable-body').html(rows.length ? rows.join('')
            : '<tr><td colspan="4" class="text-secondary">' + esc(t('privacyHold.unattributable.none')) + '</td></tr>');
        $('#hold-submit').prop('disabled', !overview.startReady || allowed.length === 0);
        toggleTargetFields();
    }

    function activeHolds() {
        return (overview && overview.holds ? overview.holds : []).filter(function (hold) {
            return hold.status === 'ACTIVE';
        });
    }

    function partitionOf(hold) {
        return hold.targetType === 'DATA_ELEMENT' ? DATA_ELEMENT_PARTITION_PREFIX + hold.dataElementId : hold.subjectRef;
    }

    function renderRelease() {
        var ready = !!overview.releaseReady;
        var holds = activeHolds();
        $('#hold-release-closed').toggleClass('d-none', ready);
        var select = $('#hold-release-target').empty();
        if (holds.length === 0) {
            select.append($('<option>').val('').text(t('privacyHold.release.none')));
        }
        holds.forEach(function (hold) {
            var target = hold.targetType === 'SUBJECT' ? hold.subjectRef : hold.dataElementId;
            select.append($('<option>').val(hold.holdId).text(hold.holdId + ' (' + target + ')'));
        });
        $('#privacy-hold-release-form :input').prop('disabled', !ready);
        $('#hold-release-submit').prop('disabled', !ready || holds.length === 0);
        renderBinding();
    }

    function renderBinding() {
        var holdId = $('#hold-release-target').val();
        var hold = activeHolds().filter(function (h) {
            return h.holdId === holdId;
        })[0];
        $('#hold-release-binding-hold').text(hold ? hold.holdId : '');
        $('#hold-release-binding-subject').text(hold ? partitionOf(hold) : '');
        $('#hold-release-binding-revision').text(hold ? hold.journalRevision : '');
        $('#hold-release-binding-hash').text(hold ? (hold.journalEventHash || '') : '');
    }

    function postRelease(holdId, payload) {
        return new Promise(function (resolve, reject) {
            $.ajax({
                url: '/api/privacy/holds/' + encodeURIComponent(holdId) + '/release',
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify(payload),
                headers: SES.csrf.header(),
                success: function (res) {
                    if (res.code === 200) {
                        resolve(res.data);
                    } else {
                        reject(res.message || t('privacyHold.error.load'));
                    }
                },
                error: function (xhr) {
                    reject(errorMessage(xhr));
                }
            });
        });
    }

    function submitRelease(event) {
        event.preventDefault();
        var holdId = $('#hold-release-target').val();
        var base = {
            reasonCode: $.trim($('#hold-release-reason-code').val()),
            reasonText: $('#hold-release-reason-text').val(),
            ticketRef: $.trim($('#hold-release-ticket-ref').val())
        };
        if (!holdId || !base.reasonCode || !$.trim(base.reasonText) || !base.ticketRef) {
            SES.toast.error(t('privacyHold.validation.required'));
            return;
        }
        var proof;
        try {
            proof = JSON.parse($('#hold-release-proof').val());
        } catch (e) {
            proof = null;
        }
        if (!proof || typeof proof !== 'object' || !proof.binding || !proof.issuer || !proof.approver) {
            SES.toast.error(t('privacyHold.validation.proof'));
            return;
        }
        var payload = $.extend({}, base, {binding: proof.binding, issuer: proof.issuer, approver: proof.approver});
        Swal.fire({
            title: t('privacyHold.release.confirm.title'),
            text: t('privacyHold.release.confirm.text'),
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: t('privacyHold.release.confirm.ok'),
            cancelButtonText: t('common.cancel')
        }).then(function (result) {
            if (!result.isConfirmed) {
                return;
            }
            $('#hold-release-submit').prop('disabled', true);
            postRelease(holdId, payload).then(function (data) {
                SES.toast.success(t(data && data.replayed ? 'privacyHold.toast.releaseReplayed' : 'privacyHold.toast.released'));
                $('#privacy-hold-release-form')[0].reset();
            }).catch(function (message) {
                SES.toast.error(message);
            }).then(function () {
                load();
            });
        });
    }

    function renderNotes() {
        var related = (overview.relatedElements || []).map(function (pair) {
            return '<li class="text-break">' + esc(t(pair.noteKey)) + ' (<code>' + esc(pair.dataElementId)
                + '</code> / <code>' + esc(pair.relatedDataElementId) + '</code>)</li>';
        });
        $('#hold-related-list').html(related.join(''));
        var unclassified = (overview.unclassifiedElements || []).map(function (id) {
            return '<code class="me-2">' + esc(id) + '</code>';
        });
        $('#hold-unclassified-list').html(unclassified.length ? unclassified.join(' ')
            : '<span class="text-secondary">' + esc(t('privacyHold.unclassified.none')) + '</span>');
    }

    function toggleTargetFields() {
        var subject = $('#hold-target-type').val() === 'SUBJECT';
        $('.hold-subject-field').toggleClass('d-none', !subject);
        $('.hold-element-field').toggleClass('d-none', subject);
    }

    function collectChoices() {
        var choices = [];
        var complete = true;
        $('#hold-unattributable-body tr[data-element]').each(function () {
            var row = $(this);
            var value = row.find('input[type="radio"]:checked').val();
            if (!value) {
                complete = false;
                return;
            }
            choices.push({dataElementId: row.data('element'), choice: value, held: row.data('held') === 1});
        });
        return complete ? choices : null;
    }

    function postHold(payload) {
        return new Promise(function (resolve, reject) {
            $.ajax({
                url: '/api/privacy/holds',
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify(payload),
                headers: SES.csrf.header(),
                success: function (res) {
                    if (res.code === 200) {
                        resolve(res.data);
                    } else {
                        reject(res.message || t('privacyHold.error.load'));
                    }
                },
                error: function (xhr) {
                    reject(errorMessage(xhr));
                }
            });
        });
    }

    function submit(event) {
        event.preventDefault();
        var targetType = $('#hold-target-type').val();
        var base = {
            reasonCode: $.trim($('#hold-reason-code').val()),
            reasonText: $('#hold-reason-text').val(),
            ticketRef: $.trim($('#hold-ticket-ref').val())
        };
        if (!targetType || !base.reasonCode || !$.trim(base.reasonText) || !base.ticketRef) {
            SES.toast.error(t('privacyHold.validation.required'));
            return;
        }
        var request;
        var elementsToStart = [];
        if (targetType === 'SUBJECT') {
            var subjectId = $.trim($('#hold-subject-id').val());
            if (!/^[1-9][0-9]{0,17}$/.test(subjectId)) {
                SES.toast.error(t('privacyHold.validation.required'));
                return;
            }
            var choices = collectChoices();
            if (choices === null) {
                SES.toast.error(t('privacyHold.validation.choices'));
                return;
            }
            elementsToStart = choices.filter(function (c) {
                return c.choice === CHOICE_ADD && !c.held;
            }).map(function (c) {
                return c.dataElementId;
            });
            request = $.extend({}, base, {
                targetType: 'SUBJECT',
                subjectRef: $('#hold-subject-type').val() + ':' + subjectId,
                unattributableChoices: choices.map(function (c) {
                    return {dataElementId: c.dataElementId, choice: c.choice};
                })
            });
        } else {
            if (!$('#hold-acknowledge').is(':checked')) {
                SES.toast.error(t('privacyHold.validation.acknowledge'));
                return;
            }
            request = $.extend({}, base, {
                targetType: 'DATA_ELEMENT',
                dataElementId: $('#hold-data-element').val(),
                acknowledgePurgeStop: true
            });
        }
        var stopped = targetType === 'DATA_ELEMENT' ? [request.dataElementId] : elementsToStart;
        var text = t('privacyHold.confirm.text');
        if (stopped.length > 0) {
            text += '\n' + t('privacyHold.confirm.elements', stopped.join(', '));
        }
        Swal.fire({
            title: t('privacyHold.confirm.title'),
            text: text,
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: t('privacyHold.confirm.ok'),
            cancelButtonText: t('common.cancel')
        }).then(function (result) {
            if (!result.isConfirmed) {
                return;
            }
            $('#hold-submit').prop('disabled', true);
            var chain = Promise.resolve();
            elementsToStart.forEach(function (elementId) {
                chain = chain.then(function () {
                    return postHold($.extend({}, base, {
                        targetType: 'DATA_ELEMENT',
                        dataElementId: elementId,
                        acknowledgePurgeStop: true
                    })).then(function () {
                        SES.toast.success(t('privacyHold.toast.elementStarted', elementId));
                    });
                });
            });
            chain.then(function () {
                return postHold(request);
            }).then(function () {
                SES.toast.success(t('privacyHold.toast.started'));
                $('#privacy-hold-form')[0].reset();
            }).catch(function (message) {
                SES.toast.error(message);
            }).then(function () {
                load();
            });
        });
    }

    $(function () {
        $('#hold-target-type').on('change', toggleTargetFields);
        $('#privacy-hold-form').on('submit', submit);
        $('#hold-release-target').on('change', renderBinding);
        $('#privacy-hold-release-form').on('submit', submitRelease);
        $('#privacy-hold-reload').on('click', load);
        load();
    });
})();
