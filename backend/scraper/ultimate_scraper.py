# ==========================================
# Bytify/backend/scraper/ultimate_scraper.py
# Anti-Junk, Deep M3U8 Extraction, Metadata lengkap, TMDB Enrichment
# ==========================================
import os
import time
import re
import json
import hashlib
import threading
import subprocess
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

def detect_chrome_major_version():
    """Deteksi versi mayor Chrome terpasang (Windows registry, lalu CLI). Override: env CHROME_VERSION."""
    env_ver = os.environ.get("CHROME_VERSION", "").strip()
    if env_ver.isdigit():
        return int(env_ver)
    commands = [
        r'reg query "HKEY_CURRENT_USER\Software\Google\Chrome\BLBeacon" /v version',
        r'reg query "HKEY_LOCAL_MACHINE\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\Google Chrome" /v DisplayVersion',
        r'reg query "HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Google Chrome" /v DisplayVersion',
        "google-chrome --version",
        "chromium --version",
    ]
    for cmd in commands:
        try:
            output = subprocess.check_output(cmd, shell=True, stderr=subprocess.DEVNULL, text=True, timeout=10)
            match = re.search(r"(\d+)\.\d+\.\d+\.\d+", output)
            if match:
                return int(match.group(1))
        except Exception:
            pass
    return None  # Biarkan undetected_chromedriver menebak sendiri


_CHROME_VERSION = None


def create_chrome_options():
    options = uc.ChromeOptions()
    options.add_argument("--headless=new")
    options.add_argument("--disable-gpu")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument("--window-size=1280,720")
    options.add_argument("--mute-audio")
    options.add_argument("--disable-extensions")
    options.add_argument("--autoplay-policy=no-user-gesture-required")
    # Gambar tetap aktif: dibutuhkan untuk mengambil poster
    options.set_capability("goog:loggingPrefs", {"performance": "ALL"})
    return options


def init_stealth_driver():
    """Inisialisasi driver thread-safe; versi Chrome dideteksi otomatis sekali saja."""
    global _CACHED_DRIVER_PATH, _CHROME_VERSION
    with _DRIVER_INIT_LOCK:
        if _CHROME_VERSION is None:
            _CHROME_VERSION = detect_chrome_major_version() or 0
        kwargs = {"options": create_chrome_options(), "use_subprocess": True}
        if _CHROME_VERSION:
            kwargs["version_main"] = _CHROME_VERSION
        if _CACHED_DRIVER_PATH and os.path.exists(_CACHED_DRIVER_PATH):
            kwargs["driver_executable_path"] = _CACHED_DRIVER_PATH
        driver = uc.Chrome(**kwargs)
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


# Nama lama tetap tersedia (dipakai tv_garden_scraper.py)
extract_network_media = extract_network_media_clean


def deep_extract_from_embed(driver, embed_url, max_wait=10, referer=None):
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

        if referer:
            try:
                driver.execute_cdp_cmd("Network.enable", {})
                driver.execute_cdp_cmd("Network.setExtraHTTPHeaders", {"headers": {"Referer": referer}})
            except Exception:
                pass
        driver.get(embed_url)
        time.sleep(3)

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

    if referer:
        try:
            driver.execute_cdp_cmd("Network.setExtraHTTPHeaders", {"headers": {}})
        except Exception:
            pass

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

# Selector di bawah ini diverifikasi langsung terhadap HTML asli
# tv12.lk21official.cc (struktur: div.movie-info > h1 / .info-tag / .tag-list).

def _json_ld_movie(soup):
    """Ambil objek schema.org Movie/TVSeries/TVEpisode dari JSON-LD."""
    for script in soup.find_all("script", type="application/ld+json"):
        raw = script.string or script.get_text() or ""
        try:
            payload = json.loads(raw)
        except Exception:
            continue
        stack = list(payload) if isinstance(payload, list) else [payload]
        while stack:
            obj = stack.pop()
            if isinstance(obj, dict):
                kinds = obj.get("@type")
                kinds = kinds if isinstance(kinds, list) else [kinds]
                if any(k in ("Movie", "TVSeries", "TVEpisode") for k in kinds):
                    return obj
                stack.extend(v for v in obj.values() if isinstance(v, (dict, list)))
            elif isinstance(obj, list):
                stack.extend(obj)
    return {}


