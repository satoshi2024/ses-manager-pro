/**
 * 外部顧客/BPポータル共通JS。
 * portal専用CSRF（XSRF-TOKEN-PORTAL → X-XSRF-TOKEN-PORTAL）と
 * 認証フロー（login / MFA設定 / 招待受諾 / 規約同意 / logout）を管理する。
 * 内部common.js（SES）とは別物であり、内部のsession expiry処理に依存しない。
 */
(function (window, $) {
    'use strict';

    const PORTAL_CSRF_COOKIE = 'XSRF-TOKEN-PORTAL';
    const PORTAL_CSRF_HEADER = 'X-XSRF-TOKEN-PORTAL';
    const TERMS_REQUIRED_CODE = 'TERMS_REQUIRED';

    function readCookie(name) {
        const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
        return match ? decodeURIComponent(match[1]) : null;
    }

    function csrfHeader() {
        const token = readCookie(PORTAL_CSRF_COOKIE);
        const headers = {};
        if (token) {
            headers[PORTAL_CSRF_HEADER] = token;
        }
        return headers;
    }

    function postJson(url, data) {
        return $.ajax({
            url: url,
            method: 'POST',
            contentType: 'application/json',
            headers: csrfHeader(),
            data: data == null ? undefined : JSON.stringify(data)
        });
    }

    function showError(elementId, message) {
        const el = $('#' + elementId);
        el.text(message).removeClass('d-none');
    }

    function hideError(elementId) {
        $('#' + elementId).addClass('d-none').text('');
    }

    function handleApiFailure(xhr, elementId) {
        let message = '通信に失敗しました。しばらくしてから再度お試しください。';
        if (xhr && xhr.responseJSON) {
            if (xhr.responseJSON.message) {
                message = xhr.responseJSON.message;
            } else if (xhr.responseJSON.code === 429) {
                message = 'リクエストが多すぎます。しばらくしてから再度お試しください。';
            }
        }
        showError(elementId, message);
    }

    /** ログイン完了後の遷移先（規約同意待ちは同意画面へ） */
    function redirectAfterLogin(result) {
        if (result && result.termsPending) {
            window.location.href = '/portal/terms';
        } else {
            window.location.href = '/portal';
        }
    }

    const PortalAuth = {

        /** ログイン画面: password → MFAコード → MFA初期設定 → 遷移 */
        initLoginPage: function () {
            const $form = $('#loginForm');
            const $mfaGroup = $('#mfaCodeGroup');
            const $mfaSetupSection = $('#mfaSetupSection');
            const $loginSection = $('#loginSection');
            let pendingSecret = null;
            let pendingEmail = null;

            $form.on('submit', function (event) {
                event.preventDefault();
                hideError('loginError');
                const email = $('#loginEmail').val().trim();
                const password = $('#loginPassword').val();
                const mfaCode = $('#loginMfaCode').val().trim();
                if (!email || !password) {
                    showError('loginError', 'メールアドレスとパスワードを入力してください');
                    return;
                }
                $('#loginButton').prop('disabled', true);
                postJson('/api/portal/auth/login', {email: email, password: password, mfaCode: mfaCode})
                    .done(function (res) {
                        if (!res || res.code !== 200) {
                            showError('loginError', res ? res.message : 'ログインに失敗しました');
                            return;
                        }
                        const result = res.data;
                        if (result.status === 'MFA_SETUP') {
                            pendingSecret = result.mfaSetup;
                            pendingEmail = email;
                            showMfaSetup(result.mfaSetup);
                        } else if (result.status === 'MFA_REQUIRED') {
                            $mfaGroup.removeClass('d-none');
                            $('#loginMfaCode').focus();
                        } else if (result.status === 'OK') {
                            redirectAfterLogin(result);
                        }
                    })
                    .fail(function (xhr) {
                        handleApiFailure(xhr, 'loginError');
                    })
                    .always(function () {
                        $('#loginButton').prop('disabled', false);
                    });
            });

            function showMfaSetup(setup) {
                $loginSection.addClass('d-none');
                $mfaSetupSection.removeClass('d-none');
                $('#mfaSecretLabel').text('Secret: ' + setup.secret);
                renderQr(setup.otpauthUri);
                $('#mfaSetupCode').val('').focus();
            }

            function renderQr(otpauthUri) {
                // QR描画は外部ライブラリへ依存しない（秘密鍵の文字列出力でも登録可能）
                $('#mfaQr').text(otpauthUri).addClass('portal-qr-text');
            }

            $('#mfaSetupButton').on('click', function () {
                hideError('loginError');
                const code = $('#mfaSetupCode').val().trim();
                if (!code || !pendingEmail) {
                    return;
                }
                $(this).prop('disabled', true);
                postJson('/api/portal/auth/mfa/complete?email=' + encodeURIComponent(pendingEmail)
                    + '&code=' + encodeURIComponent(code), null)
                    .done(function (res) {
                        if (!res || res.code !== 200) {
                            showError('loginError', res ? res.message : 'MFAの有効化に失敗しました');
                            return;
                        }
                        $('#mfaRecoveryCode').text(res.data.recoveryCode);
                        $('#mfaRecovery').removeClass('d-none');
                        $('#mfaSetupButton').text('設定完了');
                        setTimeout(function () {
                            window.location.href = '/portal';
                        }, 4000);
                    })
                    .fail(function (xhr) {
                        handleApiFailure(xhr, 'loginError');
                    })
                    .always(function () {
                        $('#mfaSetupButton').prop('disabled', false);
                    });
            });

            $('#mfaSetupBack').on('click', function () {
                $mfaSetupSection.addClass('d-none');
                $loginSection.removeClass('d-none');
            });
        },

        /** 招待受諾画面 */
        initAcceptPage: function () {
            $('#acceptForm').on('submit', function (event) {
                event.preventDefault();
                hideError('acceptError');
                const email = $('#acceptEmail').val().trim();
                const displayName = $('#acceptDisplayName').val().trim();
                const password = $('#acceptPassword').val();
                const confirm = $('#acceptPasswordConfirm').val();
                if (!email || !displayName || !password || !confirm) {
                    showError('acceptError', 'すべての項目を入力してください');
                    return;
                }
                if (password !== confirm) {
                    showError('acceptError', 'パスワード（確認）が一致しません');
                    return;
                }
                if (password.length < 8) {
                    showError('acceptError', 'パスワードは8文字以上で入力してください');
                    return;
                }
                const params = new URLSearchParams(window.location.search);
                const token = params.get('token') || '';
                if (!token) {
                    showError('acceptError', '招待URLが正しくありません。招待メールのリンクから開いてください。');
                    return;
                }
                $('#acceptButton').prop('disabled', true);
                postJson('/api/portal/auth/accept-invitation', {
                    token: token,
                    email: email,
                    displayName: displayName,
                    password: password
                }).done(function (res) {
                    if (!res || res.code !== 200) {
                        showError('acceptError', res ? res.message : '受諾に失敗しました');
                        return;
                    }
                    window.location.href = '/portal/login?accepted=1';
                }).fail(function (xhr) {
                    handleApiFailure(xhr, 'acceptError');
                }).always(function () {
                    $('#acceptButton').prop('disabled', false);
                });
            });
        },

        /** 規約同意画面 */
        initTermsPage: function (options) {
            $('#termsAgreeButton').on('click', function () {
                hideError('termsError');
                $(this).prop('disabled', true);
                postJson('/api/portal/auth/consent', {termsVersion: options.termsVersion})
                    .done(function (res) {
                        if (!res || res.code !== 200) {
                            showError('termsError', res ? res.message : '同意の記録に失敗しました');
                            return;
                        }
                        window.location.href = '/portal';
                    })
                    .fail(function (xhr) {
                        handleApiFailure(xhr, 'termsError');
                    })
                    .always(function () {
                        $('#termsAgreeButton').prop('disabled', false);
                    });
            });
            $('#termsDeclineButton').on('click', function () {
                postJson('/api/portal/auth/logout', null).always(function () {
                    window.location.href = '/portal/login';
                });
            });
        },

        /** ログイン後のindex画面 */
        initIndexPage: function () {
            $.get('/api/portal/auth/me').done(function (res) {
                if (!res || res.code !== 200) {
                    window.location.href = '/portal/login';
                    return;
                }
                const me = res.data;
                $('#portalHeaderUser').text(me.displayName + ' (' + (me.orgType === 'BP' ? 'BP' : '顧客') + ')');
                $('#portalIndexOrg').text(me.email);
                if (me.termsPending) {
                    $('#termsPendingAlert').removeClass('d-none');
                }
            }).fail(function () {
                window.location.href = '/portal/login';
            });

            $('#logoutButton').on('click', function () {
                postJson('/api/portal/auth/logout', null).always(function () {
                    window.location.href = '/portal/login';
                });
            });
        },

        /** 規約未同意時にAPIから403 TERMS_REQUIREDを受けた場合の共通処理（AJAX失敗時） */
        handleTermsRequired: function (xhr) {
            if (xhr && xhr.responseJSON && xhr.responseJSON.message === TERMS_REQUIRED_CODE) {
                window.location.href = '/portal/terms';
                return true;
            }
            return false;
        },

        /** portal API呼出し共通ヘルパー（将来の画面で使用） */
        request: function (options) {
            options.headers = Object.assign({}, csrfHeader(), options.headers || {});
            return $.ajax(options);
        },

        /** 顧客ポータル画面（契約/検収/請求/見積/注文請） */
        initCustomerPage: function () {
            const self = this;
            let currentTab = 'acceptances';

            function escapeHtml(value) {
                return String(value == null ? '' : value)
                    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
            }

            function money(value) {
                if (value == null) {
                    return '-';
                }
                return Number(value).toLocaleString('ja-JP') + ' 円';
            }

            function showError(message) {
                $('#portalError').text(message).removeClass('d-none');
            }

            function hideError() {
                $('#portalError').addClass('d-none').text('');
            }

            function loadHeader() {
                $.get('/api/portal/auth/me').done(function (res) {
                    if (!res || res.code !== 200) {
                        window.location.href = '/portal/login';
                        return;
                    }
                    $('#portalHeaderUser').text(res.data.displayName + '（顧客）');
                    if (res.data.termsPending) {
                        window.location.href = '/portal/terms';
                    }
                }).fail(function () {
                    window.location.href = '/portal/login';
                });
            }

            $('#logoutButton').on('click', function () {
                self.request({url: '/api/portal/auth/logout', method: 'POST'}).always(function () {
                    window.location.href = '/portal/login';
                });
            });

            $('.portal-tab').on('click', function () {
                currentTab = $(this).data('tab');
                $('.portal-tab').removeClass('active');
                $(this).addClass('active');
                $('.portal-tab-panel').addClass('d-none');
                $('#tab-' + currentTab).removeClass('d-none');
                hideError();
                loadTab(currentTab);
            });

            function loadTab(tab) {
                const loaders = {
                    quotations: loadQuotations,
                    'sales-orders': loadSalesOrders,
                    contracts: loadContracts,
                    acceptances: loadAcceptances,
                    invoices: loadInvoices
                };
                (loaders[tab] || function () {} )();
            }

            // ===== 見積 =====
            function loadQuotations() {
                $.get('/api/portal/customer/quotations?current=1&size=100').done(function (res) {
                    if (res.code !== 200) { showError(res.message); return; }
                    const rows = (res.data.records || []).map(function (q) {
                        return '<div class="portal-card portal-row">'
                            + '<div class="portal-row-title">' + escapeHtml(q.title)
                            + ' <span class="portal-badge">' + escapeHtml(q.status) + '</span></div>'
                            + '<div class="portal-muted">' + escapeHtml(q.quotationNo || '')
                            + ' / ' + money(q.unitPrice) + '</div>'
                            + '<div class="portal-row-actions">'
                            + '<a class="btn btn-sm btn-outline-primary" href="/api/portal/customer/quotations/'
                            + q.id + '/download" target="_blank" rel="noopener">PDF</a>'
                            + '</div></div>';
                    }).join('');
                    $('#quotationList').html(rows || '<p class="portal-muted">表示できる見積はありません</p>');
                }).fail(function (xhr) { if (!self.handleTermsRequired(xhr)) showError('読み込みに失敗しました'); });
            }

            // ===== 注文請 =====
            function loadSalesOrders() {
                $.get('/api/portal/customer/sales-orders?current=1&size=100').done(function (res) {
                    if (res.code !== 200) { showError(res.message); return; }
                    const rows = (res.data.records || []).map(function (o) {
                        const pdf = o.acknowledgementAvailable
                            ? '<a class="btn btn-sm btn-outline-primary" href="/api/portal/customer/sales-orders/'
                                + o.id + '/acknowledgement/download" target="_blank" rel="noopener">注文請PDF</a>' : '';
                        return '<div class="portal-card portal-row">'
                            + '<div class="portal-row-title">' + escapeHtml(o.orderNo || '')
                            + ' <span class="portal-badge">' + escapeHtml(o.status) + '</span></div>'
                            + '<div class="portal-muted">' + escapeHtml(o.customerPoNo || '') + ' / '
                            + money(o.totalAmountSnapshot) + '</div>'
                            + '<div class="portal-row-actions">' + pdf + '</div></div>';
                    }).join('');
                    $('#salesOrderList').html(rows || '<p class="portal-muted">表示できる注文請はありません</p>');
                }).fail(function (xhr) { if (!self.handleTermsRequired(xhr)) showError('読み込みに失敗しました'); });
            }

            // ===== 契約 =====
            function loadContracts() {
                $.get('/api/portal/customer/contracts?current=1&size=100').done(function (res) {
                    if (res.code !== 200) { showError(res.message); return; }
                    const rows = (res.data.records || []).map(function (c) {
                        const doc = c.contractDocumentAvailable
                            ? '<a class="btn btn-sm btn-outline-primary" href="/api/portal/customer/contracts/'
                                + c.id + '/document/download" target="_blank" rel="noopener">契約書PDF</a>' : '';
                        return '<div class="portal-card portal-row">'
                            + '<div class="portal-row-title">' + escapeHtml(c.contractNo || '')
                            + ' <span class="portal-badge">' + escapeHtml(c.status) + '</span></div>'
                            + '<div class="portal-muted">' + escapeHtml(c.engineerName || '') + ' / '
                            + escapeHtml(c.projectName || '') + '</div>'
                            + '<div class="portal-muted">' + escapeHtml(c.jobDescription || '') + '</div>'
                            + '<div class="portal-muted">電子署名: ' + escapeHtml(c.esignStatus || '未実施') + '</div>'
                            + '<div class="portal-row-actions">' + doc + '</div></div>';
                    }).join('');
                    $('#contractList').html(rows || '<p class="portal-muted">表示できる契約はありません</p>');
                }).fail(function (xhr) { if (!self.handleTermsRequired(xhr)) showError('読み込みに失敗しました'); });
            }

            // ===== 検収 =====
            function loadAcceptances() {
                $.get('/api/portal/customer/acceptances?current=1&size=100').done(function (res) {
                    if (res.code !== 200) { showError(res.message); return; }
                    const rows = (res.data.records || []).map(function (a) {
                        const doc = a.documentAvailable
                            ? '<a class="btn btn-sm btn-outline-primary" href="/api/portal/customer/acceptances/'
                                + a.id + '/document/download" target="_blank" rel="noopener">検収書PDF</a>' : '';
                        const operate = a.status === '提出済'
                            ? '<button type="button" class="btn btn-sm btn-success" data-acceptance-id="' + a.id
                                + '" data-month="' + escapeHtml(a.workMonth) + '">検収</button>'
                                + '<button type="button" class="btn btn-sm btn-warning" data-reject-id="' + a.id
                                + '" data-month="' + escapeHtml(a.workMonth) + '">差戻し</button>' : '';
                        return '<div class="portal-card portal-row">'
                            + '<div class="portal-row-title">' + escapeHtml(a.workMonth) + ' '
                            + escapeHtml(a.contractNo || '')
                            + ' <span class="portal-badge">' + escapeHtml(a.status) + '</span></div>'
                            + '<div class="portal-muted">' + escapeHtml(a.engineerName || '') + ' / '
                            + money(a.amountSnapshot) + '</div>'
                            + (a.rejectComment ? '<div class="portal-reject-comment">差戻し理由: '
                                + escapeHtml(a.rejectComment) + '</div>' : '')
                            + '<div class="portal-row-actions">' + operate + doc + '</div></div>';
                    }).join('');
                    $('#acceptanceList').html(rows || '<p class="portal-muted">表示できる検収はありません</p>');
                    $('[data-acceptance-id]').on('click', function () {
                        openAcceptanceModal($(this).data('acceptance-id'), $(this).data('month'), 'accept');
                    });
                    $('[data-reject-id]').on('click', function () {
                        openAcceptanceModal($(this).data('reject-id'), $(this).data('month'), 'reject');
                    });
                }).fail(function (xhr) { if (!self.handleTermsRequired(xhr)) showError('読み込みに失敗しました'); });
            }

            function openAcceptanceModal(id, month, mode) {
                $('#acceptanceModalError').addClass('d-none');
                $('#rejectCommentGroup').toggleClass('d-none', mode !== 'reject');
                $('#acceptanceModalTitle').text('検収 ' + month + '（' + id + '）');
                $('#acceptButton').prop('disabled', mode !== 'accept');
                $('#rejectButton').prop('disabled', mode !== 'reject');
                bootstrap.Modal.getOrCreateInstance('#acceptanceModal').show();
                $('#acceptButton').off('click').on('click', function () {
                    submitAcceptance(id, 'accept', null);
                });
                $('#rejectButton').off('click').on('click', function () {
                    const comment = $('#rejectComment').val().trim();
                    if (!comment) {
                        $('#acceptanceModalError').text('差戻し理由を入力してください').removeClass('d-none');
                        return;
                    }
                    submitAcceptance(id, 'reject', comment);
                });
            }

            function submitAcceptance(id, mode, comment) {
                const url = '/api/portal/customer/acceptances/' + id + '/' + mode;
                const data = mode === 'reject' ? {comment: comment} : {};
                self.request({url: url, method: 'POST', contentType: 'application/json', data: JSON.stringify(data)})
                    .done(function (res) {
                        if (res.code !== 200) {
                            $('#acceptanceModalError').text(res.message).removeClass('d-none');
                            return;
                        }
                        bootstrap.Modal.getInstance('#acceptanceModal').hide();
                        loadAcceptances();
                    })
                    .fail(function (xhr) {
                        let message = '操作に失敗しました';
                        if (xhr.responseJSON && xhr.responseJSON.message) {
                            message = xhr.responseJSON.message;
                        }
                        $('#acceptanceModalError').text(message).removeClass('d-none');
                    });
            }

            // ===== 請求 =====
            function loadInvoices() {
                $.get('/api/portal/customer/invoices?current=1&size=100').done(function (res) {
                    if (res.code !== 200) { showError(res.message); return; }
                    const rows = (res.data.records || []).map(function (inv) {
                        const received = inv.receivedConfirmedAt ? '受領確認済み' : '未確認';
                        return '<div class="portal-card portal-row">'
                            + '<div class="portal-row-title">' + escapeHtml(inv.invoiceNo || '')
                            + ' <span class="portal-badge">' + escapeHtml(inv.status) + '</span></div>'
                            + '<div class="portal-muted">' + escapeHtml(inv.billingMonth || '') + ' / '
                            + money(inv.total) + ' / ' + received + '</div>'
                            + '<div class="portal-row-actions">'
                            + '<a class="btn btn-sm btn-outline-primary" href="/api/portal/customer/invoices/'
                            + inv.id + '/download" target="_blank" rel="noopener">請求書PDF</a>'
                            + '<button type="button" class="btn btn-sm btn-outline-secondary" data-invoice-id="'
                            + inv.id + '">受領確認・登録</button></div></div>';
                    }).join('');
                    $('#invoiceList').html(rows || '<p class="portal-muted">表示できる請求はありません</p>');
                    $('[data-invoice-id]').on('click', function () {
                        openInvoiceModal($(this).data('invoice-id'));
                    });
                }).fail(function (xhr) { if (!self.handleTermsRequired(xhr)) showError('読み込みに失敗しました'); });
            }

            function openInvoiceModal(id) {
                $('#invoiceModalError').addClass('d-none');
                $.get('/api/portal/customer/invoices/' + id).done(function (res) {
                    if (res.code !== 200) { showError(res.message); return; }
                    const inv = res.data;
                    $('#invoiceModalTitle').text('請求 ' + (inv.invoiceNo || id));
                    $('#invoiceModalBody').html(
                        '<dt>請求月</dt><dd>' + escapeHtml(inv.billingMonth || '-') + '</dd>'
                        + '<dt>合計</dt><dd>' + money(inv.total) + '</dd>'
                        + '<dt>状態</dt><dd>' + escapeHtml(inv.status) + '</dd>'
                        + '<dt>支払期日</dt><dd>' + escapeHtml(inv.dueDate || '-') + '</dd>');
                    $('#invoiceReceived').prop('checked', !!inv.receivedConfirmedAt);
                    $('#invoicePaymentDate').val(inv.paymentExpectedDate || '');
                    $('#invoiceInquiry').val(inv.portalInquiry || '');
                    bootstrap.Modal.getOrCreateInstance('#invoiceModal').show();
                });
                $('#invoiceSaveButton').off('click').on('click', function () {
                    saveInvoice(id);
                });
            }

            function saveInvoice(id) {
                const payload = {
                    receivedConfirmed: $('#invoiceReceived').is(':checked'),
                    paymentExpectedDate: $('#invoicePaymentDate').val() || null,
                    inquiry: $('#invoiceInquiry').val().trim() || null
                };
                self.request({url: '/api/portal/customer/invoices/' + id + '/register', method: 'POST',
                        contentType: 'application/json', data: JSON.stringify(payload)})
                    .done(function (res) {
                        if (res.code !== 200) {
                            $('#invoiceModalError').text(res.message).removeClass('d-none');
                            return;
                        }
                        bootstrap.Modal.getInstance('#invoiceModal').hide();
                        loadInvoices();
                    })
                    .fail(function (xhr) {
                        let message = '登録に失敗しました';
                        if (xhr.responseJSON && xhr.responseJSON.message) {
                            message = xhr.responseJSON.message;
                        }
                        $('#invoiceModalError').text(message).removeClass('d-none');
                    });
            }

            loadHeader();
            loadTab(currentTab);
        },

        /** BPポータル画面（発注・実績/空き要員/口座変更） */
        initBpPage: function () {
            const self = this;
            let currentTab = 'payments';

            function escapeHtml(value) {
                return String(value == null ? '' : value)
                    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
            }

            function money(value) {
                if (value == null) {
                    return '-';
                }
                return Number(value).toLocaleString('ja-JP') + ' 円';
            }

            function showError(message) {
                $('#portalError').text(message).removeClass('d-none');
                $('#portalSuccess').addClass('d-none');
            }

            function showSuccess(message) {
                $('#portalSuccess').text(message).removeClass('d-none');
                $('#portalError').addClass('d-none');
            }

            function hideAlerts() {
                $('#portalError').addClass('d-none').text('');
                $('#portalSuccess').addClass('d-none').text('');
            }

            function loadHeader() {
                $.get('/api/portal/auth/me').done(function (res) {
                    if (!res || res.code !== 200) {
                        window.location.href = '/portal/login';
                        return;
                    }
                    $('#portalHeaderUser').text(res.data.displayName + '（BP）');
                    if (res.data.termsPending) {
                        window.location.href = '/portal/terms';
                    }
                }).fail(function () {
                    window.location.href = '/portal/login';
                });
            }

            $('#logoutButton').on('click', function () {
                self.request({url: '/api/portal/auth/logout', method: 'POST'}).always(function () {
                    window.location.href = '/portal/login';
                });
            });

            $('.portal-tab').on('click', function () {
                currentTab = $(this).data('tab');
                $('.portal-tab').removeClass('active');
                $(this).addClass('active');
                $('.portal-tab-panel').addClass('d-none');
                $('#tab-' + currentTab).removeClass('d-none');
                hideAlerts();
                if (currentTab === 'payments') { loadPayments(); }
                if (currentTab === 'availabilities') { loadAvailabilities(); }
                if (currentTab === 'bank') { loadBankAccounts(); }
            });

            // ===== 発注・実績 =====
            function loadPayments() {
                $.get('/api/portal/bp/payments?current=1&size=100').done(function (res) {
                    if (res.code !== 200) { showError(res.message); return; }
                    const rows = (res.data.records || []).map(function (p) {
                        const confirmBtn = (!p.receivedConfirmedAt && p.status === '未払')
                            ? '<button type="button" class="btn btn-sm btn-outline-success" data-confirm-id="'
                                + p.id + '">受領確認</button>' : '';
                        const submitBtn = '<button type="button" class="btn btn-sm btn-outline-primary" data-submit-id="'
                            + p.id + '" data-title="' + escapeHtml(p.workMonth || '') + '">提出</button>';
                        const paid = p.status === '支払済'
                            ? '<span class="portal-badge">支払済 ' + escapeHtml(p.paidDate || '') + '</span>'
                            : '<span class="portal-badge">' + escapeHtml(p.status || '') + '</span>';
                        return '<div class="portal-card portal-row">'
                            + '<div class="portal-row-title">' + escapeHtml(p.workMonth || '-') + ' '
                            + escapeHtml(p.contractNo || '') + ' ' + paid + '</div>'
                            + '<div class="portal-muted">要員: ' + escapeHtml(p.engineerName || '-')
                            + ' / 工数: ' + escapeHtml(p.actualHours == null ? '-' : p.actualHours) + 'h'
                            + ' / 金額: ' + money(p.amount) + '</div>'
                            + '<div class="portal-muted">支払予定: ' + escapeHtml(p.paymentScheduleDate || '未確定')
                            + ' / 提出物: ' + p.submissionCount + '件</div>'
                            + '<div class="portal-row-actions">' + confirmBtn + submitBtn + '</div></div>';
                    }).join('');
                    $('#paymentList').html(rows || '<p class="portal-muted">表示できる発注はありません</p>');
                    $('[data-confirm-id]').on('click', function () {
                        confirmReceipt($(this).data('confirm-id'));
                    });
                    $('[data-submit-id]').on('click', function () {
                        openSubmissionModal($(this).data('submit-id'), $(this).data('title'));
                    });
                }).fail(function (xhr) { if (!self.handleTermsRequired(xhr)) showError('読み込みに失敗しました'); });
            }

            function confirmReceipt(id) {
                self.request({url: '/api/portal/bp/payments/' + id + '/confirm-receipt', method: 'POST'})
                    .done(function (res) {
                        if (res.code !== 200) { showError(res.message); return; }
                        showSuccess('受領確認しました');
                        loadPayments();
                    })
                    .fail(function (xhr) {
                        showError(xhr.responseJSON && xhr.responseJSON.message
                            ? xhr.responseJSON.message : '受領確認に失敗しました');
                    });
            }

            function openSubmissionModal(id, title) {
                $('#submissionModalError').addClass('d-none');
                $('#submissionModalTitle').text('提出 ' + title);
                $('#submissionFile').val('');
                loadSubmissions(id);
                bootstrap.Modal.getOrCreateInstance('#submissionModal').show();
                $('#submissionUploadButton').off('click').on('click', function () {
                    uploadSubmission(id);
                });
            }

            function loadSubmissions(id) {
                $.get('/api/portal/bp/payments/' + id + '/submissions').done(function (res) {
                    if (res.code !== 200) { showError(res.message); return; }
                    const rows = (res.data || []).map(function (s) {
                        const dl = s.downloadable
                            ? '<a class="btn btn-sm btn-outline-primary" href="/api/portal/bp/payments/' + id
                                + '/submissions/' + s.documentId + '/download" target="_blank" rel="noopener">DL</a>' : '';
                        return '<div class="portal-card portal-row">'
                            + '<div class="portal-row-title">' + escapeHtml(s.originalName || s.title || '') + '</div>'
                            + '<div class="portal-row-actions">' + dl + '</div></div>';
                    }).join('');
                    $('#submissionList').html(rows || '<p class="portal-muted">提出物はありません</p>');
                }).fail(function () { showError('提出物の読み込みに失敗しました'); });
            }

            function uploadSubmission(id) {
                const fileInput = $('#submissionFile')[0];
                if (!fileInput.files || !fileInput.files[0]) {
                    $('#submissionModalError').text('ファイルを選択してください').removeClass('d-none');
                    return;
                }
                const formData = new FormData();
                formData.append('file', fileInput.files[0]);
                const csrf = readCookie('XSRF-TOKEN-PORTAL');
                $.ajax({
                    url: '/api/portal/bp/payments/' + id + '/submissions',
                    method: 'POST',
                    headers: csrf ? {'X-XSRF-TOKEN-PORTAL': csrf} : {},
                    data: formData,
                    processData: false,
                    contentType: false
                }).done(function (res) {
                    if (res.code !== 200) {
                        $('#submissionModalError').text(res.message).removeClass('d-none');
                        return;
                    }
                    loadSubmissions(id);
                    loadPayments();
                }).fail(function (xhr) {
                    let message = '提出に失敗しました';
                    if (xhr.responseJSON && xhr.responseJSON.message) {
                        message = xhr.responseJSON.message;
                    }
                    $('#submissionModalError').text(message).removeClass('d-none');
                });
            }

            // ===== 空き要員 =====
            function loadAvailabilities() {
                $.get('/api/portal/bp/availabilities?current=1&size=100').done(function (res) {
                    if (res.code !== 200) { showError(res.message); return; }
                    const rows = (res.data.records || []).map(function (a) {
                        const edit = (a.status === '未確認' || a.status === '却下')
                            ? '<button type="button" class="btn btn-sm btn-outline-secondary" data-edit-id="' + a.id
                                + '">編集</button>' : '';
                        const stop = a.status === '提案可能'
                            ? '<button type="button" class="btn btn-sm btn-outline-danger" data-stop-id="' + a.id
                                + '">停止</button>' : '';
                        return '<div class="portal-card portal-row">'
                            + '<div class="portal-row-title">' + escapeHtml(a.initialName)
                            + ' <span class="portal-badge">' + escapeHtml(a.status) + '</span></div>'
                            + '<div class="portal-muted">' + escapeHtml(a.skillsJson || '')
                            + ' / ' + money(a.unitPrice) + '</div>'
                            + '<div class="portal-row-actions">' + edit + stop + '</div></div>';
                    }).join('');
                    $('#availabilityList').html(rows || '<p class="portal-muted">登録した空き要員はありません</p>');
                    $('[data-edit-id]').on('click', function () {
                        openAvailabilityModal($(this).data('edit-id'));
                    });
                    $('[data-stop-id]').on('click', function () {
                        stopAvailability($(this).data('stop-id'));
                    });
                }).fail(function (xhr) { if (!self.handleTermsRequired(xhr)) showError('読み込みに失敗しました'); });
            }

            $('#availabilityAddButton').on('click', function () {
                openAvailabilityModal(null);
            });

            function openAvailabilityModal(id) {
                $('#availabilityModalError').addClass('d-none');
                $('#availabilityModalTitle').text(id ? '空き要員を編集' : '空き要員を登録');
                $('#avName').val('');
                $('#avSkills').val('');
                $('#avUnitPrice').val('');
                $('#avAvailableFrom').val('');
                $('#avExperience').val('');
                $('#avRemarks').val('');
                if (id) {
                    $.get('/api/portal/bp/availabilities?current=1&size=100').done(function (res) {
                        const item = (res.data.records || []).find(function (a) { return a.id === id; });
                        if (item) {
                            $('#avName').val(item.initialName || '');
                            $('#avSkills').val(item.skillsJson || '');
                            $('#avUnitPrice').val(item.unitPrice == null ? '' : item.unitPrice);
                            $('#avAvailableFrom').val(item.availableFrom || '');
                            $('#avExperience').val(item.experienceYears == null ? '' : item.experienceYears);
                            $('#avRemarks').val(item.remarks || '');
                        }
                    });
                }
                bootstrap.Modal.getOrCreateInstance('#availabilityModal').show();
                $('#availabilitySaveButton').off('click').on('click', function () {
                    saveAvailability(id);
                });
            }

            function saveAvailability(id) {
                const payload = {
                    initialName: $('#avName').val().trim(),
                    skillsJson: $('#avSkills').val().trim() || null,
                    unitPrice: $('#avUnitPrice').val() ? Number($('#avUnitPrice').val()) : null,
                    availableFrom: $('#avAvailableFrom').val() || null,
                    experienceYears: $('#avExperience').val() ? Number($('#avExperience').val()) : null,
                    remarks: $('#avRemarks').val().trim() || null
                };
                const url = id ? '/api/portal/bp/availabilities/' + id : '/api/portal/bp/availabilities';
                const method = id ? 'PUT' : 'POST';
                self.request({url: url, method: method, contentType: 'application/json', data: JSON.stringify(payload)})
                    .done(function (res) {
                        if (res.code !== 200) {
                            $('#availabilityModalError').text(res.message).removeClass('d-none');
                            return;
                        }
                        bootstrap.Modal.getInstance('#availabilityModal').hide();
                        loadAvailabilities();
                    })
                    .fail(function (xhr) {
                        $('#availabilityModalError').text(
                            xhr.responseJSON && xhr.responseJSON.message ? xhr.responseJSON.message : '保存に失敗しました'
                        ).removeClass('d-none');
                    });
            }

            function stopAvailability(id) {
                self.request({url: '/api/portal/bp/availabilities/' + id + '/stop', method: 'POST'})
                    .done(function (res) {
                        if (res.code !== 200) { showError(res.message); return; }
                        showSuccess('停止しました');
                        loadAvailabilities();
                    })
                    .fail(function (xhr) {
                        showError(xhr.responseJSON && xhr.responseJSON.message
                            ? xhr.responseJSON.message : '停止に失敗しました');
                    });
            }

            // ===== 口座変更 =====
            function loadBankAccounts() {
                $.get('/api/portal/bp/bank-accounts').done(function (res) {
                    if (res.code !== 200) { showError(res.message); return; }
                    const rows = (res.data || []).map(function (b) {
                        return '<div class="portal-row">'
                            + '<div class="portal-row-title">' + escapeHtml(b.maskedLabel || '')
                            + ' <span class="portal-badge">' + escapeHtml(b.approvalStatus || '') + '</span></div>'
                            + '<div class="portal-muted">' + escapeHtml(b.bankName || '') + ' '
                            + escapeHtml(b.branchName || '') + ' / ' + escapeHtml(b.accountHolder || '') + '</div>'
                            + '</div>';
                    }).join('');
                    $('#bankAccountList').html(rows
                        ? '<h3 class="portal-list-heading">登録済み口座</h3>' + rows
                        : '<p class="portal-muted">登録済み口座はありません</p>');
                }).fail(function () { showError('口座の読み込みに失敗しました'); });
            }

            $('#bankAccountForm').on('submit', function (event) {
                event.preventDefault();
                const payload = {
                    bankName: $('#bankName').val().trim(),
                    branchName: $('#branchName').val().trim(),
                    accountType: $('#accountType').val(),
                    accountNumber: $('#accountNumber').val().trim(),
                    accountHolder: $('#accountHolder').val().trim()
                };
                if (!payload.bankName || !payload.branchName || !payload.accountNumber || !payload.accountHolder) {
                    showError('すべての項目を入力してください');
                    return;
                }
                self.request({url: '/api/portal/bp/bank-accounts', method: 'POST',
                        contentType: 'application/json', data: JSON.stringify(payload)})
                    .done(function (res) {
                        if (res.code !== 200) { showError(res.message); return; }
                        showSuccess('口座変更を申請しました。内部承認後に反映されます。');
                        $('#bankAccountForm')[0].reset();
                        loadBankAccounts();
                    })
                    .fail(function (xhr) {
                        showError(xhr.responseJSON && xhr.responseJSON.message
                            ? xhr.responseJSON.message : '申請に失敗しました');
                    });
            });

            loadHeader();
            loadPayments();
        }
    };

    window.PortalAuth = PortalAuth;
})(window, jQuery);
