@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================================
echo  SES Manager Pro: impl(3000) / review(3001) 自動協働環境の起動
echo ============================================================
echo.

rem ============ opencode CLI の探索（PATH 優先、次に既知のインストール先） ============
set "OC="
where opencode >nul 2>&1
if not errorlevel 1 set "OC=opencode"
if not defined OC (
  for %%P in (
    "%USERPROFILE%\.opencode\bin\opencode.exe"
    "%LOCALAPPDATA%\opencode\opencode.exe"
    "%LOCALAPPDATA%\Programs\opencode\opencode.exe"
    "%APPDATA%\npm\opencode.cmd"
    "%USERPROFILE%\.local\bin\opencode.exe"
  ) do (
    if not defined OC if exist "%%~P" set "OC=%%~P"
  )
)
if not defined OC (
  echo [ERROR] opencode CLI が見つかりません。
  echo         PATH に opencode を追加するか、start-services.bat の検索パスを編集してください。
  pause
  exit /b 1
)
echo [INFO] opencode CLI : %OC%

rem ============ ポート使用状況の確認 ============
netstat -ano | findstr /c:":3000 " >nul 2>&1
if not errorlevel 1 echo [WARN] ポート 3000 は既に使用中です。既存プロセスを確認してください。
netstat -ano | findstr /c:":3001 " >nul 2>&1
if not errorlevel 1 echo [WARN] ポート 3001 は既に使用中です。既存プロセスを確認してください。

rem ============ impl / review サービスの起動 ============
start "opencode-impl-3000" cmd /k "set OPENCODE_CONFIG_DIR=%CD%\automation_profiles\impl && %OC% serve --port 3000"
start "opencode-review-3001" cmd /k "set OPENCODE_CONFIG_DIR=%CD%\automation_profiles\review && %OC% serve --port 3001"

echo [INFO] 起動を待機しています（最大60秒）...
set /a waited=0
:wait_loop
set "up3000="
set "up3001="
powershell -NoProfile -ExecutionPolicy Bypass -Command "$c=New-Object System.Net.Sockets.TcpClient; try{$c.Connect('127.0.0.1',3000);exit 0}catch{exit 1}finally{$c.Dispose()}"
if not errorlevel 1 set "up3000=1"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$c=New-Object System.Net.Sockets.TcpClient; try{$c.Connect('127.0.0.1',3001);exit 0}catch{exit 1}finally{$c.Dispose()}"
if not errorlevel 1 set "up3001=1"
if defined up3000 if defined up3001 goto :all_up
timeout /t 3 /nobreak >nul 2>&1 || ping -n 4 127.0.0.1 >nul
set /a waited+=3
if %waited% geq 60 goto :wait_done
goto :wait_loop

:all_up
echo [OK]  impl  (http://localhost:3000) 起動済み
echo [OK]  review(http://localhost:3001) 起動済み
goto :finish

:wait_done
echo [WARN] 起動待機がタイムアウトしました。別ウィンドウのログを確認してください。
if not defined up3000 echo        - impl  (3000): 応答なし
if not defined up3001 echo        - review(3001): 応答なし

:finish
echo.
echo 次に watchdog を実行してください:
echo     powershell -ExecutionPolicy Bypass -File watchdog.ps1
echo.
pause
