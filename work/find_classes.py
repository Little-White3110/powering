"""在 DEX 类清单中按关键词检索，输出命中的类描述符。"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexlib import Dex


def main():
    dex_dir = sys.argv[1]
    keywords = sys.argv[2].split(",")
    files = sorted(
        f for f in os.listdir(dex_dir) if f.startswith("classes") and f.endswith(".dex")
    )
    hits = {}
    for fn in files:
        d = Dex(os.path.join(dex_dir, fn))
        for desc, _cdo, _acc, _sup in d.class_defs():
            for kw in keywords:
                if kw in desc:
                    hits.setdefault(kw, []).append((fn, desc))
    for kw in keywords:
        items = hits.get(kw, [])
        print("=" * 8, kw, len(items))
        for fn, desc in items:
            print("  [%s] %s" % (fn, desc))


if __name__ == "__main__":
    main()