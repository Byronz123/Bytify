# ==========================================
# Bytify/backend/scraper/ultimate_scraper_v2.py
# Versi perbaikan: Anti-Junk, Deep M3U8 Extraction, TMDB Enrichment
# ==========================================
import os
import time
import re
import json
import hashlib
import threading
from urllib.parse import urljoin, urlparse, parse_qs
from bs4 import BeautifulSoup
import undetected_chromedriver as uc
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.common.exceptions import TimeoutException

# Membungkam bug bawaan undetected_chromedriver di Windows
uc.Chrome.__del__ = lambda self: None

_DRIVER_INIT_LOCK = threading.Lock()
_CACHED_DRIVER_PATH = None

# ============================================================
# 1. BLACKLIST & FILTER CONFIGURATION
# ============================================================

# Domain/path yang PASTI junk — video iklan, kampanye, atau sampah
JUNK_URL_PATTERNS = [
    # Anti-gambling campaign videos
    r'stopjudi',
    r'donasi\.assetsy',
    r'anti[-_]?judi',
    r'stop[-_]?gambling',
    # Common ad/tracker networks
    r'doubleclick\.net',
    r'googlesyndication\.com',
    r'popads\.net',
    r'popcash\.net',
    r'propellerads\.com',
    r'adsterra\.com',
    r'exoclick\.com',
    r'juicyads\.com',
    r'onclickads\.net',
    r'histats\.com',
    r'trafficjunky\.com',
    r'clickadu\.com',
    r'hilltopads\.com',
    r'monetag\.com',
    r'a-ads\.com',
    r'adcash\.com',
    # Tracking pixels & analytics
    r'google-analytics\.com',
    r'facebook\.com/tr',
    r'pixel\.quantserve',
    r'scorecardresearch\.com',
    # Common ad paths
    r'/ads/',
    r'/advert/',
    r'/banner/',
    r'/popup/',
    r'/sponsor/',
    r'judol',           # slang untuk judi online
    r'slot[-_]?gacor',
    r'togel',
    r'maxwin',
]

# Compile menjadi satu regex besar untuk performa
_JUNK_REGEX = re.compile('|'.join(JUNK_URL_PATTERNS), re.IGNORECASE)

# Domain embed player yang VALID dan dikenal
VALID_EMBED_DOMAINS = [
    'videonode.de',
    'hydrax.net',
    'turboviplay.com',
    'turbovid.me',
    'castlehoud.com',
    'filemoon.sx',
    'filemoon.to',
    'streamwish.to',
    'vidhide.com',
    'doodstream.com',
    'mixdrop.co',
    'upstream.to',
    'streamtape.com',
    'mp4upload.com',
    'krakenfiles.com',
]

# Extension media yang valid
VALID_MEDIA_EXTENSIONS = ['.m3u8', '.mp4', '.mkv', '.webm', '.ts']


def is_junk_url(url: str) -> bool:
    """Cek apakah URL adalah junk/iklan/sampah."""
    if not url:
        return True
    return bool(_JUNK_REGEX.search(url))


def is_valid_media_url(url: str) -> bool:
    """Cek apakah URL adalah media stream yang valid (bukan junk)."""
    if not url or is_junk_url(url):
        return False
    lower = url.lower()
    # Harus mengandung extension media atau keyword streaming
    return (
        any(ext in lower for ext in VALID_MEDIA_EXTENSIONS) or
        'videoplayback' in lower or
        'master.m3u8' in lower or
        'index.m3u8' in lower or
        '/hls/' in lower or
        '/video/' in lower
    )


def is_valid_embed_url(url: str) -> bool:
    """Cek apakah URL embed berasal dari player yang dikenal."""
    if not url or is_junk_url(url):
        return False
    lower = url.lower()
    return any(domain in lower for domain in VALID_EMBED_DOMAINS)


# ============================================================
# 2. CHROME DRIVER SETUP
# ============================================================

def create_chrome_options():
    options = uc.ChromeOptions()
    options.add_argument("--headless=new")
    options.add_argument("--disable-gpu")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument("--window-size=1280,720")
    options.add_argument("--mute-audio")
    options.add_argument("--disable-extensions")
    # Jangan matikan gambar — kita butuh poster
    options.set_capability("goog:loggingPrefs", {"performance": "ALL"})
    return options


