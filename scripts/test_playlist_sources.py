#!/usr/bin/env python3
"""CarMusic v2.9 歌单音源有效性实测
按 app/src/main/java/com/carmusic/source/providers/ 各 provider 的线上接口逐一验证：
推荐歌单 / 歌单广场 / 歌单曲目 / 播放 URL 抽查。
"""
import json, random, string, base64, re, time, html, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
import requests
from Crypto.Cipher import AES

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
TIMEOUT = 15
S = requests.Session()

def get_json(url, headers=None):
    h = {"User-Agent": UA}
    if headers:
        h.update(headers)
    try:
        r = S.get(url, headers=h, timeout=TIMEOUT)
        if r.status_code // 100 != 2:
            return None, f"HTTP {r.status_code}"
        return r.json(), None
    except Exception as e:
        return None, str(e)

# ---------------- 网易云 weapi ----------------
PRESET_KEY = b"0CoJUm6Qyw8W8jud"
IV = b"0102030405060708"
PUBKEY = "010001"
MODULUS = ("00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa"
           "76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee"
           "255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7")

def aes_enc(text: bytes, key: bytes) -> str:
    pad = 16 - len(text) % 16
    text = text + bytes([pad]) * pad
    return base64.b64encode(AES.new(key, AES.MODE_CBC, IV).encrypt(text)).decode()

def rsa_enc(text: str) -> str:
    num = int.from_bytes(text[::-1].encode(), "big")
    return format(pow(num, int(PUBKEY, 16), int(MODULUS, 16)), "0256x")

def weapi(path, payload):
    secret = "".join(random.choices(string.ascii_letters + string.digits, k=16))
    text = json.dumps(payload, separators=(",", ":"))
    params = aes_enc(aes_enc(text.encode(), PRESET_KEY).encode(), secret.encode())
    r = S.post("https://music.163.com" + path,
               data={"params": params, "encSecKey": rsa_enc(secret)},
               headers={"User-Agent": UA, "Referer": "https://music.163.com",
                        "Content-Type": "application/x-www-form-urlencoded"},
               timeout=TIMEOUT)
    if r.status_code // 100 != 2:
        return None, f"HTTP {r.status_code}"
    return r.json(), None

def test_netease():
    print("=" * 60)
    print("【网易云】")
    res = {}
    # 推荐歌单 1/3：榜单
    try:
        r, err = weapi("/weapi/toplist", {"csrf_token": ""})
        if err: raise RuntimeError(err)
        lst = r.get("list") or []
        res["推荐-榜单"] = (len(lst) > 0, f"{len(lst)} 个榜单")
    except Exception as e:
        res["推荐-榜单"] = (False, str(e))
    # 推荐歌单 2/3：精品
    try:
        r, err = weapi("/weapi/playlist/highquality/list", {"cat": "华语", "limit": 6, "csrf_token": ""})
        if err: raise RuntimeError(err)
        pls = r.get("playlists") or []
        res["推荐-精品"] = (len(pls) > 0, f"{len(pls)} 个精品歌单")
    except Exception as e:
        res["推荐-精品"] = (False, str(e))
    # 推荐歌单 3/3：个性化
    try:
        r, err = weapi("/weapi/personalized/playlist", {"limit": 30, "total": True, "n": 1000, "csrf_token": ""})
        if err: raise RuntimeError(err)
        pls = r.get("result") or []
        res["推荐-个性化"] = (len(pls) > 0, f"{len(pls)} 个")
    except Exception as e:
        res["推荐-个性化"] = (False, str(e))
    # 广场 2 页
    first_id = None
    try:
        ok_pages = 0
        for offset in (0, 30):
            r, err = weapi("/weapi/playlist/list",
                           {"cat": "全部", "order": "hot", "limit": 30, "offset": offset, "total": True, "csrf_token": ""})
            if err: raise RuntimeError(err)
            pls = r.get("playlists") or []
            if not pls: raise RuntimeError(f"offset={offset} 返回空")
            if offset == 0 and pls:
                first_id = pls[0]["id"]
            ok_pages += 1
        res["广场"] = (True, f"2 页正常（第 1 个歌单 id={first_id}）")
    except Exception as e:
        res["广场"] = (False, str(e))
    # 曲目 + 灰歌统计
    tracks = []
    try:
        pid = first_id or 3778678
        r, err = weapi("/weapi/v6/playlist/detail", {"id": pid, "n": 100000, "s": 8, "csrf_token": ""})
        if err or not (r.get("playlist") or {}).get("tracks"):
            r, err = weapi("/weapi/v3/playlist/detail", {"id": pid, "n": 100000, "s": 8, "csrf_token": ""})
        if err: raise RuntimeError(err)
        pl = r.get("playlist") or {}
        tracks = pl.get("tracks") or []
        privs = pl.get("privileges") or []
        grey = sum(1 for p in privs if p.get("st", 0) < 0 or p.get("fee") in (1, 4))
        if not tracks: raise RuntimeError("tracks 为空")
        res["曲目"] = (True, f"歌单 {pid}: {len(tracks)} 首, 灰歌/VIP {grey} 首")
    except Exception as e:
        res["曲目"] = (False, str(e))
    # 播放抽查
    try:
        sample = [t["id"] for t in tracks[:3]]
        if not sample: raise RuntimeError("无可抽查曲目")
        r, err = weapi("/weapi/song/enhance/player/url/v1",
                       {"ids": json.dumps(sample), "level": "standard", "encodeType": "aac", "csrf_token": ""})
        if err: raise RuntimeError(err)
        data = r.get("data") or []
        ok = sum(1 for d in data if d.get("url"))
        res["播放抽查"] = (len(data) > 0, f"{ok}/{len(data)} 首可取流（其余为 VIP/版权）")
    except Exception as e:
        res["播放抽查"] = (False, str(e))
    for k, (ok, msg) in res.items():
        print(f"  [{'✅' if ok else '❌'}] {k}: {msg}")
    return res

