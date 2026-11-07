# ==========================================
# Bytify\backend\scraper\tv_garden_scraper.py
# ==========================================
import os
import re
import json
import time
import queue
import threading
import urllib.request
from bs4 import BeautifulSoup
from ultimate_scraper import init_stealth_driver, extract_network_media

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.abspath(os.path.join(BASE_DIR, "..", "data"))
LIVE_DB_FILE = os.path.join(DATA_DIR, "live_tv_database.json")
LIVE_M3U_FILE = os.path.join(DATA_DIR, "tv_garden_streams.m3u")

MAX_WORKERS = 10
lock = threading.Lock()
channels_db = []
existing_streams = set()

# Daftar negara prioritas untuk dipindai di TV Garden (Indonesia & Global)
TARGET_COUNTRIES = [
    ("id", "Indonesia"),
    ("my", "Malaysia"),
    ("sg", "Singapore"),
    ("jp", "Japan"),
    ("kr", "South Korea"),
    ("us", "United States"),
    ("gb", "United Kingdom"),
    ("sa", "Saudi Arabia")
]

def save_live_outputs():
    """Menyimpan hasil ke format JSON dan M3U8 agar langsung terbaca oleh live.mjs dan APK."""
    os.makedirs(DATA_DIR, exist_ok=True)
    
    # 1. Simpan JSON Database
    temp_json = LIVE_DB_FILE + ".tmp"
    with open(temp_json, "w", encoding="utf-8") as f:
        json.dump(channels_db, f, indent=2, ensure_ascii=False)
    os.replace(temp_json, LIVE_DB_FILE)

    # 2. Simpan File Playlist M3U8 Standar untuk Kompatibilitas Ekstra
    temp_m3u = LIVE_M3U_FILE + ".tmp"
    with open(temp_m3u, "w", encoding="utf-8") as f:
        f.write("#EXTM3U\n")
        for ch in channels_db:
            logo = ch.get("logo") or ""
            group = ch.get("category") or "General"
            country = ch.get("country") or "ID"
            name = ch.get("name") or "Unknown Channel"
            stream_url = ch.get("stream_url") or ""
            if stream_url:
                f.write(f'#EXTINF:-1 tvg-id="{ch["id"]}" tvg-logo="{logo}" tvg-country="{country}" group-title="{group}",{name}\n')
                f.write(f"{stream_url}\n")
    os.replace(temp_m3u, LIVE_M3U_FILE)

def scrape_tv_garden_country(driver, country_code, country_name):
    """Membuka halaman negara di TV Garden dan menyadap stream M3U8 aktif."""
    extracted = []
    target_url = f"https://tv.garden/{country_code}"
    
    try:
        driver.get_log("performance")
    except Exception:
        pass

    try:
        driver.get(target_url)
        time.sleep(3)
        
        soup = BeautifulSoup(driver.page_source, "html.parser")
        
        # Cari daftar tombol/elemen channel di halaman TV Garden
        channel_elements = driver.find_elements("css selector", "button, a[href*='/'], .channel-item, [role='button']")
        
        # Klik beberapa channel teratas untuk memicu request jaringan .m3u8
        clicked = 0
        for el in channel_elements:
            if clicked >= 15:
                break
            try:
                text = el.text.strip()
                if not text or len(text) < 2 or len(text) > 40:
                    continue
                if any(skip in text.lower() for skip in ["home", "search", "random", "favorite", "about", "country"]):
                    continue
                
                driver.execute_script("arguments[0].click();", el)
                time.sleep(1.2)
                
                media_links = extract_network_media(driver)
                for m_url in media_links:
                    if ".m3u8" in m_url.lower():
                        ch_id = re.sub(r'[^a-zA-Z0-9]', '-', f"{country_code}-{text}").lower().strip('-')
                        extracted.append({
                            "id": ch_id,
                            "name": text.splitlines()[0].strip(),
                            "country": country_code.upper(),
                            "category": f"TV Garden - {country_name}",
                            "logo": f"https://www.google.com/s2/favicons?domain=tv.garden&sz=128",
                            "stream_url": m_url,
                            "source": "tv.garden"
                        })
                        clicked += 1
                        break
            except Exception:
                continue
    except Exception as e:
        print(f"[!] Gagal memindai interaksi langsung {target_url}: {e}")

    return extracted