def init_stealth_driver():
    """Inisialisasi driver thread-safe."""
    global _CACHED_DRIVER_PATH
    with _DRIVER_INIT_LOCK:
        if _CACHED_DRIVER_PATH and os.path.exists(_CACHED_DRIVER_PATH):
            driver = uc.Chrome(
                options=create_chrome_options(),
                driver_executable_path=_CACHED_DRIVER_PATH,
                version_main=136
            )
        else:
            driver = uc.Chrome(options=create_chrome_options(), version_main=136)
            _CACHED_DRIVER_PATH = driver.patcher.executable_path
    driver.set_page_load_timeout(30)
    return driver


def wait_for_cloudflare(driver, timeout=10):
    try:
        WebDriverWait(driver, timeout).until_not(EC.title_contains("Just a moment"))
        WebDriverWait(driver, timeout).until_not(EC.title_contains("Cloudflare"))
    except TimeoutException:
        pass


# ============================================================
# 3. DEEP MEDIA EXTRACTION (Anti-Junk)
# ============================================================

def extract_network_media_clean(driver):
    """
    Menyadap log jaringan untuk mendapatkan link streaming,
    dengan filter agresif terhadap junk URLs.
    """
    media_urls = []
    seen = set()
    try:
        logs = driver.get_log("performance")
        for entry in logs:
            try:
                log = json.loads(entry["message"])["message"]
                if log["method"] not in ["Network.requestWillBeSent", "Network.responseReceived"]:
                    continue
                req = log["params"].get("request") or log["params"].get("response")
                if not req:
                    continue
                url = req.get("url", "")
                if not url or url in seen:
                    continue
                seen.add(url)

                # Filter: hanya ambil URL media yang valid dan bukan junk
                if is_valid_media_url(url):
                    # Prioritaskan m3u8 di awal list
                    if '.m3u8' in url.lower():
                        media_urls.insert(0, url)
                    else:
                        media_urls.append(url)
            except Exception:
                continue
    except Exception:
        pass
    return media_urls


def deep_extract_from_embed(driver, embed_url, max_wait=8):
    """
    Navigasi ke embed URL dan ekstrak m3u8/mp4 dari dalam iframe player.
    Strategi bertingkat:
    1. Intercept network requests
    2. Cari di page source (JS variables, jwplayer setup, video tags)
    3. Klik tombol play dan intercept lagi
    """
    media_urls = []

    if not embed_url or is_junk_url(embed_url):
        return media_urls

    try:
        # Bersihkan log lama
        try:
            driver.get_log("performance")
        except Exception:
            pass

        driver.get(embed_url)
        time.sleep(2)

        # Strategi 1: Cek network logs setelah halaman dimuat
        found = extract_network_media_clean(driver)
        if found:
            media_urls.extend(found)

        # Strategi 2: Parse page source untuk URL media tersembunyi
        page_source = driver.page_source

        # Cari m3u8 di dalam JavaScript (jwplayer, videojs, plyr, dll)
        m3u8_patterns = [
            r'(?:file|source|src|url|video_url|stream_url|hls_url|manifest)\s*[:=]\s*["\']([^"\']*?\.m3u8[^"\']*)',
            r'["\']([^"\']*?\.m3u8\??[^"\']*)["\']',
            r'["\']([^"\']*?/hls/[^"\']*)["\']',
            r'["\']([^"\']*?/master\.m3u8[^"\']*)["\']',
            r'["\']([^"\']*?/index\.m3u8[^"\']*)["\']',
        ]
        for pattern in m3u8_patterns:
            for match in re.finditer(pattern, page_source, re.IGNORECASE):
                url = match.group(1)
                if is_valid_media_url(url) and url not in media_urls:
                    media_urls.insert(0, url)  # m3u8 prioritas tinggi

        # Cari mp4 di source
        mp4_patterns = [
            r'(?:file|source|src|url|video_url)\s*[:=]\s*["\']([^"\']*?\.mp4[^"\']*)',
            r'["\']([^"\']*?\.mp4\??[^"\']*)["\']',
        ]
        for pattern in mp4_patterns:
            for match in re.finditer(pattern, page_source, re.IGNORECASE):
                url = match.group(1)
                if is_valid_media_url(url) and url not in media_urls:
                    media_urls.append(url)

        # Cari <video> dan <source> tags
        soup = BeautifulSoup(page_source, 'html.parser')
        for tag in soup.find_all(['video', 'source']):
            src = tag.get('src') or tag.get('data-src')
            if src and not src.startswith('blob:') and is_valid_media_url(src):
                if src not in media_urls:
                    media_urls.append(src)

        # Strategi 3: Klik tombol play dan tunggu stream muncul
        if not media_urls:
            try:
                play_selectors = [
                    "button.vjs-big-play-button",
                    ".jw-icon-display",
                    ".play-btn",
                    "#player",
                    "button[aria-label='Play']",
                    ".plyr__control--overlaid",
                    "[class*='play']",
                    "video",
                ]
                for selector in play_selectors:
                    try:
                        btn = driver.find_element(By.CSS_SELECTOR, selector)
                        driver.execute_script("arguments[0].click();", btn)
                        break
                    except Exception:
                        continue

                # Tunggu dan intercept
                for _ in range(max_wait * 2):
                    time.sleep(0.5)
                    found = extract_network_media_clean(driver)
                    if found:
                        media_urls.extend(found)
                        break
            except Exception:
                pass

    except Exception as e:
        print(f"    [!] Error saat ekstraksi embed: {e}")

    # Deduplicate dan pertahankan urutan (m3u8 duluan)
    seen = set()
    unique = []
    for url in media_urls:
        if url not in seen:
            seen.add(url)
            unique.append(url)
    return unique


