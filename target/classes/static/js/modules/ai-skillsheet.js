function generateSkillSheet(engineerId) {
    Swal.fire({
        title: 'スキルシートの自動生成',
        text: 'AIが経歴情報を分析し、氏名などの個人情報を匿名化したうえで、業界標準フォーマットのPDFを生成します。よろしいですか？',
        icon: 'question',
        showCancelButton: true,
        confirmButtonColor: '#6f42c1',
        cancelButtonColor: '#6c757d',
        confirmButtonText: '<i class="bi bi-magic me-1"></i> 生成する',
        cancelButtonText: 'キャンセル'
    }).then((result) => {
        if (result.isConfirmed) {
            Swal.fire({
                title: '生成中...',
                html: 'AIが経歴を分析し、PDFを組み立てています<br><br><div class="spinner-border text-accent-purple" role="status"></div>',
                allowOutsideClick: false,
                showConfirmButton: false
            });

            // Simulate API call
            $.ajax({
                url: '/api/ai/skill-sheet/generate',
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({ engineerId: engineerId }),
                success: function(res) {
                    setTimeout(() => {
                        Swal.fire({
                            title: '生成完了！',
                            text: 'スキルシート(PDF)の生成に成功しました。',
                            icon: 'success',
                            confirmButtonColor: '#20c997',
                            confirmButtonText: 'ダウンロード'
                        }).then(() => {
                            // In real app, trigger PDF download or open in new tab
                            Toast.success('【モック】PDFのダウンロードを開始しました');
                        });
                    }, 2000);
                },
                error: function(err) {
                    setTimeout(() => {
                        Swal.fire({
                            title: '生成完了！(モック)',
                            text: 'スキルシート(PDF)の生成に成功しました。',
                            icon: 'success',
                            confirmButtonColor: '#20c997',
                            confirmButtonText: 'ダウンロード'
                        }).then(() => {
                            Toast.success('【モック】PDFのダウンロードを開始しました');
                        });
                    }, 2000);
                }
            });
        }
    });
}