# ---------------- QQ 音乐 ----------------
QQ_TOP_IDS = [(26, "热歌"), (62, "飙升"), (27, "新歌"), (4, "流行指数"), (5, "内地"), (6, "港台"),
              (3, "欧美"), (60, "抖音热歌"), (57, "电音"), (58, "说唱"), (67, "听歌识曲"), (108, "美国公告牌"),
              (28, "网络歌曲"), (29, "影视金曲"), (65, "国风热歌"), (63, "DJ舞曲"), (72, "动漫音乐"), (16, "韩国")]
QQ_H = {"Referer": "https://y.qq.com/"}

def test_qq():
    print("=" * 60)
    print("【QQ 音乐】")
    res = {}
    # 全部榜单判活（含曲目）
    alive, dead = [], []
    for topid, name in QQ_TOP_IDS:
        j, err = get_json(f"https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?topid={topid}&num=5&page=1&type=1&format=json", QQ_H)
        if err or not j or j.get("code") != 0 or not j.get("songlist"):
            dead.append(f"{topid}({name}):{err or (j or {}).get('code')}")
        else:
            alive.append(topid)
    res[f"推荐-{len(QQ_TOP_IDS)}榜单"] = (not dead, f"{len(alive)}/{len(QQ_TOP_IDS)} 存活" + (f", 失效: {'; '.join(dead)}" if dead else ""))
    # 广场 2 页
    first_diss = None
    try:
        for sin in (0, 30):
            j, err = get_json(
                "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?picmid=1&rnd=0.5&g_tk=732560869"
                "&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0"
                f"&platform=yqq.json&needNewCode=0&categoryId=10000000&sortId=5&sin={sin}&ein={sin+29}", QQ_H)
            if err: raise RuntimeError(err)
            lst = ((j or {}).get("data") or {}).get("list") or []
            if not lst: raise RuntimeError(f"sin={sin} 返回空 code={(j or {}).get('code')}")
            if sin == 0:
                first_diss = lst[0].get("dissid")
        res["广场"] = (True, f"2 页正常（第 1 个歌单 dissid={first_diss}）")
    except Exception as e:
        res["广场"] = (False, str(e))
    # 广场歌单曲目 + VIP 统计
    songs = []
    try:
        if not first_diss: raise RuntimeError("无广场歌单可测")
        j, err = get_json(
            "https://i.y.qq.com/qzone-music/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0"
            f"&nosign=1&disstid={first_diss}&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=GB2312"
            "&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0", QQ_H)
        if err: raise RuntimeError(err)
        cdlist = j.get("cdlist") or []
        songs = (cdlist[0].get("songlist") if cdlist else []) or []
        if not songs: raise RuntimeError(f"songlist 为空 code={j.get('code')}")
        vip = sum(1 for s in songs if (s.get("pay") or {}).get("pay_play") == 1)
        res["曲目"] = (True, f"歌单 {first_diss}: {len(songs)} 首, VIP {vip} 首")
    except Exception as e:
        res["曲目"] = (False, str(e))
    # 播放抽查（vkey）
    try:
        cands = [s for s in songs if (s.get("pay") or {}).get("pay_play") != 1 and s.get("songmid") and s.get("strMediaMid")][:3]
        if not cands: raise RuntimeError("无可抽查曲目")
        guid = "".join(random.choices("0123456789abcdef", k=10))
        mids = [s["songmid"] for s in cands]
        files = [f"M500{s['strMediaMid']}.mp3" for s in cands]
        body = {"req_0": {"module": "vkey.GetVkeyServer", "method": "CgiGetVkey",
                          "param": {"filename": files, "guid": guid, "songmid": mids,
                                    "songtype": [0] * len(mids), "uin": "0", "loginflag": 1, "platform": "20"}},
                "comm": {"qq": "0", "authst": "", "ct": "26", "cv": "2010101", "v": "2010101"}}
        r = S.post("https://u.y.qq.com/cgi-bin/musicu.fcg", json=body,
                   headers={"User-Agent": UA, "Referer": "https://y.qq.com/"}, timeout=TIMEOUT)
        j = r.json()
        infos = (((j.get("req_0") or {}).get("data") or {}).get("midurlinfo")) or []
        ok = sum(1 for i in infos if i.get("purl"))
        res["播放抽查"] = (len(infos) > 0 and ok > 0, f"{ok}/{len(infos)} 首可取流")
    except Exception as e:
        res["播放抽查"] = (False, str(e))
    for k, (ok, msg) in res.items():
        print(f"  [{'✅' if ok else '❌'}] {k}: {msg}")
    return res

