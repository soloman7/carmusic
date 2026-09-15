#!/usr/bin/env python3
"""CarMusic 电台数据侧探针(v5 验证计划;PC 网络不宣称覆盖车机)。
断言六类纪律:字段存在性/完整性(行数下限)/丢弃语义;本平台整体 WARN 级——
local-first 下 API 不可达不影响 App 功能(seed 兜底),失败可见但不拦截发版。"""
import sys, time, datetime
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
import requests

UA = "carmusic/3.5.0 (github.com/soloman7/carmusic)"
S = requests.Session(); S.headers["User-Agent"] = UA
MIRRORS = ["de1", "de2", "all", "fr1", "fi1", "nl1", "at1"]
NOW = datetime.datetime.now(datetime.timezone.utc)

def test_radio():
    print("=" * 60)
    print("【电台API】RadioBrowser 数据生产侧探针(warn 级)")
    res = {}

    # 1. 镜像可达性
    alive = []
    for m in MIRRORS:
        try:
            r = S.get(f"https://{m}.api.radio-browser.info/json/stats", timeout=6)
            if r.status_code == 200: alive.append(m)
        except Exception:
            pass
        time.sleep(0.4)
    res["镜像可达"] = (len(alive) >= 1, f"{len(alive)} 个存活: {','.join(alive) or '无'}")
    if not alive:
        res["拉流抽查"] = (None, "无镜像可达,跳过")
        for k, (ok, msg) in res.items():
            print(f"  [{'✅' if ok else ('⚠️' if ok is None else '❌')}] {k}: {msg}")
        return res

    base = f"https://{alive[0]}.api.radio-browser.info"

    # 2. changed 端点冻结监控(历史教训:200 但 feed 停在 2026-01-14)
    try:
        r = S.get(base + "/json/stations/changed", params={"reverse": "true", "limit": 1}, timeout=15)
        iso = r.json()[0]["lastchangetime_iso8601"]
        t = datetime.datetime.fromisoformat(iso.replace("Z", "+00:00"))
        age = (NOW - t).days
        res["changed新鲜度"] = (age < 14, f"最新变更距今 {age} 天(冻结则同步管道保持禁用)")
    except Exception as e:
        res["changed新鲜度"] = (False, str(e)[:50])
    time.sleep(1.1)

    # 3. CN 语料完整性(行数下限 = stationcount×0.8)
    try:
        r = S.get(base + "/json/countries", params={"limit": 300}, timeout=15)
        cn = next((c["stationcount"] for c in r.json() if c.get("iso_3166_1") == "CN"), 0)
        time.sleep(1.1)
        r = S.get(base + "/json/stations/search", params={"countrycode": "CN", "limit": 5000, "order": "stationuuid"}, timeout=300)
        rows = r.json()
        fields_ok = all(k in rows[0] for k in ("lastcheckok", "bitrate", "url_resolved"))
        res["CN语料完整性"] = (len(rows) >= cn * 0.8 and fields_ok and cn > 0,
                        f"{len(rows)}/{cn} 行, 字段{'齐全' if fields_ok else '缺失'}")
    except Exception as e:
        res["CN语料完整性"] = (False, str(e)[:60])
    time.sleep(1.1)

    # 4. 拉流抽查(CN https 非HLS 前 3 台)
    try:
        r = S.get(base + "/json/stations/search", params={
            "countrycode": "CN", "hidebroken": "true", "limit": 100, "order": "votes",
            "reverse": "true"}, timeout=60)
        cands = [s for s in r.json() if (s.get("url_resolved") or "").startswith("https")
                 and ".m3u8" not in s.get("url_resolved", "")][:3]
        ok = 0
        for s in cands:
            try:
                rr = S.get(s["url_resolved"], timeout=(8, 5), stream=True)
                chunk = next(rr.iter_content(4096), b"")
                rr.close()
                if chunk: ok += 1
            except Exception:
                pass
            time.sleep(0.4)
        res["拉流抽查"] = (ok == len(cands) and len(cands) > 0, f"{ok}/{len(cands)} 台可拉流")
    except Exception as e:
        res["拉流抽查"] = (False, str(e)[:60])

    for k, (ok, msg) in res.items():
        mark = "✅" if ok else "❌"
        print(f"  [{mark}] {k}: {msg}")
    return res
