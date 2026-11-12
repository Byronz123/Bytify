@echo off
title Bytify Auto-Sync Engine (Movies + TV Garden)
cd /d E:\Portofolio\Karya\Bytify\backend\scraper

:: Judul film banyak memakai aksara CJK atau emoji. Tanpa ini, mencetak judul
:: tersebut ke log Windows gagal dengan galat 'charmap' dan film itu ikut
:: GAGAL di-scrape.
set PYTHONIOENCODING=utf-8

:: Semua keluaran dicatat ke berkas supaya bisa diperiksa setelah berjalan
:: tanpa pengawasan. Berkas log ditimpa tiap kali daemon dimulai.
set LOG=E:\Portofolio\Karya\Bytify\backend\scraper\auto-sync.log

echo ======================================================== > "%LOG%"
echo [%DATE% %TIME%] Memulai Sistem Auto-Sync Bytify... >> "%LOG%"
echo ======================================================== >> "%LOG%"

:: Crawl film baru -> build catalog.json -> scrape TV Garden -> terbitkan ke GitHub.
:: Tahap terbit itulah yang memicu deploy ke Cloudflare Pages.
python auto_sync_daemon.py >> "%LOG%" 2>&1

:: Sengaja TIDAK memakai "pause": saat dijalankan Task Scheduler, pause akan
:: menggantung jendela selamanya dan tugas dianggap tidak pernah selesai.
echo [%DATE% %TIME%] Daemon berhenti. Lihat %LOG% untuk detailnya. >> "%LOG%"