def _meta(soup, *names):
    for name in names:
        tag = (soup.find("meta", attrs={"property": name}) or
               soup.find("meta", attrs={"name": name}))
        if tag and (tag.get("content") or "").strip():
            return tag["content"].strip()
    return ""


def _names(value):
    """Ambil daftar nama dari field JSON-LD actor/director."""
    out = []
    for entry in (value if isinstance(value, list) else [value]):
        if isinstance(entry, dict) and entry.get("name"):
            out.append(str(entry["name"]).strip())
        elif isinstance(entry, str) and entry.strip():
            out.append(entry.strip())
    return out


_DUR_HM = re.compile(r'^(?:(\d+)\s*h)?\s*(?:(\d+)\s*m)?$', re.I)
_RES_RE = re.compile(r'^\d{3,4}p$', re.I)
_QUAL_RE = re.compile(r'^[A-Za-z]{2,8}$')


def _parse_info_tag(info):
    """div.info-tag berisi span: kualitas (WEBDL), resolusi (1080p), durasi (1h 50m)."""
    quality = resolution = None
    duration_min = None
    for span in info.select(".info-tag span") if info else []:
        text = span.get_text(" ", strip=True)
        if not text:
            continue
        match = _DUR_HM.match(text)
        if match and (match.group(1) or match.group(2)):
            duration_min = int(match.group(1) or 0) * 60 + int(match.group(2) or 0)
        elif _RES_RE.match(text):
            resolution = text
        elif _QUAL_RE.match(text):
            quality = text.upper()
    # Bentuk lain: "95 min"
    if duration_min is None and info:
        match = re.search(r'(\d{2,3})\s*(?:min|menit)\b', info.get_text(" ", strip=True), re.I)
        if match:
            duration_min = int(match.group(1))
    return quality, resolution, duration_min


def _parse_rating(*values):
    for value in values:
        match = re.search(r'(\d+(?:[.,]\d+)?)', str(value or ""))
        if match:
            score = float(match.group(1).replace(',', '.'))
            if 0 < score <= 10:
                return round(score, 1)
    return None