# ============================================================
# 4. METADATA EXTRACTION (Lebih Lengkap)
# ============================================================

def extract_metadata(soup, target_url):
    """Ekstrak metadata lengkap dari halaman detail film."""
    data = {
        "title": "",
        "overview": "",
        "poster_url": None,
        "backdrop_url": None,
        "genres": [],
        "rating": None,
        "release_year": None,
        "duration_min": None,
        "language": "id",
    }

    # Judul
    title_tag = soup.find('h1') or soup.find('h2')
    if title_tag:
        data["title"] = title_tag.get_text().strip()

    # Sinopsis dari <blockquote>
    blockquote = soup.find('blockquote')
    if blockquote:
        # Hapus tag <strong> (biasanya label "Synopsis")
        for strong in blockquote.find_all('strong'):
            strong.decompose()
        synopsis = blockquote.get_text().strip()
        synopsis = re.sub(r'^synopsis\s*', '', synopsis, flags=re.IGNORECASE).strip()
        if len(synopsis) > 10:
            data["overview"] = synopsis

    # Poster - strategi berlapis
    poster_candidates = [
        soup.select_one('.content-poster img'),
        soup.select_one('.poster img'),
        soup.select_one('.thumb img'),
        soup.find('img', class_=re.compile(r'poster|thumb|cover', re.I)),
    ]
    for tag in poster_candidates:
        if tag:
            img_url = tag.get('src') or tag.get('data-src') or tag.get('data-lazy-src')
            if img_url:
                if ' ' in img_url:
                    img_url = img_url.split(' ')[0]
                data["poster_url"] = urljoin(target_url, img_url)
                break

    # Fallback ke og:image
    if not data["poster_url"]:
        og = soup.find('meta', property='og:image')
        if og and og.get('content'):
            data["poster_url"] = og['content']

    # Backdrop (kalau ada)
    data["backdrop_url"] = data["poster_url"]

    # Rating, Durasi, Tahun dari info-detail sidebar LK21
    detail_mapping = {
        'imdb': 'rating',
        'durasi': 'duration_raw',
        'diterbitkan': 'release_raw',
        'kualitas': 'quality',
    }
    for h2 in soup.find_all('h2'):
        label = h2.get_text().strip().lower()
        h3 = h2.find_next_sibling('h3')
        if h3 and label in detail_mapping:
            value = h3.get_text().strip()
            key = detail_mapping[label]
            if key == 'rating':
                match = re.search(r'(\d+(?:[.,]\d+)?)', value)
                if match:
                    score = float(match.group(1).replace(',', '.'))
                    data["rating"] = round(min(score, 10.0), 1)
            elif key == 'duration_raw':
                match = re.search(r'(\d+)', value)
                if match:
                    data["duration_min"] = int(match.group(1))
            elif key == 'release_raw':
                match = re.search(r'((?:19|20)\d{2})', value)
                if match:
                    data["release_year"] = int(match.group(1))

    # Genre
    genres = []
    for a in soup.find_all('a', href=True):
        if '/genre/' in a['href']:
            genre = a.get_text().strip()
            if genre and genre not in genres:
                genres.append(genre)
    data["genres"] = genres

    # Tahun dari judul kalau belum ada
    if not data["release_year"] and data["title"]:
        match = re.search(r'\((\d{4})\)', data["title"])
        if match:
            data["release_year"] = int(match.group(1))

    return data


