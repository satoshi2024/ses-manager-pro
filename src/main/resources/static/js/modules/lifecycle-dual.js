/**
 * 二者確認（DUAL_CONFIRMATION）の提出・確認・差戻し画面部品（NF01細粒度epoch計画 §5.10、W3-21b）。
 *
 * 社内の案件詳細（/lifecycle/{id}）と本人画面（/my/lifecycle/{id}）が共有する。モーダル #dualConfirmModal は
 * 子スイッチ nf01-dual-confirmation がONのときだけテンプレートが描画するため、モーダルが無ければ何もしない。
 *
 * - API の入口はモーダルの data-dual-api-base（社内 /api/lifecycle、本人 /api/my/lifecycle）。
 * - 確認・差戻しの欄（#dualDecisionSection）は社内テンプレートにだけある。本人画面には欄自体が無い。
 * - 提出は候補（A2/A6）のCLEAN版から選ばせ、版IDを必ず送る（Q-9。版の無い提出は送らない）。
 * - Idempotency-Key は操作ごとに生成し、結果が分からない失敗（通信エラー・5xx）の後の同じ内容の再送では同じkeyを使う。
 *   内容が変われば新しいkeyにし、成功した・4xxで拒否されたら破棄する。
 * - 送信中はボタンを無効にして二重送信を防ぐ。
 * - エラーはHTTP statusに関係なくサーバーの message（error.lifecycle.authorizationChanged 等のkeyを解決した文言）を表示する。
 *   共通の SES.api は403の本文を捨て、任意のheaderも付けられないため、ここでは専用の fetch を使う。
 */
