@echo off
rem Windows 一键启动：双击运行。网页服务 8080 + 电脑串流服务 8090。
chcp 65001 >nul
cd /d "%~dp0"
node start_all.mjs %*
pause