# ============================================================
# 5. MAIN SCRAPER FUNCTION
# ============================================================

def scrape_with_driver(driver, target_url):
    """
    Mengekstrak data film secara lengkap dengan filter anti-junk.
    Output kompatibel dengan kontrak MovieDto Bytify.
    """
    slug = re.sub(r'[^a-zA-Z0-9]', '-', target_url.rstrip('/').split('/')[-1]).lower()
    slug = re.sub(r'-+', '-', slug).strip('-')

    data = {
        "id": slug,
        "type": "movie",
        "title": "Unknown Title",
        "overview": "",
        "posterUrl": None,
        "backdropUrl": None,
        "releaseYear": None,
        "durationMin": None,
        "genres": [],
        "rating": None,
        "language": "id",
        "videoUrl": None,        # Direct m3u8/mp4 (prioritas tertinggi)
        "embedUrl": None,        # Embed iframe URL (fallback)
        "trailerUrl": None,
        "subtitles": [],
        "lk21_url": target_url,  # Referensi internal
        "dl_urls": [],
        "stream_urls": [],       # Semua stream URL yang ditemukan (sudah difilter)
    }

    # Bersihkan performance log
    try:
        driver.get_log("performance")
    except Exception:
        pass

    # Muat halaman utama
    driver.get(target_url)
    wait_for_cloudflare(driver)

    # Scroll untuk trigger lazy loading
    try:
        driver.execute_script("window.scrollTo(0, 600);")
        time.sleep(1)
    except Exception:
        pass

    soup = BeautifulSoup(driver.page_source, 'html.parser')

    # ---- Ekstrak Metadata ----
    meta = extract_metadata(soup, target_url)
    data["title"] = meta["title"] or data["title"]
    data["overview"] = meta["overview"]
    data["posterUrl"] = meta["poster_url"]
    data["backdropUrl"] = meta["backdrop_url"]
    data["genres"] = meta["genres"]
    data["rating"] = meta["rating"]
    data["releaseYear"] = meta["release_year"]
    data["durationMin"] = meta["duration_min"]

    # Deteksi apakah ini series
    is_series = bool(soup.find('div', class_=re.compile(r'episode|serial', re.I)))
    if is_series:
        data["type"] = "series"

    # ---- Cari Embed URL (iframe player) ----
    embed_candidates = []
    for iframe in soup.find_all('iframe'):
        src = (iframe.get('src') or iframe.get('data-src') or
               iframe.get('data-url') or iframe.get('data-link'))
        if src:
            full_src = urljoin(target_url, src)
            if is_valid_embed_url(full_src):
                embed_candidates.append(full_src)
            elif not is_junk_url(full_src) and any(k in src.lower() for k in ['embed', 'play', 'player', 'stream']):
                embed_candidates.append(full_src)

    # Jika tidak ditemukan iframe, coba klik server button
    if not embed_candidates:
        try:
            server_buttons = driver.find_elements(
                By.CSS_SELECTOR,
                ".player-option, .servers li, [data-server], .dropdown-menu a"
            )
            if server_buttons:
                driver.execute_script("arguments[0].click();", server_buttons[0])
                time.sleep(1.5)
                soup_refreshed = BeautifulSoup(driver.page_source, 'html.parser')
                for iframe in soup_refreshed.find_all('iframe'):
                    src = iframe.get('src') or iframe.get('data-src')
                    if src:
                        full_src = urljoin(target_url, src)
                        if not is_junk_url(full_src):
                            embed_candidates.append(full_src)
        except Exception:
            pass

    # Pilih embed URL terbaik
    if embed_candidates:
        # Prioritaskan domain embed yang dikenal
        known = [u for u in embed_candidates if is_valid_embed_url(u)]
        data["embedUrl"] = known[0] if known else embed_candidates[0]

    # ---- Cari Link Download (DL_URL) ----
    dl_keywords = ['download', 'racaty', 'hxfile', 'drive.google', 'terabox',
                   'uptobox', 'mediafire', 'pixeldrain', 'krakenfiles']
    for a in soup.find_all('a', href=True):
        href = a['href']
        text = a.get_text().lower()
        if any(k in href.lower() or k in text for k in dl_keywords):
            if href.startswith('http') and 'imdb.com' not in href and not is_junk_url(href):
                data["dl_urls"].append(href)

    # ---- Deep Extract Stream dari Embed ----
    if data["embedUrl"]:
        streams = deep_extract_from_embed(driver, data["embedUrl"])
        data["stream_urls"] = streams

        # Set videoUrl ke stream terbaik (m3u8 > mp4)
        for stream in streams:
            if '.m3u8' in stream.lower():
                data["videoUrl"] = stream
                break
        if not data["videoUrl"] and streams:
            data["videoUrl"] = streams[0]

    # Jika tidak ada direct stream, gunakan embed sebagai videoUrl
    if not data["videoUrl"] and data["embedUrl"]:
        data["videoUrl"] = data["embedUrl"]

    # ---- Deduplicate ----
    data["dl_urls"] = list(set(data["dl_urls"]))
    data["stream_urls"] = list(set(data["stream_urls"]))

    return data


