"""APK 体积归因（第二版）：区分 dex 各区段体积，并细分 miuix 子包。

第一版发现 23MB dex 里只有 8.5MB 是 code_item，剩下 14MB 去哪了？
本脚本按 header 里各 id 表的 size/off（注意是交错排列，不是六个连续的 off）
拆出字符串池/类型池等占比，再把 top/yukonga/miuix 按子包再分一层，
确认是不是图标集撑起来的。
"""
import collections
import os
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexlib import Dex

APK = r"HolePowerRing\app\build\outputs\apk\release\app-release.apk"
OUT = os.environ.get("TEMP", ".") + r"\dexsize"

# DEX header 里各 id 表字段的字节偏移（size 与 off 交错）
FIELD_OFF = {
    "string": (56, 60),
    "type": (64, 68),
    "proto": (72, 76),
    "field": (80, 84),
    "method": (88, 92),
    "class": (96, 100),
}


def header_table(buf, name):
    size_at, off_at = FIELD_OFF[name]
    size = struct.unpack_from("<I", buf, size_at)[0]
    off = struct.unpack_from("<I", buf, off_at)[0]
    return size, off


def uleb(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, off


def code_item_size(buf, code_off):
    """code_item 的 insns 段 + 16 字节头（含 padding），不含 tries/handlers。

    tries 块通常只有几十字节，对归因结论没有影响，故不精确计算。
    """
    if code_off == 0:
        return 0
    insns_size = struct.unpack_from("<I", buf, code_off + 12)[0]
    return 16 + insns_size * 2


def class_code_size(d, class_data_off):
    if class_data_off == 0:
        return 0
    try:
        _, _, dm, vm = d.class_data(class_data_off)
    except Exception:
        return 0
    total = 0
    for _, _, code_off in list(dm) + list(vm):
        total += code_item_size(d.buf, code_off)
    return total


def miuix_key(desc):
    seg = [p for p in desc.split("/") if not p.endswith(";")]
    return "/".join(seg[:5]) if len(seg) >= 5 else "/".join(seg[:-1])


def main():
    z = zipfile.ZipFile(APK)
    dex_names = sorted(n for n in z.namelist() if n.endswith(".dex"))
    miu = collections.defaultdict(lambda: [0, 0])

    for dn in dex_names:
        path = os.path.join(OUT, os.path.basename(dn))
        d = Dex(path)
        b = d.buf
        file_size = len(b)

        n_str, str_off = header_table(b, "string")
        n_type, _ = header_table(b, "type")
        n_proto, _ = header_table(b, "proto")
        n_field, _ = header_table(b, "field")
        n_method, _ = header_table(b, "method")
        n_class, class_off = header_table(b, "class")

        # string_data_item：每个是 uleb128 长度 + MUTF-8 + \0
        str_data = 0
        for i in range(n_str):
            o = struct.unpack_from("<I", b, str_off + i * 4)[0]
            n, o2 = uleb(b, o)
            str_data += (o2 - o) + n + 1

        code_total = 0
        for desc, cdoff, _a, _s in d.class_defs():
            s = class_code_size(d, cdoff)
            code_total += s
            if desc.startswith("Ltop/yukonga/miuix"):
                g = miu[miuix_key(desc)]
                g[0] += s
                g[1] += 1

        tables = {
            "string_ids 表": n_str * 4,
            "type_ids 表": n_type * 4,
            "proto_ids 表": n_proto * 12,
            "field_ids 表": n_field * 8,
            "method_ids 表": n_method * 8,
            "class_defs 表": n_class * 32,
        }
        data_start = class_off + n_class * 32
        sections = dict(tables)
        sections["string data"] = str_data
        sections["code_item(insns)"] = code_total
        sections["data 段余量(debug/注解/对齐)"] = (
            file_size - data_start - sum(tables.values()) - str_data - code_total
        )
        sections["__文件总大小"] = file_size

        print(f"--- {dn}  ({file_size/1024/1024:.2f} MB, {n_class} 个类, {n_str} 个字符串) ---")
        for k, v in sorted(sections.items(), key=lambda x: -x[1]):
            print(f"  {v/1024/1024:8.3f} MB  {k}")
        print()

    print("=== miuix 按子包细分 ===")
    for k, (s, n) in sorted(miu.items(), key=lambda x: -x[1][0])[:20]:
        print(f"{s/1024/1024:8.3f} MB  {n:6d} 类  {k}")


if __name__ == "__main__":
    main()