def extract_metadata(soup, target_url):
    """
    Ekstrak metadata dari halaman detail LK21.
    Lingkupnya dibatasi ke div.movie-info agar menu genre situs dan
    kartu film lain di sidebar tidak ikut terambil.
    """
    data = {
        "title": "", "overview": "", "poster_url": None, "backdrop_url": None,
        "genres": [], "countries": [], "cast": [], "directors": [],
        "rating": None, "rating_users": None, "release_year": None,
        "duration_min": None, "quality": None, "resolution": None,
        "language": "id",
    }
    ld = _json_ld_movie(soup)
    info = soup.select_one(".movie-info")

    # Judul: h1 di dalam .movie-info bersih; og:title dan JSON-LD name
    # tercemar embel-embel "Lk21 Nonton ... Sub Indo | Streaming dan Download".
    h1 = (info.find("h1") if info else None) or soup.find("h1")
    title = h1.get_text(" ", strip=True) if h1 else ""
    if not title:
        title = re.sub(r'^lk21\s+nonton\s+', '',
                       str(ld.get("name") or _meta(soup, "og:title")), flags=re.I)
        title = re.split(r'\s+sub\s+indo\s*\|', title, flags=re.I)[0].strip()
    data["title"] = title

    # Sinopsis: div.synopsis. Deskripsi JSON-LD/og hanya template SEO, jadi diabaikan.
    synopsis = soup.select_one(".synopsis")
    if synopsis:
        # Saat .synopsis "collapsed", teks tampilnya terpotong "..." dan
        # versi utuhnya ada di atribut data-full.
        text = (synopsis.get("data-full") or "").strip()
        if not text:
            for strong in synopsis.find_all("strong"):
                strong.decompose()
            text = synopsis.get_text(" ", strip=True)
        text = re.sub(r'^(synopsis|sinopsis)\s*[:\-]?\s*', '', text, flags=re.I).strip()
        if len(text) >= 20:
            data["overview"] = text

    # Poster: og:image sudah poster film ini (bukan kartu film lain).
    poster = _meta(soup, "og:image", "twitter:image")
    if not poster:
        image = ld.get("image")
        if isinstance(image, dict):
            poster = image.get("url", "")
        elif isinstance(image, list) and image:
            poster = image[0]
        elif isinstance(image, str):
            poster = image
    if poster:
        data["poster_url"] = urljoin(target_url, str(poster).split(" ")[0])
    data["backdrop_url"] = data["poster_url"]

    # Rating: JSON-LD aggregateRating, lalu .rating-score / .rating-number
    agg = ld.get("aggregateRating") if isinstance(ld.get("aggregateRating"), dict) else {}
    score_el = soup.select_one(".rating-score") or soup.select_one(".rating-number")
    data["rating"] = _parse_rating(agg.get("ratingValue"),
                                   score_el.get_text(strip=True) if score_el else None)
    users = agg.get("ratingCount")
    if not users:
        users_el = soup.select_one(".rating-users")
        match = re.search(r'([\d.,]+)', users_el.get_text() if users_el else "")
        users = match.group(1).replace('.', '').replace(',', '') if match else None
    try:
        data["rating_users"] = int(users) if users else None
    except (TypeError, ValueError):
        data["rating_users"] = None

    # Kualitas, resolusi, durasi dari div.info-tag
    data["quality"], data["resolution"], data["duration_min"] = _parse_info_tag(info)
    if data["duration_min"] is None and ld.get("duration"):
        match = re.match(r'PT(?:(\d+)H)?(?:(\d+)M)?', str(ld["duration"]))
        if match and (match.group(1) or match.group(2)):
            data["duration_min"] = int(match.group(1) or 0) * 60 + int(match.group(2) or 0)

    # Genre: JSON-LD dulu; fallback ke .tag-list (BUKAN seluruh halaman,
    # karena a[href*="/genre/"] global akan menarik 27 genre dari menu situs).
    genres = [g for g in ([ld.get("genre")] if isinstance(ld.get("genre"), str)
                          else list(ld.get("genre") or [])) if g]
    if not genres and info:
        genres = [a.get_text(" ", strip=True)
                  for a in info.select('.tag-list a[href*="/genre/"]')]
    seen, clean = set(), []
    for genre in genres:
        key = str(genre).strip().lower()
        if key and key not in seen:
            seen.add(key)
            clean.append(str(genre).strip())
    data["genres"] = clean

    # Negara dari .tag-list
    if info:
        data["countries"] = [a.get_text(" ", strip=True)
                             for a in info.select('.tag-list a[href*="/country/"]')]

    data["cast"] = _names(ld.get("actor"))
    data["directors"] = _names(ld.get("director"))

    # Tahun rilis: datePublished, lalu "(2024)" di judul
    match = re.search(r'((?:19|20)\d{2})', str(ld.get("datePublished") or ""))
    if match:
        data["release_year"] = int(match.group(1))
    if data["release_year"] is None and data["title"]:
        match = re.search(r'\((\d{4})\)', data["title"])
        if match:
            data["release_year"] = int(match.group(1))

    return data


def extract_servers(soup, target_url):
    """
    Daftar server player. Diambil dari #player-select (paling lengkap,
    pada contoh nyata memuat turbovip yang tidak ada di #player-list)
    digabung dengan #player-list.
    """
    servers, seen = [], set()
    for option in soup.select("#player-select option"):
        url = (option.get("value") or "").strip()
        if url.startswith("http") and url not in seen and not is_junk_url(url):
            seen.add(url)
            servers.append({"server": (option.get("data-server") or "").lower(), "url": url})
    for link in soup.select("#player-list a[data-url], #player-list a[href^='http']"):
        url = (link.get("data-url") or link.get("href") or "").strip()
        if url.startswith("http") and url not in seen and not is_junk_url(url):
            seen.add(url)
            servers.append({"server": (link.get("data-server") or "").lower(), "url": url})
    # Fallback: iframe #main-player
    if not servers:
        iframe = soup.select_one("#main-player, iframe[src*='videonode'], iframe[src]")
        src = (iframe.get("src") or iframe.get("data-src") or "") if iframe else ""
        if src and not is_junk_url(src):
            servers.append({"server": "default", "url": urljoin(target_url, src)})
    return servers


# ============================================================
# 5. MAIN SCRAPER FUNCTION
# ============================================================