def lk21_scrape(target_url):
    """Fungsi untuk tes 1 URL tunggal."""
    driver = init_stealth_driver()
    try:
        return scrape_with_driver(driver, target_url)
    finally:
        try:
            driver.quit()
        except Exception:
            pass


# ============================================================
# 6. TMDB ENRICHMENT (Opsional — aktifkan dengan API key)
# ============================================================

def enrich_with_tmdb(movie_data, tmdb_api_key=None):
    """
    Perkaya metadata film dengan data dari TMDB.
    Membutuhkan library `requests` dan TMDB API key.
    """
    if not tmdb_api_key:
        return movie_data

    try:
        import requests
    except ImportError:
        return movie_data

    title = movie_data.get("title", "")
    year = movie_data.get("releaseYear")

    # Bersihkan judul dari tahun dalam kurung
    clean_title = re.sub(r'\s*\(\d{4}\)\s*$', '', title).strip()
    if not clean_title:
        return movie_data

    try:
        params = {
            "api_key": tmdb_api_key,
            "query": clean_title,
            "language": "id-ID",
        }
        if year:
            params["year"] = year

        resp = requests.get(
            "https://api.themoviedb.org/3/search/movie",
            params=params,
            timeout=10
        )
        resp.raise_for_status()
        results = resp.json().get("results", [])

        if not results:
            # Coba tanpa filter tahun
            params.pop("year", None)
            resp = requests.get(
                "https://api.themoviedb.org/3/search/movie",
                params=params,
                timeout=10
            )
            results = resp.json().get("results", [])

        if results:
            best = results[0]

            # Hanya override jika data TMDB lebih lengkap
            if best.get("overview") and len(best["overview"]) > len(movie_data.get("overview", "")):
                movie_data["overview"] = best["overview"]

            if best.get("poster_path") and not movie_data.get("posterUrl"):
                movie_data["posterUrl"] = f"https://image.tmdb.org/t/p/w500{best['poster_path']}"

            if best.get("backdrop_path"):
                movie_data["backdropUrl"] = f"https://image.tmdb.org/t/p/w1280{best['backdrop_path']}"

            if best.get("vote_average") and not movie_data.get("rating"):
                movie_data["rating"] = round(best["vote_average"], 1)

            if best.get("release_date") and not movie_data.get("releaseYear"):
                match = re.match(r'(\d{4})', best["release_date"])
                if match:
                    movie_data["releaseYear"] = int(match.group(1))

    except Exception as e:
        print(f"    [TMDB] Gagal enrichment untuk '{clean_title}': {e}")

    return movie_data