# ---------------- 酷狗 ----------------
KUGOU_RANKS = [(8888, "TOP500"), (6666, "飙升榜"), (31308, "内地榜"), (74534, "新歌榜"),
               (82831, "网络热歌榜"), (85432, "百万收藏榜"), (24971, "DJ热歌榜"),
               (33165, "粤语金曲榜"), (33163, "影视金曲榜")]

def test_kugou():
    print("=" * 60)
    print("【酷狗】")
    res = {}
    songs = []
    dead = []
    for rankid, name in KUGOU_RANKS:
        j, err = get_json(f"https://m.kugou.com/rank/info/?rankid={rankid}&page=1&json=true")
        lst = ((j or {}).get("songs") or {}).get("list") if j else None
        if err or not lst:
            dead.append(f"{rankid}({name}):{err or 'songs.list 缺失'}")
        elif not songs:
            songs = lst
    res[f"推荐-{len(KUGOU_RANKS)}榜单"] = (not dead,
        f"{len(KUGOU_RANKS)}/{len(KUGOU_RANKS)} 存活" if not dead else f"失效: {'; '.join(dead)}")
    # 广场（新增）：plist/index 两页 + special/song 曲目
    first_sp = None
    try:
        for page in (1, 2):
            j, err = get_json(f"https://m.kugou.com/plist/index?json=true&page={page}")
            if err: raise RuntimeError(err)
            info = (((j or {}).get("plist") or {}).get("list") or {}).get("info") or []
            if not info: raise RuntimeError(f"第 {page} 页为空")
            if page == 1:
                first_sp = info[0].get("specialid")
        res["广场"] = (True, f"2 页正常（第 1 个歌单 specialid={first_sp}）")
    except Exception as e:
        res["广场"] = (False, str(e))
    res["曲目-榜单"] = (bool(songs), f"{len(songs)} 首（TOP500 第 1 页）" if songs else "无曲目")
    try:
        if not first_sp: raise RuntimeError("无广场歌单可测")
        j, err = get_json(f"http://mobilecdnbj.kugou.com/api/v3/special/song?specialid={first_sp}&page=1&pagesize=50")
        if err: raise RuntimeError(err)
        info = ((j or {}).get("data") or {}).get("info") or []
        bad = [t for t in info if not t.get("hash") or not t.get("filename")]
        res["曲目-广场"] = (bool(info) and not bad, f"{len(info)} 首, filename 可解析 {len(info)-len(bad)}/{len(info)}")
        if info and not songs:
            songs = info
    except Exception as e:
        res["曲目-广场"] = (False, str(e))
    try:
        sample = songs[:3]
        if not sample: raise RuntimeError("无可抽查曲目")
        ok, vip = 0, 0
        for s in sample:
            h = s.get("sqhash") or s.get("320hash") or s.get("hash")
            j, err = get_json(f"https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash={h}")
            if err: raise RuntimeError(err)
            if j.get("url"):
                ok += 1
            elif j.get("error"):
                vip += 1
        res["播放抽查"] = (ok + vip == len(sample), f"{ok}/{len(sample)} 首可取流, {vip} 首 VIP 需付费")
    except Exception as e:
        res["播放抽查"] = (False, str(e))
    for k, (ok, msg) in res.items():
        mark = "➖" if ok is None else ("✅" if ok else "❌")
        print(f"  [{mark}] {k}: {msg}")
    return res

