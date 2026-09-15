"""全字符串池检索：在 DEX 的所有字符串中按关键词匹配（含设置项 key、广播 action、资源名）。

用法: python find_strings.py <dex目录> <关键词1,关键词2> [-c 是否大小写敏感]
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexlib import Dex


def main():
    dex_dir = sys.argv[1]
    kws = sys.argv[2].split(",")
    cs = "-c" in sys.argv
    files = sorted(f for f in os.listdir(dex_dir) if f.startswith("classes") and f.endswith(".dex"))
    seen = set()
    for fn in files:
        d = Dex(os.path.join(dex_dir, fn))
        for i in range(d.string_ids_size):
            try:
                s = d.string(i)
            except Exception:
                continue
            hay = s if cs else s.lower()
            for kw in kws:
                k = kw if cs else kw.lower()
                if k in hay:
                    key = (kw, s)
                    if key in seen:
                        continue
                    seen.add(key)
                    print("[%s][%s] %s" % (fn, kw, s))


if __name__ == "__main__":
    main()