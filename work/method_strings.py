"""导出指定类每个方法体内引用的字符串常量，帮助还原方法行为。

用法: python method_strings.py <dex目录> <类全名>
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexlib import Dex


def main():
    dex_dir, target = sys.argv[1], sys.argv[2]
    target_desc = "L" + target.replace(".", "/") + ";"
    files = sorted(f for f in os.listdir(dex_dir) if f.startswith("classes") and f.endswith(".dex"))
    for fn in files:
        d = Dex(os.path.join(dex_dir, fn))
        for desc, cdo, acc, sup in d.class_defs():
            if desc != target_desc:
                continue
            _sf, _if, dm, vm = d.class_data(cdo)
            for midx, ma, co in list(dm) + list(vm):
                try:
                    c, name, sig = d.method_sig(midx)
                except Exception:
                    continue
                strs = d.code_string_refs(co) if co else []
                # 只打印含字符串常量的方法，过滤噪声
                strs = [s for s in strs if s.strip() and len(s) < 120]
                if strs:
                    print("%s%s" % (name, sig))
                    for s in dict.fromkeys(strs):
                        print("    str: %s" % s)


if __name__ == "__main__":
    main()