# ---------------- 咪咕 ----------------
MIGU_H = {"channel": "0146951"}

def test_migu():
    print("=" * 60)
    print("【咪咕】")
    res = {}
    first_pid = None
    try:
        for page in (1, 2):
            j, err = get_json(
                f"https://app.c.nf.migu.cn/MIGUM2.0/v2.0/content/getMusicData.do?count=30&start={page}&templateVersion=5&type=1",
                MIGU_H)
            if err: raise RuntimeError(err)
            items = (((j or {}).get("data") or {}).get("contentItemList") or [{}])[0].get("itemList") or []
            if not items: raise RuntimeError(f"第 {page} 页为空")
            if page == 1:
                m = re.search(r"id=(\d+)&", items[0].get("actionUrl", "") + "&")
                first_pid = m.group(1) if m else None
        res["广场(=推荐)"] = (True, f"2 页正常（第 1 个歌单 id={first_pid}）")
    except Exception as e:
        res["广场(=推荐)"] = (False, str(e))
    songs = []
    try:
        if not first_pid: raise RuntimeError("无歌单 id 可测")
        j, err = get_json(
            f"https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0?playlistId={first_pid}&pageNo=1&pageSize=50",
            MIGU_H)
        if err: raise RuntimeError(err)
        raw = ((j or {}).get("data") or {}).get("songList") or []
        if not raw: raise RuntimeError(f"songList 为空 code={(j or {}).get('code')}")
        # 与 App 一致：showTags 含 "vip" 的歌预过滤（匿名必播不了）
        songs = [s for s in raw if "vip" not in (s.get("showTags") or [])]
        res["曲目"] = (True, f"歌单 {first_pid}: {len(raw)} 首, 过滤会员歌后剩 {len(songs)} 首")
    except Exception as e:
        res["曲目"] = (False, str(e))
    try:
        # 从过滤后的歌里抽查：全部应可播（不能再出现 440013 会员墙）
        sample = [s for s in songs if s.get("copyrightId") and s.get("contentId")][:5]
        if not sample: raise RuntimeError("过滤后无可抽查曲目（该歌单整单会员）")
        ok, vip = 0, 0
        for s in sample:
            j, err = get_json(
                "https://app.c.nf.migu.cn/MIGUM3.0/strategy/pc/listen/v1.0?scene=&netType=01&resourceType=2"
                f"&copyrightId={s['copyrightId']}&contentId={s['contentId']}&toneFlag=PQ",
                {**MIGU_H, "uid": "1234"})
            if err: raise RuntimeError(err)
            d = (j or {}).get("data") or {}
            if d.get("url"):
                ok += 1
            elif d.get("cannotCode"):
                vip += 1
        res["播放抽查"] = (ok == len(sample), f"{ok}/{len(sample)} 首可取流" + (f", 漏网会员歌 {vip} 首" if vip else ""))
    except Exception as e:
        res["播放抽查"] = (False, str(e))
    for k, (ok, msg) in res.items():
        print(f"  [{'✅' if ok else '❌'}] {k}: {msg}")
    return res

