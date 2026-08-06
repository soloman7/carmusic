#!/usr/bin/env python3
"""网易云 weapi 接口实测：/weapi/toplist 与 /weapi/playlist/highquality/list 匿名可用性验证"""
import json, random, string, base64, sys
import requests
from Crypto.Cipher import AES

PRESET_KEY = b"0CoJUm6Qyw8W8jud"
IV = b"0102030405060708"
PUBKEY = "010001"
MODULUS = ("00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa"
           "76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee"
           "255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7")

def aes_encrypt(text: bytes, key: bytes) -> str:
    pad = 16 - len(text) % 16
    text = text + bytes([pad]) * pad
    return base64.b64encode(AES.new(key, AES.MODE_CBC, IV).encrypt(text)).decode()

def rsa_encrypt(text: str) -> str:
    # 网易 weapi：反转字符串 → 大数模幂（无填充裸 RSA）→ 256 位 hex
    num = int.from_bytes(text[::-1].encode(), "big")
    return format(pow(num, int(PUBKEY, 16), int(MODULUS, 16)), "0256x")

def weapi(path: str, payload: dict):
    secret = "".join(random.choices(string.ascii_letters + string.digits, k=16))
    text = json.dumps(payload, separators=(",", ":"))
    params = aes_encrypt(aes_encrypt(text.encode(), PRESET_KEY).encode(), secret.encode())
    enc_sec_key = rsa_encrypt(secret)
    resp = requests.post(
        "https://music.163.com" + path,
        data={"params": params, "encSecKey": enc_sec_key},
        headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            "Referer": "https://music.163.com",
        },
        timeout=15,
    )
    return resp.json()

print("=== /weapi/toplist ===")
try:
    r = weapi("/weapi/toplist", {"csrf_token": ""})
    lst = r.get("list", [])
    print(f"榜单数: {len(lst)}")
    for p in lst[:8]:
        print(f"  {p['id']} | {p['name']} | {p.get('updateFrequency','')}")
except Exception as e:
    print("FAILED:", e)

print()
print("=== /weapi/playlist/highquality/list (华语) ===")
try:
    r = weapi("/weapi/playlist/highquality/list", {"cat": "华语", "limit": 6, "csrf_token": ""})
    pls = r.get("playlists", [])
    print(f"精品歌单数: {len(pls)}, total={r.get('total')}")
    for p in pls:
        print(f"  {p['id']} | {p['name']} | tracks={p.get('trackCount')}")
except Exception as e:
    print("FAILED:", e)

print()
print("=== /weapi/v3/playlist/detail privileges 灰歌字段验证 (热歌榜 3778678) ===")
try:
    r = weapi("/weapi/v3/playlist/detail", {"id": 3778678, "n": 100000, "s": 8, "csrf_token": ""})
    pl = r.get("playlist", {})
    tracks = pl.get("tracks", [])
    privs = pl.get("privileges", [])
    grey = sum(1 for p in privs if p.get("st", 0) < 0)
    print(f"tracks={len(tracks)}, privileges={len(privs)}, 灰歌(st<0)={grey}")
    if privs:
        print("privilege 样例字段:", sorted(privs[0].keys())[:12])
except Exception as e:
    print("FAILED:", e)