def scrape_with_driver(driver, target_url):
    """
    Ekstrak satu film dari halaman detail LK21.
    Output kompatibel dengan kontrak MovieDto Bytify.

    Catatan hasil pengujian pada situs asli:
      - URL embed videonode yang dibuka LANGSUNG akan dialihkan ke homepage,
        karena player mensyaratkan Referer dari halaman film. Maka scraper
        tidak menavigasi ke embed; URL server diambil dari DOM halaman film.
      - Satu-satunya media yang dimuat otomatis di halaman adalah video iklan
        donasi.assetsy.de/stopjudi/*.mp4. Itu disaring is_valid_media_url().
      - m3u8 asli baru muncul setelah melewati gerbang iklan popunder di dalam
        iframe. Aktifkan lewat env BYTIFY_DEEP=1 bila memang diperlukan.
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
        "countries": [],
        "cast": [],
        "directors": [],
        "rating": None,
        "ratingUsers": None,
        "quality": None,
        "resolution": None,
        "language": "id",
        "videoUrl": None,
        "embedUrl": None,
        "servers": [],
        "trailerUrl": None,
        "subtitles": [],       # LK21 memakai hardsub; tidak ada berkas .srt/.vtt
        "lk21_url": target_url,
        "dl_urls": [],
        "stream_urls": [],
    }

    try:
        driver.get_log("performance")  # buang log sisa halaman sebelumnya
    except Exception:
        pass

    driver.get(target_url)
    wait_for_cloudflare(driver)
    try:
        driver.execute_script("window.scrollTo(0, 600);")
        time.sleep(1)
    except Exception:
        pass

    soup = BeautifulSoup(driver.page_source, 'html.parser')

    meta = extract_metadata(soup, target_url)
    data["title"] = meta["title"] or data["title"]

    # Penjaga halaman-antara: LK21 kadang menyajikan interstisial
    # "Anda akan dialihkan ke Lk21 Nontondrama dalam N detik" alih-alih halaman
    # film. Tanpa server/metadata, entri ini tak dapat diputar — lewati agar
    # tidak mencemari katalog (sumber 58 entri sampah sebelumnya).
    if data["title"].strip().lower().startswith("anda akan dialihkan"):
        print(f"    [SKIP] Halaman antara Nontondrama terdeteksi: {target_url}")
        return None

    data["overview"] = meta["overview"]
    data["posterUrl"] = meta["poster_url"]
    data["backdropUrl"] = meta["backdrop_url"]
    data["genres"] = meta["genres"]
    data["countries"] = meta["countries"]
    data["cast"] = meta["cast"]
    data["directors"] = meta["directors"]
    data["rating"] = meta["rating"]
    data["ratingUsers"] = meta["rating_users"]
    data["releaseYear"] = meta["release_year"]
    data["durationMin"] = meta["duration_min"]
    data["quality"] = meta["quality"]
    data["resolution"] = meta["resolution"]

    if soup.select_one("#episode-list, .episode-list, [class*='episode']"):
        data["type"] = "series"

    # ---- Server player ----
    servers = extract_servers(soup, target_url)
    data["servers"] = servers
    server_urls = [s["url"] for s in servers]
    if server_urls:
        data["embedUrl"] = server_urls[0]

    # ---- Trailer ----
    trailer = soup.select_one("a[href*='youtube.com'], a[href*='youtu.be'], #yt-iframe[src]")
    if trailer:
        href = trailer.get("href") or trailer.get("src")
        if href and href.startswith("http"):
            data["trailerUrl"] = href

    # ---- Link download ----
    for a in soup.select(".movie-action a[href^='http'], a[href*='download']"):
        href = a.get("href", "")
        if href.startswith("http") and "imdb.com" not in href and not is_junk_url(href):
            if href not in data["dl_urls"]:
                data["dl_urls"].append(href)

    # ---- Media langsung dari log jaringan (iklan stopjudi tersaring) ----
    direct = extract_network_media_clean(driver)

    # ---- Deep extract opsional (harus melewati gerbang iklan; lambat) ----
    if os.environ.get("BYTIFY_DEEP") == "1" and data["embedUrl"]:
        direct += deep_extract_from_embed(driver, data["embedUrl"], referer=target_url)
        driver.get(target_url)  # kembali agar state konsisten

    # stream_urls = media langsung (jika ada) + seluruh URL server
    seen, streams = set(), []
    for url in direct + server_urls:
        if url and url not in seen:
            seen.add(url)
            streams.append(url)
    data["stream_urls"] = streams

    # videoUrl: m3u8 > mp4 > embed server pertama
    for url in streams:
        if '.m3u8' in url.lower():
            data["videoUrl"] = url
            break
    if not data["videoUrl"]:
        for url in streams:
            if '.mp4' in url.lower():
                data["videoUrl"] = url
                break
    if not data["videoUrl"]:
        data["videoUrl"] = data["embedUrl"]

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
