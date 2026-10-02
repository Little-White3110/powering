"""APK 体积归因：按包名前缀统计 dex 里每个类的 code_item 体积。

用途：release APK 23MB，resources 只占 0.3MB，怀疑是依赖把代码撑起来的。
本脚本把 classes*.dex 解出来，用 dexlib 解析 class_def，累加每个类的
code_item 字节数，再按包名前缀聚合，便于把体积归到具体依赖上。
"""
import collections
import os
import struct
import sys
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from dexlib import Dex

APK = r"HolePowerRing\app\build\outputs\apk\release\app-release.apk"
OUT = os.environ.get("TEMP", ".") + r"\dexsize"


def class_code_size(d, class_data_off):
    """该类 direct+virtual 方法的 code_item 总字节数（粗粒度估算，不含字符串池/类型池）。"""
    if class_data_off == 0:
        return 0, 0
    try:
        _, _, dm, vm = d.class_data(class_data_off)
    except Exception:
        return 0, 0
    total = 0
    n = 0
    for _, _, code_off in list(dm) + list(vm):
        if code_off == 0:
            continue
        # code_item: 16 字节头 + insns(insns_size*2)；有 tries 还要追加
        insns_size = struct.unpack_from("<I", d.buf, code_off + 12)[0]
        total += 16 + insns_size * 2
        n += 1
    return total, n


def main():
    os.makedirs(OUT, exist_ok=True)
    z = zipfile.ZipFile(APK)
    dex_names = [n for n in z.namelist() if n.endswith(".dex")]

    by_prefix = collections.defaultdict(lambda: [0, 0])   # prefix -> [bytes, classes]
    by_prefix3 = collections.defaultdict(lambda: [0, 0])  # 3 段前缀 -> [bytes, classes]
    total = 0

    for dn in dex_names:
        path = os.path.join(OUT, os.path.basename(dn))
        with open(path, "wb") as f:
            f.write(z.read(dn))
        d = Dex(path)
        for desc, cdoff, _acc, _sup in d.class_defs():
            size, _ = class_code_size(d, cdoff)
            total += size
            # Ltop/yukonga/miuix/ui/... -> 3 段
            parts = desc.split("/")
            seg = [p for p in parts if not p.endswith(";")]
            p3 = "/".join(seg[:3])
            p4 = "/".join(seg[:4])
            by_prefix[p3][0] += size
            by_prefix[p3][1] += 1
            by_prefix3[p4][0] += size
            by_prefix3[p4][1] += 1

    print(f"dex code 总计: {total/1024/1024:.2f} MB  （来自 {', '.join(dex_names)}）\n")
    print("=== 按 3 段包名聚合 ===")
    for k, (s, n) in sorted(by_prefix.items(), key=lambda x: -x[1][0])[:20]:
        print(f"{s/1024/1024:8.2f} MB  {n:6d} 类  {k}")
    print("\n=== 按 4 段包名聚合（前 30）===")
    for k, (s, n) in sorted(by_prefix3.items(), key=lambda x: -x[1][0])[:30]:
        print(f"{s/1024/1024:8.2f} MB  {n:6d} 类  {k}")


if __name__ == "__main__":
    main()