# ---------------- 酷我 ----------------
KUWO_BANGS = [(16, "热歌"), (17, "新歌"), (26, "经典"), (62, "华语"), (93, "飙升"), (104, "先锋"),
              (145, "畅销"), (151, "腾讯原创"), (158, "短视频"), (187, "流行趋势"), (236, "抖音"), (284, "热评"),
              (22, "欧美"), (23, "日韩"), (64, "影视"), (153, "网红新歌"), (287, "DJ搜索")]

def test_kuwo():
    print("=" * 60)
    print("【酷我】")
    res = {}
    songs = []
    dead = []
    for bid, name in KUWO_BANGS:
        j, err = get_json(f"http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&type=bang&data=content&id={bid}&rn=100")
        ml = (j or {}).get("musiclist") if j else None
        if err or not ml:
            dead.append(f"{bid}({name}):{err or 'musiclist 缺失'}")
        elif not songs:
            songs = ml
    res[f"推荐-{len(KUWO_BANGS)}榜单(=曲目)"] = (not dead,
                                 f"{len(KUWO_BANGS)}/{len(KUWO_BANGS)} 存活, 单曲 {len(songs)} 首/榜" if not dead else f"失效: {'; '.join(dead)}")
    res["广场"] = (None, "无可用匿名广场 API（wapi 已被 WAF 拦死），不实现")
    try:
        sample = songs[:3]
        if not sample: raise RuntimeError("无可抽查曲目")
        ok = 0
        for s in sample:
            r = S.get(f"https://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_{s['id']}",
                      headers={"User-Agent": UA}, timeout=TIMEOUT)
            if r.status_code == 200 and r.text.strip().startswith("http"):
                ok += 1
        res["播放抽查"] = (ok > 0, f"{ok}/{len(sample)} 首可取流")
    except Exception as e:
        res["播放抽查"] = (False, str(e))
    for k, (ok, msg) in res.items():
        mark = "➖" if ok is None else ("✅" if ok else "❌")
        print(f"  [{mark}] {k}: {msg}")
    return res

# ---------------- 猫耳FM ----------------
MAOER_THEMES = ["热门广播剧", "助眠", "白噪音", "有声小说", "情感电台", "耳语",
                "悬疑广播剧", "儿童故事", "相声", "睡前故事", "历史", "评书"]

def test_maoer():
    print("=" * 60)
    print("【猫耳FM】（主题歌单 = 关键词搜索快照，官方歌单 API 已全 404）")
    res = {}
    import urllib.parse
    first_sounds = []
    dead = []
    for kw in MAOER_THEMES:
        j, err = get_json(f"https://www.missevan.com/sound/getsearch?s={urllib.parse.quote(kw)}&p=1&type=3&page_size=10")
        datas = ((j or {}).get("info") or {}).get("Datas") if j else None
        # 与 App 一致：pay_type=2 付费剧集预过滤（注意 JSON 里是字符串 "2"）
        datas = [d for d in (datas or []) if str(d.get("pay_type")) != "2"]
        if err or not datas:
            dead.append(f"{kw}:{err or 'Datas 为空'}")
        elif not first_sounds:
            first_sounds = datas
    res[f"推荐-{len(MAOER_THEMES)}主题(=曲目)"] = (not dead,
                                f"{len(MAOER_THEMES)}/{len(MAOER_THEMES)} 主题有结果" if not dead else f"空主题: {'; '.join(dead)}")
    try:
        sample = first_sounds[:3]
        if not sample: raise RuntimeError("无可抽查曲目")
        ok = 0
        for s in sample:
            j, err = get_json(f"https://www.missevan.com/sound/getsound?soundid={s['id']}")
            if err: raise RuntimeError(err)
            url = (((j or {}).get("info") or {}).get("sound") or {}).get("soundurl") or ""
            if url.startswith("http"):
                ok += 1
        res["播放抽查"] = (ok == len(sample), f"{ok}/{len(sample)} 首可取流")
    except Exception as e:
        res["播放抽查"] = (False, str(e))
    for k, (ok, msg) in res.items():
        print(f"  [{'✅' if ok else '❌'}] {k}: {msg}")
    return res

