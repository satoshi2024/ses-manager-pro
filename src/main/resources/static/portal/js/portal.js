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
        }
    };

    window.PortalAuth = PortalAuth;
})(window, jQuery);
