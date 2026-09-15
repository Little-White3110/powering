"""跨 DEX 反查方法调用点（xref）。

用法: python xref.py <dex目录> <目标类全名> <目标方法名>
输出所有 invoke-* 引用该方法的 (调用方类, 调用方方法)。
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexlib import Dex, _uleb128

INVOKE_OPS = {
    0x6E: 35, 0x6F: 35, 0x70: 35, 0x71: 35, 0x72: 35,   # invoke-kind (35c)
    0x74: 3, 0x75: 3, 0x76: 3, 0x77: 3, 0x78: 3,          # invoke-kind/range (3rc)
}


def code_invokes(d, code_off):
    """扫描 code_item，返回引用的 method_id 索引集合。"""
    if code_off == 0:
        return set()
    buf = d.buf
    insns_size = int.from_bytes(buf[code_off + 12:code_off + 16], "little")
    insns_off = code_off + 16
    out = set()
    i = 0
    n = insns_size * 2
    while i + 2 <= n:
        op = buf[insns_off + i]
        if op in (0x6E, 0x6F, 0x70, 0x71, 0x72):      # 35c 格式，长度 6 字节
            idx = int.from_bytes(buf[insns_off + i + 2:insns_off + i + 4], "little")
            out.add(idx)
            i += 6
        elif op in (0x74, 0x75, 0x76, 0x77, 0x78):     # 3rc 格式，长度 6 字节
            idx = int.from_bytes(buf[insns_off + i + 2:insns_off + i + 4], "little")
            out.add(idx)
            i += 6
        else:
            i += 2
    return out


def main():
    dex_dir, target_cls, target_name = sys.argv[1], sys.argv[2], sys.argv[3]
    files = sorted(f for f in os.listdir(dex_dir) if f.startswith("classes") and f.endswith(".dex"))
    target_desc = "L" + target_cls.replace(".", "/") + ";"
    found_method_ids = []
    for fn in files:
        d = Dex(os.path.join(dex_dir, fn))
        for i in range(d.method_ids_size):
            cls, proto, nm = d.method(i)
            if cls == target_desc and nm == target_name:
                found_method_ids.append(i)
    if not found_method_ids:
        print("未找到目标方法:", target_desc, target_name)
        return
    print("目标方法 id:", found_method_ids)
    tid_set = set(found_method_ids)
    for fn in files:
        d = Dex(os.path.join(dex_dir, fn))
        for desc, cdo, acc, sup in d.class_defs():
            if cdo == 0:
                continue
            _sf, _if, dm, vm = d.class_data(cdo)
            for midx, ma, co in list(dm) + list(vm):
                if co == 0:
                    continue
                refs = code_invokes(d, co)
                hit = refs & tid_set
                if hit:
                    try:
                        c, name, sig = d.method_sig(midx)
                    except Exception:
                        name, sig = "?", "?"
                    print("  [%s] %s.%s%s" % (fn, desc, name, sig))


if __name__ == "__main__":
    main()