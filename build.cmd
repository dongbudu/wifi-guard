@echo off
rem 双击即可重新打包。也可以用 PowerShell 手动执行 build.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
pause