def fetch_direct_m3u_index(country_code, country_name):
    """Mengambil indeks stream langsung yang digunakan oleh ekosistem TV Garden / IPTV publik."""
    extracted = []
    m3u_url = f"https://iptv-org.github.io/iptv/countries/{country_code}.m3u"
    try:
        req = urllib.request.Request(m3u_url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            content = resp.read().decode("utf-8", errors="ignore")
            
        lines = [line.strip() for line in content.splitlines() if line.strip()]
        current_meta = {}
        
        for line in lines:
            if line.startswith("#EXTINF:"):
                name_match = line.split(",")[-1].strip()
                logo_match = re.search(r'tvg-logo="([^"]+)"', line)
                group_match = re.search(r'group-title="([^"]+)"', line)
                id_match = re.search(r'tvg-id="([^"]+)"', line)
                
                raw_id = id_match.group(1) if id_match else f"{country_code}-{name_match}"
                clean_id = re.sub(r'[^a-zA-Z0-9]', '-', raw_id).lower().strip('-')
                
                current_meta = {
                    "id": clean_id or f"ch-{len(extracted)}",
                    "name": name_match or "Live Channel",
                    "country": country_code.upper(),
                    "category": group_match.group(1) if group_match else country_name,
                    "logo": logo_match.group(1) if logo_match else None,
                    "source": "tv.garden-index"
                }
            elif line.startswith("http") and current_meta:
                if ".m3u8" in line.lower() or ".mpd" in line.lower():
                    current_meta["stream_url"] = line
                    extracted.append(current_meta)
                current_meta = {}
    except Exception as e:
        print(f"[!] Gagal mengambil indeks M3U {country_name}: {e}")
        
    return extracted

def worker_live_task(worker_id, task_queue):
    driver = None
    try:
        driver = init_stealth_driver()
    except Exception as e:
        print(f"[!] Live Worker-{worker_id} berjalan dalam mode cepat (tanpa browser): {e}")

    while not task_queue.empty():
        try:
            country_code, country_name = task_queue.get_nowait()
        except queue.Empty:
            break

        print(f"[Live-Worker-{worker_id}] Mengekstrak saluran TV Garden untuk: {country_name} ({country_code.upper()})...")
        results = []
        
        # 1. Scrape langsung ke web TV Garden dengan Selenium
        if driver:
            results.extend(scrape_tv_garden_country(driver, country_code, country_name))
            
        # 2. Lengkapi dengan indeks stream langsung agar ratusan channel masuk semua
        results.extend(fetch_direct_m3u_index(country_code, country_name))

        added = 0
        with lock:
            for ch in results:
                s_url = ch.get("stream_url")
                if s_url and s_url not in existing_streams:
                    existing_streams.add(s_url)
                    channels_db.append(ch)
                    added += 1
            if added > 0:
                save_live_outputs()
        print(f"[Live-Worker-{worker_id}] +{added} saluran TV aktif ditambahkan dari {country_name} (Total: {len(channels_db)})")
        task_queue.task_done()

    if driver:
        try:
            driver.quit()
        except Exception:
            pass

def build_live_tv_database():
    global channels_db, existing_streams
    os.makedirs(DATA_DIR, exist_ok=True)

    if os.path.exists(LIVE_DB_FILE):
        try:
            with open(LIVE_DB_FILE, "r", encoding="utf-8") as f:
                channels_db = json.load(f)
                existing_streams = {item["stream_url"] for item in channels_db if "stream_url" in item}
        except Exception:
            channels_db = []

    task_queue = queue.Queue()
    for item in TARGET_COUNTRIES:
        task_queue.put(item)

    num_workers = min(MAX_WORKERS, task_queue.qsize())
    print("=" * 60)
    print(f"[*] Memulai Scraping Live TV (TV Garden) dengan {num_workers} Workers...")
    print("=" * 60)

    threads = []
    for i in range(1, num_workers + 1):
        t = threading.Thread(target=worker_live_task, args=(i, task_queue), daemon=True)
        t.start()
        threads.append(t)

    for t in threads:
        while t.is_alive():
            t.join(timeout=1.0)

    with lock:
        save_live_outputs()
    print(f"\n[*] Selesai! Total {len(channels_db)} saluran Live TV tersimpan di:")
    print(f"    - JSON : {LIVE_DB_FILE}")
    print(f"    - M3U8 : {LIVE_M3U_FILE}")

if __name__ == "__main__":
    build_live_tv_database()