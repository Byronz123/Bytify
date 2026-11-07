echo off
title Bytify Auto-Sync Engine (Movies + TV Garden)
cd /d E:\Portofolio\Karya\Bytify\backend\scraper

echo ========================================================
echo [%DATE% %TIME%] Memulai Sistem Auto-Sync Bytify...
echo ========================================================

:: Menjalankan daemon otomatis (Crawl Film Baru -> Build DB -> Scrape TV Garden)
python auto_sync_daemon.py

pause