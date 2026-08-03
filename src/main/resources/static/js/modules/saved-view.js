/**
 * 保存ビュー（Saved Views）共通コンポーネント JS
 */
(function() {
    window.SES = window.SES || {};

    SES.savedView = {
        pageKey: null,
        applyCallback: null,
        getFiltersCallback: null,
        currentViews: [],

        init: function(pageKey, applyCallback, getFiltersCallback) {
            this.pageKey = pageKey;
            this.applyCallback = applyCallback;
            this.getFiltersCallback = getFiltersCallback;
            this.loadViews();
        },

        loadViews: function() {
            if (!this.pageKey) return;
            const self = this;
            $.ajax({
                url: '/api/saved-views',
                type: 'GET',
                data: { pageKey: self.pageKey },
                success: function(res) {
                    if (res && res.code === 200) {
                        self.currentViews = res.data || [];
                        self.renderDropdown();
                    }
                }
            });
        },

        renderDropdown: function() {
            const $container = $('#saved-view-container');
            if (!$container.length) return;

            let html = `
                <div class="dropdown me-2 d-inline-block">
                    <button class="btn btn-outline-secondary dropdown-toggle text-light border-dark" type="button" id="savedViewDropdownBtn" data-bs-toggle="dropdown" aria-expanded="false">
                        <i class="bi bi-bookmark-star me-1 text-warning"></i>保存ビュー
                    </button>
                    <ul class="dropdown-menu dropdown-menu-dark shadow border-dark" aria-labelledby="savedViewDropdownBtn">
                        <li><h6 class="dropdown-header">表示ビューの選択</h6></li>
            `;

            if (this.currentViews.length === 0) {
                html += `<li><span class="dropdown-item text-muted disabled">保存されたビューはありません</span></li>`;
            } else {
                this.currentViews.forEach(v => {
                    const badge = v.ownerUserId === null ? '<span class="badge bg-info text-dark ms-1">共有</span>' : '<span class="badge bg-secondary ms-1">個人</span>';
                    html += `
                        <li class="d-flex justify-content-between align-items-center px-2">
                            <a class="dropdown-item py-1 rounded" href="#" onclick="event.preventDefault(); SES.savedView.selectView(${v.id});">
                                ${SES.escapeHtml(v.name)} ${badge}
                            </a>
                            <button class="btn btn-sm btn-link text-danger p-0 px-1 text-decoration-none ms-1" onclick="event.stopPropagation(); SES.savedView.deleteView(${v.id});" title="削除">
                                <i class="bi bi-x"></i>
                            </button>
                        </li>
                    `;
                });
            }

            html += `
                        <li><hr class="dropdown-divider border-secondary"></li>
                        <li><a class="dropdown-item text-primary" href="#" onclick="event.preventDefault(); SES.savedView.openSaveModal();"><i class="bi bi-plus-circle me-1"></i>現在の検索条件を保存...</a></li>
                    </ul>
                </div>
            `;

            $container.html(html);
        },

        selectView: function(viewId) {
            const view = this.currentViews.find(v => v.id === viewId);
            if (view && typeof this.applyCallback === 'function') {
                try {
                    let filter = view.filterJson ? JSON.parse(view.filterJson) : {};
                    let sort = view.sortJson ? JSON.parse(view.sortJson) : null;
                    let columns = view.columnsJson ? JSON.parse(view.columnsJson) : null;
                    let pageSize = view.pageSize || 20;
                    this.applyCallback({ filter: filter, sort: sort, columns: columns, pageSize: pageSize, view: view });
                    SES.toast.success(`ビュー「${view.name}」を適用しました`);
                } catch (e) {
                    SES.toast.error('ビューデータの適用に失敗しました');
                }
            }
        },

        openSaveModal: function() {
            let $modal = $('#saveViewModal');
            if (!$modal.length) {
                const modalHtml = `
                    <div class="modal fade" id="saveViewModal" tabindex="-1" aria-hidden="true">
                        <div class="modal-dialog">
                            <div class="modal-content bg-secondary text-white border-dark">
                                <div class="modal-header border-dark">
                                    <h5 class="modal-title">検索ビューの保存</h5>
                                    <button type="button" class="btn-close btn-close-white" data-bs-dismiss="modal" aria-label="Close"></button>
                                </div>
                                <div class="modal-body">
                                    <div class="mb-3">
                                        <label for="saved-view-name" class="form-label">ビュー名 <span class="text-danger">*</span></label>
                                        <input type="text" class="form-control bg-dark text-white border-secondary" id="saved-view-name" placeholder="例: 東京勤務・稼動中一覧" required>
                                    </div>
                                    <div class="mb-3 form-check">
                                        <input type="checkbox" class="form-check-input" id="saved-view-shared">
                                        <label class="form-check-label text-light" for="saved-view-shared">全ユーザーへ共有ビューとして公開（管理者限定）</label>
                                    </div>
                                </div>
                                <div class="modal-footer border-dark">
                                    <button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">キャンセル</button>
                                    <button type="button" class="btn btn-primary" onclick="SES.savedView.submitSave()">保存</button>
                                </div>
                            </div>
                        </div>
                    </div>
                `;
                $('body').append(modalHtml);
                $modal = $('#saveViewModal');
            }

            $('#saved-view-name').val('');
            $('#saved-view-shared').prop('checked', false);
            bootstrap.Modal.getOrCreateInstance($modal[0]).show();
        },

        submitSave: function() {
            const name = $('#saved-view-name').val().trim();
            if (!name) {
                SES.toast.error('ビュー名を入力してください');
                return;
            }
            const isShared = $('#saved-view-shared').is(':checked');
            const state = typeof this.getFiltersCallback === 'function' ? this.getFiltersCallback() : {};

            const filterObj = state.filter || state;
            const sortObj = state.sort || null;
            const columnsArr = state.columns || null;
            const pageSizeVal = state.pageSize || 20;

            const payload = {
                pageKey: this.pageKey,
                name: name,
                filterJson: JSON.stringify(filterObj),
                sortJson: sortObj ? JSON.stringify(sortObj) : null,
                columnsJson: columnsArr ? JSON.stringify(columnsArr) : null,
                pageSize: pageSizeVal,
                sharedFlag: isShared ? 1 : 0,
                ownerUserId: isShared ? null : undefined
            };

            const self = this;
            $.ajax({
                url: '/api/saved-views',
                type: 'POST',
                contentType: 'application/json',
                data: JSON.stringify(payload),
                success: function(res) {
                    if (res && res.code === 200) {
                        SES.toast.success('保存ビューを登録しました');
                        const modalEl = document.getElementById('saveViewModal');
                        if (modalEl) bootstrap.Modal.getInstance(modalEl).hide();
                        self.loadViews();
                    } else {
                        SES.toast.error(res.message || '登録に失敗しました');
                    }
                },
                error: function(xhr) {
                    let msg = '登録に失敗しました';
                    try {
                        const json = JSON.parse(xhr.responseText);
                        if (json.message) msg = json.message;
                    } catch(e) {}
                    SES.toast.error(msg);
                }
            });
        },

        deleteView: function(viewId) {
            const self = this;
            Swal.fire({
                title: '保存ビュー削除の確認',
                text: 'この保存ビューを削除してもよろしいですか？',
                icon: 'warning',
                showCancelButton: true,
                confirmButtonColor: '#d33',
                cancelButtonColor: '#6c757d',
                confirmButtonText: '削除',
                cancelButtonText: 'キャンセル'
            }).then((result) => {
                if (result.isConfirmed) {
                    $.ajax({
                        url: `/api/saved-views/${viewId}`,
                        type: 'DELETE',
                        success: function(res) {
                            if (res && res.code === 200) {
                                SES.toast.success('保存ビューを削除しました');
                                self.loadViews();
                            } else {
                                SES.toast.error(res.message || '削除に失敗しました');
                            }
                        },
                        error: function(xhr) {
                            let msg = '削除に失敗しました';
                            try {
                                const json = JSON.parse(xhr.responseText);
                                if (json.message) msg = json.message;
                            } catch(e) {}
                            SES.toast.error(msg);
                        }
                    });
                }
            });
        }
    };
})();
