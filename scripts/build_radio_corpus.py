#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RadioBrowser 全量语料拉取 + seed 构建(发版侧工具,v3.7.0 起)。

管线:4 并行 × 500 行/页(order=stationuuid, hidebroken=true)分页拉全量
→ 按 votes 取 top 1000 记 hotRank(与 doSync topvote 口径一致)
→ gzip JSON 数组 → assets/radio_seed_corpus.bin + radio_seed_meta.json。

原始拉取落盘 .corpus-raw/(已 gitignore):同日重建免重拉;跨天应删除重拉
(health/clickcount 都会过期,seed 数据新鲜度 = 拉取日)。

用法: python scripts/build_radio_corpus.py --seed-version 2026-09-16-v3.7.0
断言纪律:行数下限/字段完整性/uuid 唯一/hotRank 恰 1000/gzip 往返,任一失败退出码 1。
"""
import argparse
import gzip
import json
import os
import ssl
import sys
import threading
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_DIR = os.path.join(ROOT, ".corpus-raw")
RAW_PATH = os.path.join(RAW_DIR, "stations_full.json")
ASSET_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "radio_seed_corpus.bin")
META_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "radio_seed_meta.json")

MIRRORS = ["https://de1.api.radio-browser.info", "https://de2.api.radio-browser.info",
           "https://all.api.radio-browser.info"]
PAGE_SIZE = 500
WORKERS = 4
PAGE_PACING_S = 0.3
UA = "carmusic-corpus-builder/3.7.0 (github.com/soloman7/carmusic)"
KEEP_FIELDS = ["stationuuid", "name", "url", "url_resolved", "homepage", "favicon",
               "tags", "country", "countrycode", "state", "language", "codec",
               "bitrate", "lastcheckok", "votes", "clickcount"]


def fetch_page(offset: int, rounds: int = 4) -> list:
    """单页拉取:镜像轮转 × rounds,指数退避(5s→60s);成功页落盘缓存供断点续拉。"""
    cache = os.path.join(RAW_DIR, f"page-{offset}.json")
    if os.path.exists(cache):
        with open(cache, "r", encoding="utf-8") as f:
            return json.load(f)
    last_err = None
    delay = 5
    for attempt in range(rounds * len(MIRRORS)):
        mirror = MIRRORS[attempt % len(MIRRORS)]
        try:
            req = urllib.request.Request(
                mirror + f"/json/stations/search?limit={PAGE_SIZE}&offset={offset}"
                f"&order=stationuuid&hidebroken=true",
                headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=120) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            if not isinstance(data, list):
                raise ValueError(f"non-list payload: {type(data)}")
            os.makedirs(RAW_DIR, exist_ok=True)
            tmp = cache + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False)
            os.replace(tmp, cache)
            return data
        except Exception as e:  # noqa: BLE001 — 网络/镜像故障统一轮转重试
            last_err = e
            time.sleep(delay)
            delay = min(delay * 2, 60)
    raise RuntimeError(f"page @offset={offset} failed after retries: {last_err}")


def pull_all() -> list:
    """4 并行分页:共享 offset 游标;worker 失败不弃批,offset 记入重试队列末轮补拉。"""
    lock = threading.Lock()
    cursor = {"next": 0, "done": False}
    pages: list[tuple[int, list]] = []
    failed: list[int] = []

    def worker():
        while True:
            with lock:
                if cursor["done"]:
                    return
                offset = cursor["next"]
                cursor["next"] += PAGE_SIZE
            try:
                page = fetch_page(offset)
            except Exception as e:  # noqa: BLE001
                print(f"worker: offset {offset} deferred ({e})", file=sys.stderr)
                with lock:
                    failed.append(offset)
                continue
            with lock:
                pages.append((offset, page))
                if len(page) < PAGE_SIZE:
                    cursor["done"] = True
            time.sleep(PAGE_PACING_S)

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futures = [ex.submit(worker) for _ in range(WORKERS)]
        for f in futures:
            f.result()

    # 末轮补拉失败页(串行,重试上限已含在 fetch_page)
    if failed:
        print(f"retrying {len(failed)} deferred offsets sequentially...", file=sys.stderr)
        still_failed = []
        for offset in sorted(set(failed)):
            try:
                pages.append((offset, fetch_page(offset, rounds=6)))
            except Exception as e:  # noqa: BLE001
                still_failed.append(f"{offset}: {e}")
        if still_failed:
            print(f"ERROR: pages unrecoverable: {still_failed}", file=sys.stderr)
            sys.exit(1)

    stations: dict[str, dict] = {}
    for offset, page in sorted(pages):
        for s in page:
            uuid = (s.get("stationuuid") or "").strip()
            if uuid and uuid not in stations:
                stations[uuid] = s
    print(f"pulled {len(stations)} unique stations "
          f"({len(pages)} pages) in {time.time() - t0:.1f}s")
    return list(stations.values())


def build_seed(stations: list) -> tuple[list, dict]:
    # 完整性断言:行数下限 / 字段齐备 / 数值型 votes+clickcount
    if len(stations) < 50_000:
        print(f"ERROR: only {len(stations)} stations (<50000) — truncation suspected", file=sys.stderr)
        sys.exit(1)
    cleaned = []
    for s in stations:
        rec = {k: s.get(k) for k in KEEP_FIELDS}
        if rec["stationuuid"] is None or not str(rec["stationuuid"]).strip():
            print("ERROR: record without stationuuid", file=sys.stderr)
            sys.exit(1)
        for int_field in ("bitrate", "lastcheckok", "votes", "clickcount"):
            v = rec[int_field]
            if v is None or not isinstance(v, int):
                try:
                    rec[int_field] = int(v)
                except (TypeError, ValueError):
                    print(f"ERROR: {rec['stationuuid']} {int_field}={v!r} not int", file=sys.stderr)
                    sys.exit(1)
        cleaned.append(rec)

    # hotRank = votes 全局 top 1000(平票 clickcount、uuid 定序;与 doSync topvote 口径一致)
    ranked = sorted(cleaned, key=lambda r: (-r["votes"], -r["clickcount"], r["stationuuid"]))[:1000]
    for i, r in enumerate(ranked):
        r["hotRank"] = i + 1
    rank_map = {r["stationuuid"]: r["hotRank"] for r in ranked}
    for r in cleaned:
        r["hotRank"] = rank_map.get(r["stationuuid"], 0)

    cn = [r for r in cleaned if r["countrycode"] == "CN"]
    if len(cn) < 1500:
        print(f"ERROR: CN rows {len(cn)} < 1500 — corpus regression", file=sys.stderr)
        sys.exit(1)
    print(f"built: total={len(cleaned)} cn={len(cn)} hot=1000")
    return cleaned, {"cn": len(cn), "countries": len({r['countrycode'] for r in cleaned})}


# v6-D-B2 发版侧 CN 可达性探测:与 app 的 RadioCatalog.CATEGORIES 保持一致
# (Kotlin 侧为权威定义;改动须双侧同步,回归脚本依赖此表抽检)
PROBE_CATEGORIES = {
    "pop": ["pop", "top 40", "top40"], "rock": ["rock", "pop rock"],
    "news": ["news", "information"], "classical": ["classical"],
    "jazz": ["jazz", "smooth jazz"], "oldies": ["oldies", "70s", "80s", "90s"],
    "dance": ["dance", "electronic", "house"],
    "finance": ["business", "economics", "finance"], "talk": ["talk"],
    "culture": ["culture"], "traffic": ["traffic"],
}
PROBE_TOP_N = 15
PROBE_ATTEMPTS = 2


def probe_stream(url: str) -> bool:
    """可达性探测:连接级失败(超时/拒绝/DNS)按不可达;SSL 链问题视为可达
    (服务器应答,Android 信任库判定可能不同,留给播放期反应式挂账)。"""
    for attempt in range(PROBE_ATTEMPTS):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE      # 只判"服务器是否应答",证书判定归播放期
            with urllib.request.urlopen(req, timeout=10, context=ctx) as resp:
                chunk = resp.read(4096)
                return len(chunk) > 0
        except ssl.SSLError:
            return True
        except urllib.error.URLError as e:
            if isinstance(getattr(e, "reason", None), ssl.SSLError):
                return True                       # 证书链断但服务器应答
            time.sleep(1)
        except Exception:  # noqa: BLE001 — 超时/拒绝/DNS 都按不可达重试一次
            if attempt == PROBE_ATTEMPTS - 1:
                return False
            time.sleep(1)
    return False


def probe_top_categories(seed: list) -> dict:
    """11 分类 × clickcount top15 拉流探测:连接级不可达 → seed 内 lastcheckok=0。
    复活周期 = 运行时 doSync(CN+topvote1000,7 天)或下周 seed 重探;
    已挂账设备的 localDeadUntil 始终兜底(同步禁触)。"""
    def toks(r):
        return {t.strip().lower() for t in r["tags"].split(",")} if r["tags"] else set()

    targets: dict[str, str] = {}
    for cat, tags in PROBE_CATEGORIES.items():
        rows = [r for r in seed if r["lastcheckok"] == 1 and (toks(r) & set(tags))]
        rows.sort(key=lambda r: -r["clickcount"])
        for r in rows[:PROBE_TOP_N]:
            targets[r["stationuuid"]] = r["url_resolved"] or r["url"]

    results = {}
    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        for uuid, alive in zip(targets.keys(), ex.map(probe_stream, targets.values())):
            results[uuid] = alive

    dead = [u for u, alive in results.items() if not alive]
    for r in seed:
        if r["stationuuid"] in dead and r["countrycode"] != "CN":
            r["lastcheckok"] = 0
    os.makedirs(RAW_DIR, exist_ok=True)
    with open(os.path.join(RAW_DIR, "probe_results.json"), "w", encoding="utf-8") as f:
        json.dump({"dead": dead, "probed": len(results),
                   "cats": PROBE_CATEGORIES}, f, ensure_ascii=False, indent=1)
    print(f"probe: {len(results)} streams, dead={len(dead)} (seed 内标记 health=0)")
    return {"probed": len(results), "dead": len(dead)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed-version", required=True, help="seed 版本门标识(与上次发版不同即触发全量重导)")
    ap.add_argument("--reuse-raw", action="store_true", help="复用 .corpus-raw 原始拉取(同日重建)")
    args = ap.parse_args()

    stations = None
    if args.reuse_raw and os.path.exists(RAW_PATH):
        age_h = (time.time() - os.path.getmtime(RAW_PATH)) / 3600
        if age_h > 24:
            print(f"ERROR: raw pull is {age_h:.1f}h old — health/clickcount stale, re-pull", file=sys.stderr)
            sys.exit(1)
        with open(RAW_PATH, "r", encoding="utf-8") as f:
            stations = json.load(f)
        print(f"reusing raw pull: {len(stations)} stations")

    if stations is None:
        stations = pull_all()
        os.makedirs(RAW_DIR, exist_ok=True)
        with open(RAW_PATH, "w", encoding="utf-8") as f:
            json.dump(stations, f, ensure_ascii=False, separators=(",", ":"))
        print(f"raw saved: {RAW_PATH} ({os.path.getsize(RAW_PATH) / 1e6:.1f} MB)")

    seed, stats = build_seed(stations)
    stats["probe"] = probe_top_categories(seed)

    # gzip 往返断言后落盘(.bin 扩展名:AGP 会特殊处理 .gz 资产)
    payload = json.dumps(seed, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    blob = gzip.compress(payload, 9)
    if json.loads(gzip.decompress(blob).decode("utf-8")) != seed:
        print("ERROR: gzip roundtrip mismatch", file=sys.stderr)
        sys.exit(1)
    with open(ASSET_PATH, "wb") as f:
        f.write(blob)
    meta = {"version": args.seed_version, "count": len(seed)}
    with open(META_PATH, "w", encoding="utf-8") as f:
        json.dump(meta, f)
    print(f"seed: {ASSET_PATH} ({len(blob) / 1e6:.2f} MB) meta={meta} stats={stats}")


if __name__ == "__main__":
    main()
