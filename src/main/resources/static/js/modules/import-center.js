/**
 * NF-06 取込センター画面（W3-12）。
 *
 * - 呼ぶのは既存の /api/import-center/** だけ。mapping版の束縛は検証API（POST /jobs/{id}/validations の mappingId）が
 *   編成層の中で行い、画面から束縛だけを行う経路は持たない。
 * - 検証は同期API（最長120秒＋α、途中で1byteも返らない）。LB等のidle timeoutで応答が途切れても結果を取りこぼさない
 *   よう、実行中は GET /jobs/{id} を一定間隔で確認し、VALIDATEDになったら要約（検証APIは公開済みjobに冪等）とpreviewを読む。
 * - 認可・法人scope・監査はAPIのservice側で操作ごとに再確認する。画面の表示制御は利便のためだけで、権限の根拠にしない。
 * - preview・error exportの行参照（sourceRowHash）は鍵付きHMACで、原文から計算し直せない値である。
 */
(function () {
    'use strict';

    const API = '/api/import-center';
    const POLL_INTERVAL_MS = 3000;
    // 120秒のsoft上限 + lease期限（30秒）+ 余裕。これを超えたら確認を打ち切り、利用者に再確認を促す。
    const POLL_LIMIT_MS = 200000;
    const MAX_UPLOAD_BYTES = 64 * 1024 * 1024;
    const RECENT_KEY = 'ses.importCenter.recentJobs';
    const RECENT_LIMIT = 10;
    const ENTITY = 'CUSTOMER';
    const SCHEMA_VERSION = 'ses-import-v1';
    // ses-import-v1 の顧客（CUSTOMER）項目。順序は SesImportV1Schema と同じ。
    const CUSTOMER_FIELDS = ['externalKey', 'companyName', 'companyNameKana', 'contactPerson', 'contactEmail',
        'contactPhone', 'address', 'commercialFlow', 'trustLevel', 'deliveryPreference', 'remarks'];
    const REQUIRED_FIELDS = ['externalKey', 'companyName'];
    const SOURCE_CODE_PATTERN = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;
    const LOST_RESPONSE_STATUSES = [0, 502, 504];

    const state = {
        sources: [],
        job: null,
        jobSourceId: null,
        summary: null,
        preview: { page: 1, size: 50, disposition: '', total: 0 },
        validation: { pending: false, timer: null, startedAt: 0, inFlight: false },
        mappingSourceId: null,
        mappings: []
    };

    class ImportApiError extends Error {
        /** fromApplication: 応答がこのアプリのApiResult（JSON）だったか。LB・proxyが返したHTML等ならfalse。 */
        constructor(status, message, fromApplication) {
            super(message);
            this.name = 'ImportApiError';
            this.status = status;
            this.fromApplication = fromApplication === true;
        }
    }

    function t(key, ...args) {
        return SES.i18n.t(key, ...args);
    }

    function esc(value) {
        return SES.escapeHtml(value === null || value === undefined ? '' : String(value));
    }

    function el(id) {
        return document.getElementById(id);
    }

    function statusMessage(status) {
        if (status === 403) return t('importCenter.error.forbidden');
        if (status === 404) return t('importCenter.error.notFound');
        if (status === 409) return t('importCenter.error.conflict');
        if (status === 0) return t('importCenter.error.network');
        return t('importCenter.error.unavailable');
    }

    function sessionExpired() {
        window.location.href = '/login?error=timeout';
        throw new ImportApiError(401, t('importCenter.error.network'));
    }

    /** /api/import-center への要求。ApiResult の code が200以外、またはJSONでない応答は ImportApiError にする。 */
    async function call(method, path, options) {
        const opts = options || {};
        const headers = Object.assign({ 'Accept': 'application/json' }, opts.headers || {});
        if (method !== 'GET') {
            Object.assign(headers, SES.csrf.header());
        }
        let response;
        try {
            response = await fetch(API + path, {
                method: method,
                headers: headers,
                body: opts.body,
                credentials: 'same-origin',
                cache: 'no-store'
            });
        } catch (networkFailure) {
            throw new ImportApiError(0, t('importCenter.error.network'));
        }
        if (response.status === 401 || (response.redirected && response.url.indexOf('/login') !== -1)) {
            sessionExpired();
        }
        const type = response.headers.get('content-type') || '';
        let body = null;
        if (type.indexOf('application/json') !== -1) {
            try {
                body = await response.json();
            } catch (malformed) {
                body = null;
            }
        } else if (response.ok && type.indexOf('text/html') !== -1) {
            // JSONの代わりにHTML（login画面）が返った = session切れ
            sessionExpired();
        }
        if (!response.ok || !body || body.code !== 200) {
            const status = response.ok ? (body && body.code) || 500 : response.status;
            // 403は権限・法人の確認手順を示す固定文言にする（server側の文言は汎用の拒否文言のため）
            const message = status !== 403 && body && body.message ? body.message : statusMessage(status);
            throw new ImportApiError(status, message, body !== null);
        }
        return body.data;
    }

    function jsonBody(value) {
        return { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) };
    }

    // ---------------------------------------------------------------- 表示補助

    function showAlert(message) {
        const box = el('importCenterAlert');
        box.textContent = message;
        box.classList.remove('d-none');
    }

    function clearAlert() {
        const box = el('importCenterAlert');
        box.textContent = '';
        box.classList.add('d-none');
    }

    function setBusy(button, busy) {
        if (!button) return;
        button.disabled = busy;
        button.setAttribute('aria-busy', busy ? 'true' : 'false');
    }

    function setValidationStatus(kind, message, spinner) {
        const box = el('importValidationStatus');
        if (!message) {
            box.innerHTML = '';
            return;
        }
        const color = { info: 'text-info', success: 'text-success', warning: 'text-warning', danger: 'text-danger' }[kind]
            || 'text-muted';
        box.innerHTML = '<span class="' + color + '">'
            + (spinner ? '<span class="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>' : '')
            + esc(message) + '</span>';
    }

    function stateLabel(value) {
        const key = 'importCenter.state.' + value;
        const label = t(key);
        return label === key ? value : label;
    }

    function stateBadge(value) {
        const color = { UPLOADED: 'bg-secondary', MAPPED: 'bg-info text-dark', VALIDATING: 'bg-warning text-dark',
            VALIDATED: 'bg-success' }[value] || 'bg-secondary';
        return '<span class="badge ' + color + '">' + esc(stateLabel(value)) + '</span>';
    }

    function activeSources() {
        return state.sources.filter(source => source.status === 'ACTIVE');
    }

    function fillSourceSelect(select, selectedId) {
        const sources = activeSources();
        let html = '<option value="">' + esc(t('importCenter.select.placeholder')) + '</option>';
        sources.forEach(source => {
            html += '<option value="' + esc(source.id) + '">' + esc(source.sourceCode + ' / ' + source.displayName)
                + '</option>';
        });
        select.innerHTML = html;
        if (selectedId && sources.some(source => source.id === selectedId)) {
            select.value = String(selectedId);
        }
    }

    // ---------------------------------------------------------------- 最近のジョブ（この端末のlocalStorageだけ）

    function recentJobs() {
        try {
            const raw = window.localStorage.getItem(RECENT_KEY);
            const list = raw ? JSON.parse(raw) : [];
            return Array.isArray(list)
                ? list.filter(item => item && Number.isInteger(item.jobId) && item.jobId > 0).slice(0, RECENT_LIMIT)
                : [];
        } catch (unavailable) {
            return [];
        }
    }

    function rememberJob(jobId, sourceId, sourceCode) {
        const list = recentJobs().filter(item => item.jobId !== jobId);
        list.unshift({ jobId: jobId, sourceId: sourceId || null, sourceCode: sourceCode || '' });
        try {
            window.localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, RECENT_LIMIT)));
        } catch (unavailable) {
            // 保存できない環境（private mode等）では記憶しない
        }
        renderRecentJobs();
    }

    function renderRecentJobs() {
        const box = el('importRecentJobs');
        const list = recentJobs();
        if (list.length === 0) {
            box.innerHTML = '<span class="text-muted small">' + esc(t('importCenter.job.recentEmpty')) + '</span>';
            return;
        }
        box.innerHTML = list.map(item => '<button type="button" class="btn btn-sm btn-outline-secondary" data-job-id="'
            + esc(item.jobId) + '">#' + esc(item.jobId) + (item.sourceCode ? ' ' + esc(item.sourceCode) : '')
            + '</button>').join('');
    }

    function updateUrl(jobId) {
        try {
            window.history.replaceState(null, '', '/import-center?jobId=' + encodeURIComponent(jobId));
        } catch (unsupported) {
            // URLの更新は利便のためだけ
        }
    }

    // ---------------------------------------------------------------- 取込元

    async function loadSources() {
        try {
            const page = await call('GET', '/sources?page=1&size=200');
            state.sources = (page && page.items) || [];
            renderSourceTable(null);
        } catch (error) {
            state.sources = [];
            renderSourceTable(error.message);
            showAlert(error.message);
        }
        fillSourceSelect(el('importUploadSource'), null);
        if (state.job && state.job.state === 'UPLOADED') {
            renderValidationControls();
        }
    }

    function renderSourceTable(errorMessage) {
        const body = el('importSourceTableBody');
        if (errorMessage) {
            body.innerHTML = '<tr><td colspan="6" class="text-danger">' + esc(errorMessage) + '</td></tr>';
            return;
        }
        if (state.sources.length === 0) {
            body.innerHTML = '<tr><td colspan="6" class="text-muted">' + esc(t('importCenter.source.empty')) + '</td></tr>';
            return;
        }
        body.innerHTML = state.sources.map(source => {
            const active = source.status === 'ACTIVE';
            return '<tr>'
                + '<td>' + esc(source.id) + '</td>'
                + '<td><code>' + esc(source.sourceCode) + '</code></td>'
                + '<td class="text-break">' + esc(source.displayName) + '</td>'
                + '<td><span class="badge ' + (active ? 'bg-success' : 'bg-secondary') + '">'
                + esc(t('importCenter.source.status.' + source.status)) + '</span></td>'
                + '<td>' + esc(source.version) + '</td>'
                + '<td class="text-nowrap">'
                + '<button type="button" class="btn btn-sm btn-outline-light me-1" data-action="mappings" data-source-id="'
                + esc(source.id) + '">' + esc(t('importCenter.source.mappings')) + '</button>'
                + '<button type="button" class="btn btn-sm ' + (active ? 'btn-outline-warning' : 'btn-outline-success')
                + '" data-action="toggle" data-source-id="' + esc(source.id) + '">'
                + esc(t(active ? 'importCenter.source.disable' : 'importCenter.source.enable')) + '</button>'
                + '</td></tr>';
        }).join('');
    }

    async function createSource(event) {
        event.preventDefault();
        const code = el('importSourceCode').value.trim();
        const name = el('importSourceName').value.trim();
        if (!SOURCE_CODE_PATTERN.test(code) || !name) {
            SES.toast.error(t('importCenter.error.sourceInput'));
            return;
        }
        const button = el('importSourceCreateBtn');
        setBusy(button, true);
        try {
            await call('POST', '/sources', jsonBody({ sourceCode: code, displayName: name }));
            el('importSourceForm').reset();
            SES.toast.success(t('importCenter.source.created'));
            await loadSources();
        } catch (error) {
            SES.toast.error(error.message);
        } finally {
            setBusy(button, false);
        }
    }

    async function toggleSource(sourceId) {
        const source = state.sources.find(item => item.id === sourceId);
        if (!source) return;
        const next = source.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
        const confirmed = await Swal.fire({
            title: t(next === 'DISABLED' ? 'importCenter.source.disable' : 'importCenter.source.enable'),
            text: t(next === 'DISABLED' ? 'importCenter.source.disableConfirm' : 'importCenter.source.enableConfirm'),
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: t('common.yes'),
            cancelButtonText: t('common.cancel')
        });
        if (!confirmed.isConfirmed) return;
        try {
            await call('PUT', '/sources/' + encodeURIComponent(sourceId),
                jsonBody({ expectedVersion: source.version, status: next }));
            SES.toast.success(t('importCenter.source.updated'));
        } catch (error) {
            SES.toast.error(error.message);
        }
        await loadSources();
    }

    // ---------------------------------------------------------------- mapping版

    async function fetchMappings(sourceId) {
        const page = await call('GET', '/sources/' + encodeURIComponent(sourceId) + '/mappings?page=1&size=200');
        return ((page && page.items) || [])
            .filter(mapping => mapping.entity === ENTITY && mapping.schemaVersion === SCHEMA_VERSION)
            .sort((a, b) => b.mappingVersion - a.mappingVersion);
    }

    async function selectMappingSource(sourceId) {
        const source = state.sources.find(item => item.id === sourceId);
        if (!source) return;
        state.mappingSourceId = sourceId;
        el('importMappingSourceLabel').textContent = source.sourceCode;
        el('importMappingEmptyHint').classList.add('d-none');
        el('importMappingPanel').classList.remove('d-none');
        await loadMappings();
    }

    async function loadMappings() {
        const body = el('importMappingTableBody');
        body.innerHTML = '<tr><td colspan="4" class="text-muted">' + esc(t('common.loading')) + '</td></tr>';
        try {
            state.mappings = await fetchMappings(state.mappingSourceId);
        } catch (error) {
            state.mappings = [];
            body.innerHTML = '<tr><td colspan="4" class="text-danger">' + esc(error.message) + '</td></tr>';
            renderMappingForm(null);
            return;
        }
        if (state.mappings.length === 0) {
            body.innerHTML = '<tr><td colspan="4" class="text-muted">' + esc(t('importCenter.mapping.empty')) + '</td></tr>';
        } else {
            body.innerHTML = state.mappings.map(mapping => '<tr>'
                + '<td>' + esc(mapping.id) + '</td>'
                + '<td>v' + esc(mapping.mappingVersion) + '</td>'
                + '<td><code>' + esc(mapping.mappingHashPrefix) + '</code></td>'
                + '<td>' + esc(Object.keys(mapping.headerToCanonicalField || {}).length) + '</td>'
                + '</tr>').join('');
        }
        // 最新版を初期値にする（版は不変で、内容を変えて登録すると次の版になる）
        renderMappingForm(state.mappings.length > 0 ? state.mappings[0] : null);
    }

    function renderMappingForm(base) {
        const headerByField = {};
        if (base && base.headerToCanonicalField) {
            Object.keys(base.headerToCanonicalField).forEach(header => {
                headerByField[base.headerToCanonicalField[header]] = header;
            });
        }
        el('importMappingFields').innerHTML = CUSTOMER_FIELDS.map(field => {
            const value = base ? (headerByField[field] || '') : field;
            const required = REQUIRED_FIELDS.indexOf(field) !== -1;
            return '<tr>'
                + '<td><label for="importMappingField-' + esc(field) + '">' + esc(t('importCenter.field.' + field))
                + (required ? ' <span class="badge bg-danger">' + esc(t('importCenter.mapping.required')) + '</span>' : '')
                + '</label><div><code class="small">' + esc(field) + '</code></div></td>'
                + '<td><input class="form-control form-control-sm bg-dark border-secondary text-light" maxlength="128"'
                + ' autocomplete="off" id="importMappingField-' + esc(field) + '" data-field="' + esc(field)
                + '" value="' + esc(value) + '"></td>'
                + '</tr>';
        }).join('');
    }

    async function createMapping(event) {
        event.preventDefault();
        if (!state.mappingSourceId) return;
        const mapping = {};
        const seen = new Set();
        let duplicate = false;
        el('importMappingFields').querySelectorAll('input[data-field]').forEach(input => {
            const header = input.value.trim();
            if (!header) return;
            if (seen.has(header)) duplicate = true;
            seen.add(header);
            mapping[header] = input.getAttribute('data-field');
        });
        if (duplicate) {
            SES.toast.error(t('importCenter.mapping.duplicateHeader'));
            return;
        }
        const mappedFields = Object.keys(mapping).map(header => mapping[header]);
        if (!REQUIRED_FIELDS.every(field => mappedFields.indexOf(field) !== -1)) {
            SES.toast.error(t('importCenter.mapping.requiredMissing'));
            return;
        }
        const button = el('importMappingCreateBtn');
        setBusy(button, true);
        try {
            await call('POST', '/sources/' + encodeURIComponent(state.mappingSourceId) + '/mappings',
                jsonBody({ entity: ENTITY, schemaVersion: SCHEMA_VERSION, headerToCanonicalField: mapping }));
            SES.toast.success(t('importCenter.mapping.created'));
            await loadMappings();
            if (state.job && state.job.state === 'UPLOADED') {
                await loadValidationMappings();
            }
        } catch (error) {
            SES.toast.error(error.message);
        } finally {
            setBusy(button, false);
        }
    }

    // ---------------------------------------------------------------- upload・ジョブ

    async function upload(event) {
        event.preventDefault();
        clearAlert();
        const result = el('importUploadResult');
        const source = state.sources.find(item => String(item.id) === el('importUploadSource').value);
        const file = el('importUploadFile').files[0];
        let problem = null;
        if (!source) problem = t('importCenter.error.selectSource');
        else if (!file) problem = t('importCenter.error.selectFile');
        else if (file.size > MAX_UPLOAD_BYTES) problem = t('importCenter.error.fileTooLarge');
        if (problem) {
            result.innerHTML = '<span class="text-danger">' + esc(problem) + '</span>';
            return;
        }
        const button = el('importUploadBtn');
        setBusy(button, true);
        result.innerHTML = '<span class="text-info"><span class="spinner-border spinner-border-sm me-2" role="status"'
            + ' aria-hidden="true"></span>' + esc(t('importCenter.upload.running')) + '</span>';
        try {
            // bodyはfileそのもの（multipartにしない）。Content-Typeはparameterを付けない text/csv だけを受け付ける。
            const detail = await call('POST', '/uploads', {
                headers: {
                    'Content-Type': 'text/csv',
                    'X-Import-Source-Code': source.sourceCode,
                    'X-Import-Encoding': el('importUploadEncoding').value
                },
                body: file
            });
            result.innerHTML = '<span class="text-success">' + esc(t('importCenter.uploaded', detail.jobId)) + '</span>';
            el('importUploadFile').value = '';
            rememberJob(detail.jobId, source.id, source.sourceCode);
            showJob(detail, source.id);
        } catch (error) {
            result.innerHTML = '<span class="text-danger">' + esc(error.message) + '</span>';
        } finally {
            setBusy(button, false);
        }
    }

    async function openJob(jobId, sourceIdHint) {
        clearAlert();
        stopPolling();
        try {
            const detail = await call('GET', '/jobs/' + encodeURIComponent(jobId));
            const remembered = recentJobs().find(item => item.jobId === detail.jobId);
            showJob(detail, sourceIdHint || (remembered && remembered.sourceId) || null);
        } catch (error) {
            showAlert(error.message);
        }
    }

    function showJob(detail, sourceId) {
        const changed = !state.job || state.job.jobId !== detail.jobId;
        state.job = detail;
        if (changed) {
            state.summary = null;
            state.jobSourceId = sourceId || null;
            state.preview.page = 1;
            setValidationStatus(null, null);
            renderSummary();
            renderPreviewPlaceholder(t('importCenter.preview.notReady'), false);
        } else if (sourceId) {
            state.jobSourceId = sourceId;
        }
        updateUrl(detail.jobId);
        el('importJobEmpty').classList.add('d-none');
        el('importJobPanel').classList.remove('d-none');
        renderJobDetail();
        renderValidationControls();
        if (detail.state === 'VALIDATED' && !state.summary) {
            loadSummaryAndPreview();
        }
    }

    function renderJobDetail() {
        const job = state.job;
        const rows = [
            ['importCenter.job.idLabel', '#' + esc(job.jobId)],
            ['importCenter.job.state', stateBadge(job.state)],
            ['importCenter.job.entity', esc(job.entity)],
            ['importCenter.job.encoding', esc(job.encoding)],
            ['importCenter.job.hashPrefix', '<code>' + esc(job.sourceHashPrefix) + '</code>'],
            ['importCenter.job.documentVersion', 'v' + esc(job.documentVersionNo)]
        ];
        el('importJobDetail').innerHTML = rows.map(row => '<dt class="col-5 col-sm-4 text-muted fw-normal">'
            + esc(t(row[0])) + '</dt><dd class="col-7 col-sm-8 mb-1 text-break">' + row[1] + '</dd>').join('');
    }

    async function refreshJob(jobId) {
        try {
            const detail = await call('GET', '/jobs/' + encodeURIComponent(jobId));
            showJob(detail, null);
            return detail;
        } catch (error) {
            showAlert(error.message);
            return null;
        }
    }

    // ---------------------------------------------------------------- 検証

    function renderValidationControls() {
        const job = state.job;
        const chooser = el('importMappingChooser');
        const button = el('importValidateBtn');
        if (!job) return;
        button.classList.toggle('d-none', job.state === 'VALIDATED');
        button.textContent = t(job.state === 'VALIDATING' ? 'importCenter.validation.resume'
            : 'importCenter.validation.start');
        button.disabled = state.validation.pending;
        if (job.state === 'UPLOADED') {
            chooser.classList.remove('d-none');
            const select = el('importValidationSource');
            const previous = select.value ? Number(select.value) : null;
            fillSourceSelect(select, previous || state.jobSourceId);
            loadValidationMappings();
        } else {
            chooser.classList.add('d-none');
        }
    }

    async function loadValidationMappings() {
        const select = el('importValidationMapping');
        const sourceId = Number(el('importValidationSource').value);
        if (!sourceId) {
            select.innerHTML = '<option value="">' + esc(t('importCenter.select.placeholder')) + '</option>';
            return;
        }
        select.innerHTML = '<option value="">' + esc(t('common.loading')) + '</option>';
        try {
            const mappings = await fetchMappings(sourceId);
            if (mappings.length === 0) {
                select.innerHTML = '<option value="">' + esc(t('importCenter.validation.mappingNone')) + '</option>';
                return;
            }
            select.innerHTML = mappings.map(mapping => '<option value="' + esc(mapping.id) + '">v'
                + esc(mapping.mappingVersion) + ' / ' + esc(mapping.mappingHashPrefix) + '</option>').join('');
        } catch (error) {
            select.innerHTML = '<option value="">' + esc(error.message) + '</option>';
        }
    }

    async function startValidation() {
        const job = state.job;
        if (!job || state.validation.pending) return;
        let body = {};
        if (job.state === 'UPLOADED') {
            const mappingId = Number(el('importValidationMapping').value);
            if (!mappingId) {
                setValidationStatus('danger', t('importCenter.error.selectMapping'));
                return;
            }
            body = { mappingId: mappingId };
        }
        clearAlert();
        state.validation.pending = true;
        el('importValidateBtn').disabled = true;
        setValidationStatus('info', t('importCenter.validation.running', POLL_INTERVAL_MS / 1000), true);
        startPolling(job.jobId);
        try {
            const summary = await call('POST', '/jobs/' + encodeURIComponent(job.jobId) + '/validations', jsonBody(body));
            state.validation.pending = false;
            stopPolling();
            onValidated(summary);
        } catch (error) {
            state.validation.pending = false;
            el('importValidateBtn').disabled = false;
            if (LOST_RESPONSE_STATUSES.indexOf(error.status) !== -1 || (error.status >= 500 && !error.fromApplication)) {
                // 応答が途切れただけ（LB・proxyが返すJSONでない5xxを含む）で、server側では検証が続いている可能性がある。
                // 状態の確認を続ける。アプリのApiResultで返った5xx（120秒の上限超過の503等）は確定した失敗として扱う。
                setValidationStatus('warning', t('importCenter.validation.connectionLost'), true);
                return;
            }
            if (error.status === 409) {
                const latest = await refreshJob(job.jobId);
                if (latest && latest.state === 'VALIDATING') {
                    setValidationStatus('warning', t('importCenter.validation.inProgressElsewhere'), true);
                    return;
                }
            }
            stopPolling();
            setValidationStatus('danger', error.message);
            if (error.status !== 409) {
                await refreshJob(job.jobId);
            }
        }
    }

    function startPolling(jobId) {
        stopPolling();
        state.validation.startedAt = Date.now();
        state.validation.timer = window.setInterval(() => {
            pollOnce(jobId);
        }, POLL_INTERVAL_MS);
    }

    function stopPolling() {
        if (state.validation.timer) {
            window.clearInterval(state.validation.timer);
            state.validation.timer = null;
        }
    }

    async function pollOnce(jobId) {
        if (!state.job || state.job.jobId !== jobId) {
            stopPolling();
            return;
        }
        if (Date.now() - state.validation.startedAt > POLL_LIMIT_MS) {
            stopPolling();
            if (!state.validation.pending) {
                setValidationStatus('warning', t('importCenter.validation.pollTimeout'));
            }
            return;
        }
        if (state.validation.inFlight) return;
        state.validation.inFlight = true;
        let detail = null;
        try {
            detail = await call('GET', '/jobs/' + encodeURIComponent(jobId));
        } catch (transientFailure) {
            detail = null; // 一時的な失敗は次の周期で再確認する
        } finally {
            state.validation.inFlight = false;
        }
        if (!detail || !state.job || state.job.jobId !== jobId) return;
        state.job = detail;
        renderJobDetail();
        if (state.validation.pending) return; // 元の検証要求の応答を待つ
        if (detail.state === 'VALIDATED') {
            stopPolling();
            await loadSummaryAndPreview();
        } else if (detail.state !== 'VALIDATING') {
            stopPolling();
            setValidationStatus('warning', t('importCenter.validation.aborted'));
            renderValidationControls();
        }
    }

    /** 公開済み（VALIDATED）のjobの要約を読む。検証APIは公開済みjobを再検証せず現在の要約を返す（冪等）。 */
    async function loadSummaryAndPreview() {
        const job = state.job;
        if (!job) return;
        try {
            const summary = await call('POST', '/jobs/' + encodeURIComponent(job.jobId) + '/validations', jsonBody({}));
            onValidated(summary);
        } catch (error) {
            setValidationStatus('danger', error.message);
        }
    }

    function onValidated(summary) {
        if (!state.job || state.job.jobId !== summary.jobId) return;
        state.summary = summary;
        state.job.state = summary.state;
        renderJobDetail();
        renderValidationControls();
        renderSummary();
        setValidationStatus('success', t('importCenter.validation.done'));
        loadPreview(1);
    }

    function renderSummary() {
        const section = el('importSummarySection');
        const summary = state.summary;
        if (!summary) {
            section.classList.add('d-none');
            return;
        }
        section.classList.remove('d-none');
        const tiles = [
            ['importCenter.summary.sourceRows', summary.sourceRows, 'text-light'],
            ['importCenter.summary.accepted', summary.acceptedRows, 'text-success'],
            ['importCenter.summary.rejected', summary.rejectedRows, 'text-danger'],
            ['importCenter.summary.empty', summary.emptyRows, 'text-muted']
        ];
        el('importValidationSummary').innerHTML = tiles.map(tile => '<div class="col-6 col-md-3">'
            + '<div class="border border-secondary rounded py-2">'
            + '<div class="fs-5 fw-bold ' + tile[2] + '">' + esc(tile[1]) + '</div>'
            + '<div class="text-muted small">' + esc(t(tile[0])) + '</div></div></div>').join('')
            + '<div class="col-12 text-muted small text-start">' + esc(t('importCenter.summary.run', summary.runNo,
                summary.runId)) + '</div>';
        el('importErrorExportBtn').disabled = !(summary.rejectedRows > 0);
    }

    function exportErrors() {
        const job = state.job;
        if (!job || !state.summary) return;
        SES.download(API + '/jobs/' + encodeURIComponent(job.jobId) + '/error-export',
            'import-job-' + job.jobId + '-errors.csv');
    }

    // ---------------------------------------------------------------- preview

    function renderPreviewPlaceholder(message, isError) {
        el('importPreviewHead').innerHTML = '';
        el('importPreviewBody').innerHTML = '<tr><td class="' + (isError ? 'text-danger' : 'text-muted') + '">'
            + esc(message) + '</td></tr>';
        el('importPreviewPageInfo').textContent = '';
        el('importPreviewPrev').disabled = true;
        el('importPreviewNext').disabled = true;
    }

    async function loadPreview(page) {
        const job = state.job;
        if (!job || job.state !== 'VALIDATED') {
            renderPreviewPlaceholder(t('importCenter.preview.notReady'), false);
            return;
        }
        const preview = state.preview;
        preview.page = Math.max(1, page || 1);
        preview.size = Number(el('importPreviewSize').value) || 50;
        preview.disposition = el('importPreviewDisposition').value || '';
        const query = new URLSearchParams({ page: String(preview.page), size: String(preview.size) });
        if (preview.disposition) query.set('disposition', preview.disposition);
        renderPreviewPlaceholder(t('common.loading'), false);
        try {
            const result = await call('GET', '/jobs/' + encodeURIComponent(job.jobId) + '/preview?' + query.toString());
            if (!state.job || state.job.jobId !== job.jobId) return;
            preview.page = result.page;
            preview.size = result.size;
            preview.total = result.total;
            renderPreview(result);
        } catch (error) {
            renderPreviewPlaceholder(error.message, true);
        }
    }

    function renderPreview(result) {
        el('importPreviewHead').innerHTML = '<tr><th>' + esc(t('importCenter.preview.rowNumber')) + '</th>'
            + '<th>' + esc(t('importCenter.preview.disposition')) + '</th>'
            + '<th>' + esc(t('importCenter.preview.findings')) + '</th>'
            + CUSTOMER_FIELDS.map(field => '<th class="text-nowrap">' + esc(t('importCenter.field.' + field)) + '</th>').join('')
            + '<th>' + esc(t('importCenter.preview.rowReference')) + '</th></tr>';
        const rows = result.rows || [];
        if (rows.length === 0) {
            el('importPreviewBody').innerHTML = '<tr><td colspan="' + (CUSTOMER_FIELDS.length + 4) + '" class="text-muted">'
                + esc(t('importCenter.preview.empty')) + '</td></tr>';
        } else {
            el('importPreviewBody').innerHTML = rows.map(row => {
                const accepted = row.disposition === 'ACCEPTED';
                const fields = row.fields || {};
                const reference = row.sourceRowHash || '';
                return '<tr>'
                    + '<td>' + esc(row.sourceRowNumber) + '</td>'
                    + '<td><span class="badge ' + (accepted ? 'bg-success' : 'bg-danger') + '">'
                    + esc(t(accepted ? 'importCenter.preview.disposition.accepted'
                        : 'importCenter.preview.disposition.rejected')) + '</span></td>'
                    + '<td>' + (row.findingCodes || []).map(code => '<code class="d-block small">' + esc(code) + '</code>')
                        .join('') + '</td>'
                    + CUSTOMER_FIELDS.map(field => '<td class="text-break">'
                        + (fields[field] === null || fields[field] === undefined || fields[field] === ''
                            ? '<span class="text-muted">-</span>' : esc(fields[field])) + '</td>').join('')
                    + '<td><code class="small" title="' + esc(reference) + '">' + esc(reference.substring(0, 12))
                    + '</code></td>'
                    + '</tr>';
            }).join('');
        }
        const totalPages = Math.max(1, Math.ceil(result.total / result.size));
        el('importPreviewPageInfo').textContent = t('importCenter.preview.pageInfo', result.page, totalPages, result.total);
        el('importPreviewPrev').disabled = result.page <= 1;
        el('importPreviewNext').disabled = result.page >= totalPages;
    }

    // ---------------------------------------------------------------- 初期化

    function bind() {
        el('importUploadForm').addEventListener('submit', upload);
        el('importJobOpenForm').addEventListener('submit', event => {
            event.preventDefault();
            const value = Number(el('importJobIdInput').value);
            if (!Number.isInteger(value) || value < 1) {
                showAlert(t('importCenter.error.invalidJobId'));
                return;
            }
            openJob(value, null);
        });
        el('importRecentJobs').addEventListener('click', event => {
            const button = event.target.closest('button[data-job-id]');
            if (button) openJob(Number(button.getAttribute('data-job-id')), null);
        });
        el('importValidationSource').addEventListener('change', () => {
            state.jobSourceId = Number(el('importValidationSource').value) || null;
            loadValidationMappings();
        });
        el('importValidateBtn').addEventListener('click', startValidation);
        el('importErrorExportBtn').addEventListener('click', exportErrors);
        el('searchForm').addEventListener('submit', event => {
            event.preventDefault();
            loadPreview(1);
        });
        el('importPreviewPrev').addEventListener('click', () => loadPreview(state.preview.page - 1));
        el('importPreviewNext').addEventListener('click', () => loadPreview(state.preview.page + 1));
        el('importSourceForm').addEventListener('submit', createSource);
        el('importSourceTableBody').addEventListener('click', event => {
            const button = event.target.closest('button[data-action]');
            if (!button) return;
            const sourceId = Number(button.getAttribute('data-source-id'));
            if (button.getAttribute('data-action') === 'toggle') toggleSource(sourceId);
            else selectMappingSource(sourceId);
        });
        el('importMappingForm').addEventListener('submit', createMapping);
    }

    async function init() {
        if (!el('importCenterApp')) return;
        bind();
        renderRecentJobs();
        await loadSources();
        const requested = Number(new URLSearchParams(window.location.search).get('jobId'));
        if (Number.isInteger(requested) && requested > 0) {
            await openJob(requested, null);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