# ---------------- Jamendo ----------------
JAMENDO_CID = "8a49589e"  # SettingsRepository.kt:98 内置默认值

def jamendo_fetch(params, retries=4):
    """Jamendo 服务端不稳定：同一请求约 1/3 概率返回 results_count=0，重试并统计"""
    url = f"https://api.jamendo.com/v3.0/tracks/?client_id={JAMENDO_CID}&format=json&include=musicinfo&audioformat=mp32&{params}"
    empty = 0
    for _ in range(retries):
        j, err = get_json(url)
        if err:
            return None, err, empty
        tracks = (j or {}).get("results") or []
        if tracks:
            return tracks, None, empty
        empty += 1
        time.sleep(0.8)
    return [], None, empty

def test_jamendo():
    print("=" * 60)
    print("【Jamendo】")
    res = {}
    for order, name in [("popularity_week", "周榜"), ("popularity_total", "总榜")]:
        tracks, err, empty = jamendo_fetch(f"limit=10&order={order}")
        audio_ok = sum(1 for t in tracks or [] if t.get("audio"))
        note = f"（4 次尝试中 {empty} 次空返回，服务端不稳定）" if empty else ""
        res[f"推荐-{name}"] = (not err and bool(tracks),
                               f"{len(tracks or [])} 首, {audio_ok} 首带 audio{note}" if tracks else f"始终空{note} {err or ''}")
    tracks, err, empty = jamendo_fetch("limit=10&order=popularity_total&tags=jazz")
    note = f"（4 次尝试中 {empty} 次空返回）" if empty else ""
    res["推荐-主题(jazz)"] = (not err and bool(tracks), f"{len(tracks or [])} 首{note}" if tracks else f"始终空{note}")
    # 31 个主题精选全量扫描（与 JamendoSource.curatedPlaylists 对齐）
    TAGS = ["classical","piano","jazz","ambient","world","soundtrack","chillout","hiphop","instrumental",
            "rock","pop","dance","folk","metal","blues","country","reggae","punk","soul","funk",
            "house","trance","lofi","meditation","latin",
            "acoustic","orchestral","epic","techno","smoothjazz","celtic"]
    weak = []
    for tag in TAGS:
        tracks, err, _ = jamendo_fetch(f"limit=50&order=popularity_total&tags={tag}", retries=6)
        audio = sum(1 for t in (tracks or []) if t.get("audio"))
        if err or audio < 10:
            weak.append(f"{tag}({audio if not err else err})")
    res[f"广场-{len(TAGS)}主题"] = (not weak, f"{len(TAGS)}/{len(TAGS)} 曲目充足" if not weak else f"曲目不足: {', '.join(weak)}")
    res["播放抽查"] = (None, "audio 字段即直连 mp3，已随曲目验证")
    for k, (ok, msg) in res.items():
        mark = "➖" if ok is None else ("✅" if ok else "❌")
        print(f"  [{mark}] {k}: {msg}")
    return res

# ---------------- 主流程 ----------------
def main():
    all_res = {}
    all_res["网易云"] = test_netease()
    time.sleep(1)
    all_res["QQ音乐"] = test_qq()
    time.sleep(1)
    all_res["酷狗"] = test_kugou()
    time.sleep(1)
    all_res["咪咕"] = test_migu()
    time.sleep(1)
    all_res["酷我"] = test_kuwo()
    time.sleep(1)
    all_res["猫耳FM"] = test_maoer()
    time.sleep(1)
    all_res["Jamendo"] = test_jamendo()
    print()
    print("=" * 60)
    print("【GdStudio】纯 fallback 解析器，无歌单功能，跳过")
    print()
    print("=" * 60)
    print("汇总：")
    for platform, res in all_res.items():
        fails = [k for k, (ok, _) in res.items() if ok is False]
        if fails:
            print(f"  {platform}: ❌ 失效项 -> {', '.join(fails)}")
        else:
            print(f"  {platform}: ✅ 全部通过")

if __name__ == "__main__":
    main()
