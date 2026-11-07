# ==========================================
# Bytify\backend\scraper\ultimate_scraper.py (Final & Complete)
# ==========================================
import os
import time
import re
import json
import threading
from urllib.parse import urljoin
from bs4 import BeautifulSoup
import undetected_chromedriver as uc
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import TimeoutException

# Membungkam bug bawaan undetected_chromedriver di Windows: "OSError: [WinError 6] The handle is invalid"
uc.Chrome.__del__ = lambda self: None

# Lock agar antar-thread tidak berebut melakukan patch file .exe secara bersamaan
_DRIVER_INIT_LOCK = threading.Lock()
_CACHED_DRIVER_PATH = None

def create_chrome_options():
    options = uc.ChromeOptions()
    options.add_argument("--headless=new")
    options.add_argument("--disable-gpu")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument("--window-size=1280,720")
    options.add_argument("--mute-audio")
    options.add_argument("--disable-extensions")
    options.add_argument("--blink-settings=imagesEnabled=false") # Matikan gambar agar loading 3x lebih cepat
    options.set_capability("goog:loggingPrefs", {"performance": "ALL"})
    return options

def init_stealth_driver():
    """Inisialisasi driver yang aman untuk dijalankan secara bersamaan (Thread-Safe)."""
    global _CACHED_DRIVER_PATH
    with _DRIVER_INIT_LOCK:
        if _CACHED_DRIVER_PATH and os.path.exists(_CACHED_DRIVER_PATH):
            driver = uc.Chrome(
                options=create_chrome_options(),
                driver_executable_path=_CACHED_DRIVER_PATH,
                version_main=156
            )
        else:
            driver = uc.Chrome(options=create_chrome_options(), version_main=156)
            _CACHED_DRIVER_PATH = driver.patcher.executable_path

    driver.set_page_load_timeout(30)
    return driver

def wait_for_cloudflare(driver, timeout=10):
    try:
        WebDriverWait(driver, timeout).until_not(EC.title_contains("Just a moment"))
        WebDriverWait(driver, timeout).until_not(EC.title_contains("Cloudflare"))
    except TimeoutException:
        pass

def extract_network_media(driver):
    """Menyadap log jaringan untuk mendapatkan link streaming .m3u8 atau .mp4."""
    media_urls = set()
    try:
        logs = driver.get_log("performance")
        for entry in logs:
            log = json.loads(entry["message"])["message"]
            if log["method"] in ["Network.requestWillBeSent", "Network.responseReceived"]:
                try:
                    req = log["params"].get("request") or log["params"].get("response")
                    url = req.get("url", "")
                    if re.search(r'\.(m3u8|mp4|mkv)(\?|$)', url.lower()) or 'videoplayback' in url.lower():
                        media_urls.add(url)
                except Exception:
                    continue
    except Exception:
        pass
    return list(media_urls)

def scrape_with_driver(driver, target_url):
    """Mengekstrak URL menggunakan instance browser yang sudah menyala secara agresif."""
    data = {
        "id": re.sub(r'[^a-zA-Z0-9]', '-', target_url.rstrip('/').split('/')[-1]).lower(),
        "title": "Unknown Title",
        "poster_url": None,
        "lk21_url": target_url,
        "nd_url": None,
        "dl_urls": [],
        "stream_urls": []
    }
    
    try:
        driver.get_log("performance")
    except Exception:
        pass

    driver.get(target_url)
    wait_for_cloudflare(driver)

    # Scroll ke bawah untuk memicu pemuatan elemen dinamis (lazy load player & poster)
    try:
        driver.execute_script("window.scrollTo(0, 600);")
        time.sleep(1)
    except Exception:
        pass

    soup = BeautifulSoup(driver.page_source, 'html.parser')
    
    # 1. Ambil Judul Film
    title_tag = soup.find('h1') or soup.find('h2')
    if title_tag:
        data["title"] = title_tag.get_text().strip()
        
    # 2. Ambil Poster Film secara Agresif
    poster_tag = (
        soup.find('img', class_=re.compile(r'poster|thumb|cover|image|photo', re.I)) or 
        soup.select_one('.content-poster img') or
        soup.select_one('.poster img') or
        soup.select_one('.thumb img') or
        soup.find('meta', property='og:image')
    )

    if poster_tag:
        if poster_tag.name == 'meta':
            img_url = poster_tag.get('content')
        else:
            img_url = (
                poster_tag.get('src') or 
                poster_tag.get('data-src') or 
                poster_tag.get('data-lazy-src') or 
                poster_tag.get('srcset')
            )
            
        if img_url:
            if ' ' in img_url:
                img_url = img_url.split(' ')[0]
            data["poster_url"] = urljoin(target_url, img_url)

    # 3. Cari ND_URL (Iframe Embed Player) secara Agresif & Mendalam
    for iframe in soup.find_all('iframe'):
        src = iframe.get('src') or iframe.get('data-src') or iframe.get('data-url') or iframe.get('data-link')
        if src and any(k in src.lower() for k in ['embed', 'play', 'player', 'stream', 'cloud', 'hydrax', 'turboviplay', 'video', 'iframe']):
            data["nd_url"] = urljoin(target_url, src)
            break

    # Jika masih null, cari lewat tombol pilihan server (server-select / player option) di LK21
    if not data["nd_url"]:
        try:
            server_buttons = driver.find_elements(By.CSS_SELECTOR, ".player-option, .servers li, [data-server], .dropdown-menu a")
            if server_buttons:
                driver.execute_script("arguments[0].click();", server_buttons[0])
                time.sleep(1.5)
                soup_refreshed = BeautifulSoup(driver.page_source, 'html.parser')
                for iframe in soup_refreshed.find_all('iframe'):
                    src = iframe.get('src') or iframe.get('data-src')
                    if src:
                        data["nd_url"] = urljoin(target_url, src)
                        break
        except Exception:
            pass

    # 4. Cari Link Download (DL_URL)
    dl_keywords = ['download', 'racaty', 'hxfile', 'drive.google', 'terabox', 'uptobox', 'mediafire', 'pixeldrain', 'lk21.de']
    for a in soup.find_all('a', href=True):
        href = a['href']
        text = a.get_text().lower()
        if any(k in href.lower() or k in text for k in dl_keywords):
            if href.startswith('http') and 'imdb.com' not in href:
                data["dl_urls"].append(href)

    # 5. Ekstrak Direct Stream (M3U8/MP4) dari ND_URL
    if data["nd_url"]:
        driver.get(data["nd_url"])
        try:
            btn = WebDriverWait(driver, 3).until(
                EC.presence_of_element_located((By.CSS_SELECTOR, "button, .play-btn, .vjs-big-play-button, .jw-icon-display, #player"))
            )
            driver.execute_script("arguments[0].click();", btn)
        except Exception:
            pass
        
        for _ in range(6):
            time.sleep(0.5)
            found = extract_network_media(driver)
            if found:
                data["stream_urls"].extend(found)
                break

        if not data["stream_urls"]:
            inner_soup = BeautifulSoup(driver.page_source, 'html.parser')
            for video in inner_soup.find_all(['video', 'source']):
                src = video.get('src')
                if src and not src.startswith('blob:'):
                    data["stream_urls"].append(src)

    data["dl_urls"] = list(set(data["dl_urls"]))
    data["stream_urls"] = list(set(data["stream_urls"]))
    return data

def lk21_scrape(target_url):
    """Fungsi fallback untuk tes 1 URL tunggal."""
    driver = init_stealth_driver()
    try:
        return scrape_with_driver(driver, target_url)
    finally:
        try:
            driver.quit()
        except Exception:
            pass