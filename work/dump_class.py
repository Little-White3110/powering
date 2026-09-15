"""导出指定类的完整方法签名（含参数类型）与代码中的字符串常量，用于逆向定位。

用法: python dump_class.py <dex目录> <类全名(可多个, 逗号分隔)>
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexlib import Dex

ACC = {
    0x1: "public", 0x2: "private", 0x4: "protected", 0x8: "static", 0x10: "final",
    0x100: "native", 0x400: "abstract", 0x1000: "synthetic",
}


def acc_str(a):
    return " ".join(v for k, v in sorted(ACC.items()) if a & k)


def main():
    dex_dir = sys.argv[1]
    targets = sys.argv[2].split(",")
    files = sorted(f for f in os.listdir(dex_dir) if f.startswith("classes") and f.endswith(".dex"))
    for fn in files:
        d = Dex(os.path.join(dex_dir, fn))
        for desc, cdo, acc, sup in d.class_defs():
            plain = desc[1:-1].replace("/", ".")
            if plain not in targets:
                continue
            print("#" * 70)
            print("%s  [%s]  super=%s  acc=%s" % (desc, fn,
                  d.type(sup) if sup != 0xFFFFFFFF else "None", acc_str(acc)))
            sfields, ifields, dmethods, vmethods = d.class_data(cdo)
            if sfields or ifields:
                print("-- fields --")
                for fidx, fa in sfields + ifields:
                    c, t, n = d.field(fidx)
                    print("   %s %s %s" % (acc_str(fa), t, n))
            print("-- methods --")
            for midx, ma, code_off in dmethods + vmethods:
                try:
                    c, name, sig = d.method_sig(midx)
                except Exception as e:
                    print("   [parse err]", e)
                    continue
                print("   %s %s%s  code=%d" % (acc_str(ma), name, sig, code_off))


if __name__ == "__main__":
    main()