SES.lifecycleDual = {
    COMMENT_MAX_LENGTH: 1000,

    /** 内容が変わる・成功するまで再利用する Idempotency-Key（{fingerprint, key}）。 */
    pending: null,
    taskId: null,
    view: null,
    candidates: [],
    busy: false,
    /** 提出・確認・差戻しが成功した後に呼ぶ（ページ側がタスク一覧を再読込する）。 */
    onChanged: null,

    modal: function() {
        return document.getElementById('dualConfirmModal');
    },

    /** モーダルが描画されている（子スイッチON）か。 */
    available: function() {
        return Boolean(this.modal());
    },

    apiBase: function() {
        const modal = this.modal();
        return (modal && modal.getAttribute('data-dual-api-base')) || '/api/lifecycle';
    },

    t: function(key) {
        return SES.i18n.t.apply(SES.i18n, arguments);
    },

    // ==========================================
    // 表示
    // ==========================================
    open: async function(buttonOrTaskId) {
        if (!this.available()) {
            return;
        }
        const taskId = buttonOrTaskId && buttonOrTaskId.dataset ? buttonOrTaskId.dataset.taskId : buttonOrTaskId;
        const taskName = buttonOrTaskId && buttonOrTaskId.dataset ? (buttonOrTaskId.dataset.taskName || '') : '';
        if (String(this.taskId) !== String(taskId)) {
            this.pending = null;
        }
        this.taskId = taskId;
        this.view = null;
        this.candidates = [];
        document.getElementById('dualTaskName').textContent = taskName;
        document.getElementById('dualSubmitComment').value = '';
        const decisionComment = document.getElementById('dualDecisionComment');
        if (decisionComment) decisionComment.value = '';
        this.renderLoading();
        bootstrap.Modal.getOrCreateInstance(this.modal()).show();
        await this.reload();
    },

    reload: async function() {
        try {
            const view = await this.request('GET', this.taskUrl(''));
            this.view = view || {};
            this.renderView();
            if (this.view.canSubmit) {
                await this.loadCandidates();
            }
        } catch (e) {
            console.error(e);
            this.view = null;
            this.renderError();
        }
    },

    taskUrl: function(suffix) {
        return `${this.apiBase()}/tasks/${encodeURIComponent(this.taskId)}/dual-confirmation${suffix}`;
    },

    renderLoading: function() {
        document.getElementById('dualSummary').innerHTML =
            `<div class="text-muted small">${SES.escapeHtml(this.t('common.loading'))}</div>`;
        this.toggleSection('dualSubmitSection', false);
        this.toggleSection('dualDecisionSection', false);
        this.setNotice('');
    },

    renderError: function() {
        document.getElementById('dualSummary').innerHTML =
            `<div class="text-danger small">${SES.escapeHtml(this.t('lifecycle.dual.error.load'))}</div>`;
        this.toggleSection('dualSubmitSection', false);
        this.toggleSection('dualDecisionSection', false);
        this.setNotice('');
    },

    toggleSection: function(id, visible) {
        const section = document.getElementById(id);
        if (section) section.hidden = !visible;
    },

    setNotice: function(text) {
        const notice = document.getElementById('dualNotice');
        if (!notice) return;
        notice.textContent = text || '';
        notice.hidden = !text;
    },

    stateBadge: function(state) {
        const classes = {
            'PENDING': 'bg-warning text-dark',
            'CONFIRMED': 'bg-success',
            'REJECTED': 'bg-danger'
        };
        const label = state ? this.t('lifecycle.dual.state.' + state) : this.t('lifecycle.dual.state.none');
        return `<span class="badge ${classes[state] || 'bg-secondary'}">${SES.escapeHtml(label)}</span>`;
    },

    formatDateTime: function(value) {
        return value ? String(value).replace('T', ' ').substring(0, 16) : '-';
    },

    /** 本文を返された場合だけ本文を、返されないが記入がある場合は「コメントあり」だけを出す（最小公開）。 */
    commentHtml: function(body, present) {
        if (body !== null && body !== undefined && String(body).trim() !== '') {
            return `<span class="text-theme" style="white-space: pre-wrap; word-break: break-word;">${SES.escapeHtml(String(body))}</span>`;
        }
        if (present) {
            return `<span class="badge bg-tertiary border border-theme text-theme">${SES.escapeHtml(this.t('lifecycle.dual.comment.present'))}</span>`
                + `<div class="form-text">${SES.escapeHtml(this.t('lifecycle.dual.comment.hidden'))}</div>`;
        }
        return `<span class="text-muted">${SES.escapeHtml(this.t('lifecycle.dual.comment.none'))}</span>`;
    },

    /**
     * 提出者・確認者の表示（RB-POSTLAUNCH D-2。以前は「ユーザーID N」だけだった）。名前はサーバーが表示範囲を判定して返す:
     * 社内画面では利用者の氏名、本人画面では本人の名前だけ。本人画面で名前が無い他の利用者は「社内の担当者」と出し、
     * 社内画面で名前が無い（削除済み等）場合だけ従来のID表示にする。
     */
    personLabel: function(name, userId) {
        if (name !== null && name !== undefined && String(name).trim() !== '') {
            return String(name);
        }
        if (userId === null || userId === undefined) {
            return '-';
        }
        return document.getElementById('dualDecisionSection')
            ? this.t('lifecycle.dual.label.userValue', [String(userId)])
            : this.t('lifecycle.dual.label.internalStaff');
    },

    row: function(labelKey, valueHtml) {
        return `<dt class="col-12 col-sm-4 text-muted fw-normal">${SES.escapeHtml(this.t(labelKey))}</dt>`
            + `<dd class="col-12 col-sm-8 mb-2">${valueHtml}</dd>`;
    },

    renderView: function() {
        const v = this.view || {};
        let html = '<dl class="row small mb-0">';
        let state = this.stateBadge(v.state);
        if (v.submissionRevision) {
            state += ` <span class="text-muted">${SES.escapeHtml(this.t('lifecycle.dual.label.revisionValue', [String(v.submissionRevision)]))}</span>`;
        }
        html += this.row('lifecycle.dual.label.state', state);
        if (v.submissionId) {
            const documentValue = v.documentId
                ? this.t('lifecycle.dual.label.documentValue', [String(v.documentId), String(v.documentVersionNo || '-')])
                : '-';
            html += this.row('lifecycle.dual.label.document', SES.escapeHtml(documentValue));
            html += this.row('lifecycle.dual.label.submittedAt', SES.escapeHtml(this.formatDateTime(v.submittedAt)));
            html += this.row('lifecycle.dual.label.submitter', SES.escapeHtml(this.personLabel(v.submitterName, v.submitterUserId)));
            html += this.row('lifecycle.dual.label.submissionComment',
                this.commentHtml(v.submissionComment, v.submissionCommentPresent));
        }
        if (v.decision) {
            html += this.row('lifecycle.dual.label.decision',
                SES.escapeHtml(this.t('lifecycle.dual.decision.' + v.decision)));
            html += this.row('lifecycle.dual.label.decidedAt', SES.escapeHtml(this.formatDateTime(v.decidedAt)));
            html += this.row('lifecycle.dual.label.confirmer', SES.escapeHtml(this.personLabel(v.confirmerName, v.confirmerUserId)));
            html += this.row('lifecycle.dual.label.decisionComment',
                this.commentHtml(v.decisionComment, v.decisionCommentPresent));
        }
        html += '</dl>';
        if (v.stale) {
            html += `<div class="alert alert-warning small mt-2 mb-0" role="alert">${SES.escapeHtml(this.t('lifecycle.dual.notice.stale'))}</div>`;
        }
        document.getElementById('dualSummary').innerHTML = html;
        this.renderControls();
    },

    /** 提出欄・確認欄の表示とボタンの有効/無効（送信中は全て無効）。 */
    renderControls: function() {
        const v = this.view || {};
        const decisionSection = document.getElementById('dualDecisionSection');
        this.toggleSection('dualSubmitSection', Boolean(v.canSubmit));
        this.toggleSection('dualDecisionSection', Boolean(decisionSection && v.canDecide));

        const notices = [];
        if (v.canSubmit && v.state === 'PENDING' && !v.stale) {
            notices.push(this.t('lifecycle.dual.notice.resubmitPending'));
        }
        if (v.canSubmit && v.state === 'REJECTED' && !v.stale) {
            notices.push(this.t('lifecycle.dual.notice.rejected'));
        }
        if (v.state === 'PENDING' && !v.stale && !v.canDecide) {
            notices.push(this.t(decisionSection ? 'lifecycle.dual.notice.decisionUnavailable'
                : 'lifecycle.dual.notice.selfNoDecision'));
        }
        if (!v.canSubmit && v.taskStatus === 'IN_PROGRESS' && v.state !== 'PENDING') {
            notices.push(this.t('lifecycle.dual.notice.cannotSubmit'));
        }
        this.setNotice(notices.join(' '));

        const select = document.getElementById('dualCandidateSelect');
        const submitButton = document.getElementById('dualSubmitButton');
        if (submitButton) {
            submitButton.disabled = this.busy || !select || select.disabled || !select.value;
        }
        ['dualConfirmButton', 'dualRejectButton'].forEach(id => {
            const button = document.getElementById(id);
            if (button) button.disabled = this.busy || !v.canDecide;
        });
    },

    // ==========================================
    // 証跡候補（CLEAN版のみ）
    // ==========================================
    loadCandidates: async function() {
        const select = document.getElementById('dualCandidateSelect');
        select.disabled = true;
        select.innerHTML = `<option value="">${SES.escapeHtml(this.t('common.loading'))}</option>`;
        try {
            this.candidates = (await this.request('GET', this.taskUrl('/candidates'))) || [];
        } catch (e) {
            console.error(e);
            this.candidates = [];
        }
        this.renderCandidates();
    },

    renderCandidates: function() {
        const select = document.getElementById('dualCandidateSelect');
        // サーバーはCLEAN版だけを返すが、版IDの無い行は選択肢にしない（版の無い提出を作らない）
        const usable = this.candidates.filter(c => c && c.documentId && c.documentVersionId);
        if (usable.length === 0) {
            select.innerHTML = `<option value="">${SES.escapeHtml(this.t('lifecycle.dual.candidate.none'))}</option>`;
            select.disabled = true;
            this.renderControls();
            return;
        }
        let html = `<option value="">${SES.escapeHtml(this.t('lifecycle.dual.candidate.placeholder'))}</option>`;
        usable.forEach(c => {
            // 文書titleは置換文字列（{n}・$&等）を含み得るため、置換の外で連結する
            const title = c.title || this.t('lifecycle.dual.candidate.untitled');
            const label = title + ' ' + this.t('lifecycle.dual.candidate.versionValue', [
                String(c.documentId),
                String(c.versionNo == null ? '-' : c.versionNo),
                this.formatDateTime(c.createdAt)
            ]);
            html += `<option value="${SES.escapeHtml(String(c.documentId) + ':' + String(c.documentVersionId))}">${SES.escapeHtml(label)}</option>`;
        });
        select.innerHTML = html;
        select.disabled = false;
        this.renderControls();
    },

    selectedCandidate: function() {
        const select = document.getElementById('dualCandidateSelect');
        if (!select || select.disabled || !select.value) return null;
        const parts = select.value.split(':');
        const documentId = parseInt(parts[0], 10);
        const documentVersionId = parseInt(parts[1], 10);
        if (!(documentId > 0) || !(documentVersionId > 0)) return null;
        return { documentId: documentId, documentVersionId: documentVersionId };
    },

    // ==========================================
    // 提出（A3 / A7）
    // ==========================================
    submit: async function() {
        if (this.busy || !this.view || !this.view.canSubmit) return;
        const selection = this.selectedCandidate();
        if (!selection) {
            SES.toast.warning(this.t('error.lifecycle.evidenceVersionRequired'));
            return;
        }
        const comment = document.getElementById('dualSubmitComment').value;
        if (!this.commentWithinLimit(comment)) {
            SES.toast.warning(this.t('lifecycle.dual.validation.commentTooLong'));
            return;
        }
        const payload = {
            taskGeneration: this.view.taskGeneration,
            documentSelections: [selection],
            comment: comment.trim() === '' ? null : comment
        };
        const result = await this.send('SUBMIT', this.taskUrl('/submissions'), payload);
        if (result) {
            SES.toast.success(this.t(result.replayed ? 'lifecycle.dual.toast.replayed' : 'lifecycle.dual.toast.submitted'));
            document.getElementById('dualSubmitComment').value = '';
        }
    },

    // ==========================================
    // 確認・差戻し（A4、社内の管理者・HRだけ）
    // ==========================================
    decide: async function(decision) {
        const v = this.view;
        if (this.busy || !v || !v.canDecide || !document.getElementById('dualDecisionSection')) return;
        if (decision !== 'CONFIRM' && decision !== 'REJECT') return;
        const comment = document.getElementById('dualDecisionComment').value;
        if (!this.commentWithinLimit(comment)) {
            SES.toast.warning(this.t('lifecycle.dual.validation.commentTooLong'));
            return;
        }
        const confirmed = await this.confirmDialog(decision, comment);
        if (!confirmed) return;
        const payload = {
            submissionRevision: v.submissionRevision,
            evidenceSha256: v.evidenceSha256,
            decision: decision,
            comment: comment.trim() === '' ? null : comment
        };
        const url = this.taskUrl(`/submissions/${encodeURIComponent(v.submissionId)}/decision`);
        const result = await this.send('DECIDE:' + v.submissionId, url, payload);
        if (result) {
            const key = result.replayed ? 'lifecycle.dual.toast.replayed'
                : (decision === 'CONFIRM' ? 'lifecycle.dual.toast.confirmed' : 'lifecycle.dual.toast.rejected');
            SES.toast.success(this.t(key));
            document.getElementById('dualDecisionComment').value = '';
        }
    },

    confirmDialog: async function(decision, comment) {
        const title = this.t(decision === 'CONFIRM' ? 'lifecycle.dual.confirm.confirmTitle' : 'lifecycle.dual.confirm.rejectTitle');
        const text = decision === 'REJECT' && comment.trim() === ''
            ? this.t('lifecycle.dual.confirm.rejectWithoutComment')
            : this.t(decision === 'CONFIRM' ? 'lifecycle.dual.confirm.confirmText' : 'lifecycle.dual.confirm.rejectText');
        if (typeof Swal === 'undefined') {
            return window.confirm(title + '\n' + text);
        }
        const answer = await Swal.fire({
            title: title,
            text: text,
            icon: decision === 'CONFIRM' ? 'question' : 'warning',
            showCancelButton: true,
            confirmButtonText: this.t(decision === 'CONFIRM' ? 'lifecycle.dual.action.confirm' : 'lifecycle.dual.action.reject'),
            cancelButtonText: this.t('common.cancel')
        });
        return Boolean(answer && answer.isConfirmed);
    },

    commentWithinLimit: function(comment) {
        return Array.from(comment || '').length <= this.COMMENT_MAX_LENGTH;
    },

    // ==========================================
    // 送信（Idempotency-Key・二重送信防止・エラー表示）
    // ==========================================
    /** 同じ操作・同じ内容の再送では同じkeyを返し、内容が変われば新しいkeyにする。 */
    keyFor: function(operation, payload) {
        const fingerprint = operation + '|' + this.taskId + '|' + JSON.stringify(payload);
        if (!this.pending || this.pending.fingerprint !== fingerprint) {
            this.pending = { fingerprint: fingerprint, key: this.newKey() };
        }
        return this.pending.key;
    },

    /** 1〜190文字の [A-Za-z0-9._~-]（サーバーの検査と同じ文字種）。 */
    newKey: function() {
        const cryptoApi = window.crypto;
        if (cryptoApi && typeof cryptoApi.randomUUID === 'function') {
            return 'ui-' + cryptoApi.randomUUID();
        }
        if (cryptoApi && typeof cryptoApi.getRandomValues === 'function') {
            const bytes = cryptoApi.getRandomValues(new Uint8Array(16));
            return 'ui-' + Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
        }
        return 'ui-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
    },

    setBusy: function(busy) {
        this.busy = busy;
        const modal = this.modal();
        if (modal) modal.setAttribute('aria-busy', busy ? 'true' : 'false');
        this.renderControls();
    },

    /**
     * 提出・確認・差戻しを送る。Idempotency-Keyは結果が分からない失敗（通信エラー・5xx）の後だけ再利用する。
     * 4xxはサーバーが拒否を決めた（試行はrollback済みで冪等の行も残らない）ため、keyを破棄して次は新しい操作として送る
     * （W3-21b review P3-2。冪等衝突を文言の比較で見分けない）。送信の成功後の再読込が失敗しても成功の結果は返す（P3-6）。
     */
    send: async function(operation, url, payload) {
        if (this.busy) return null;
        const key = this.keyFor(operation, payload);
        this.setBusy(true);
        let result;
        try {
            result = (await this.request('POST', url, payload, key)) || {};
            this.pending = null;
        } catch (e) {
            console.error(e);
            if (e && e.status >= 400 && e.status < 500) {
                this.pending = null;
            }
            // 状態が変わった可能性がある（失効・競合・認可の変化）ため、最新の状態を読み直す
            if (e && (e.status === 404 || e.status === 409)) {
                await this.reload();
            }
            this.setBusy(false);
            return null;
        }
        try {
            await this.reload();
            if (typeof this.onChanged === 'function') {
                await this.onChanged();
            }
        } catch (refreshError) {
            console.error(refreshError);
        } finally {
            this.setBusy(false);
        }
        return result;
    },

    request: async function(method, url, body, idempotencyKey) {
        const headers = SES.csrf.header();
        headers['Accept'] = 'application/json';
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
        let response;
        try {
            response = await fetch(url, {
                method: method,
                headers: headers,
                body: body === undefined ? undefined : JSON.stringify(body),
                cache: 'no-store',
                credentials: 'same-origin'
            });
        } catch (networkError) {
            // 結果が分からないため、同じ内容の再送では同じIdempotency-Keyを使う（保存済みの結果が返る）
            SES.toast.error(this.t('lifecycle.dual.error.network'));
            throw networkError;
        }
        const contentType = response.headers.get('content-type') || '';
        if (response.status === 401
            || (response.redirected && (response.url.indexOf('/login') !== -1 || contentType.indexOf('text/html') !== -1))) {
            if (SES.pwa) SES.pwa.sessionExpired();
            window.location.href = '/login?error=timeout';
            throw new Error('Session timeout');
        }
        let result = null;
        if (contentType.indexOf('application/json') !== -1) {
            try {
                result = await response.json();
            } catch (parseError) {
                result = null;
            }
        }
        if (result && result.code === 200) {
            return result.data;
        }
        const message = (result && result.message) || this.t('lifecycle.dual.error.generic');
        SES.toast.error(message);
        const error = new Error(message);
        error.status = response.status;
        throw error;
    }
};
