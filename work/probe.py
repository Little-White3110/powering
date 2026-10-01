"""针对具体类的深度探针：静态常量值、方法实现者、原始指令码。

用法:
  python probe.py static  <dex目录> <类全名>            # 打印该类全部静态字段常量值
  python probe.py impls  <dex目录> <方法名>              # 找出声明了该方法的类
  python probe.py insns  <dex目录> <类全名> <方法名>     # 打印方法体的原始 16 位指令码
  python probe.py calls  <dex目录> <类全名> <方法名>     # 打印方法体内的调用/字段引用
"""
import sys
import os
import struct

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexlib import Dex, _uleb128
from method_refs import WIDTH, payload_units


def read_encoded_value(buf, off):
    """解析一个 encoded_value，返回 (python 值, 新 offset)。"""
    head = buf[off]
    off += 1
    value_type = head & 0x1F
    size = ((head >> 5) & 0x07) + 1
    if value_type == 0x1E:          # NULL
        return None, off
    if value_type == 0x1F:          # BOOLEAN
        return bool((head >> 5) & 0x07), off
    raw = buf[off:off + size]
    off += size
    if len(raw) < size:
        return None, off
    if value_type == 0x04:          # INT
        return int.from_bytes(raw, "little", signed=True), off
    if value_type == 0x06:          # LONG
        return int.from_bytes(raw, "little", signed=True), off
    if value_type == 0x02:          # SHORT
        return int.from_bytes(raw, "little", signed=True), off
    if value_type == 0x00:          # BYTE
        return int.from_bytes(raw, "little", signed=True), off
    return int.from_bytes(raw, "little", signed=False), off


def read_static_values(buf, static_values_off):
    """解析 static_values_item，返回按静态字段索引顺序的值列表。"""
    if static_values_off == 0:
        return []
    size, off = _uleb128(buf, static_values_off)
    out = []
    for _ in range(size):
        v, off = read_encoded_value(buf, off)
        out.append(v)
    return out


def load_all(dex_dir):
    files = sorted(f for f in os.listdir(dex_dir)
                   if f.startswith("classes") and f.endswith(".dex"))
    return [(fn, Dex(os.path.join(dex_dir, fn))) for fn in files]


def u16(buf, off):
    return int.from_bytes(buf[off:off + 2], "little")


def cmd_static(dex_dir, target):
    desc = "L" + target.replace(".", "/") + ";"
    for fn, d in load_all(dex_dir):
        buf = d.buf
        for idx in range(d.class_defs_size):
            base = d.class_defs_off + idx * 32
            (_ci, _acc, _sup, _io, _si, _ao, cdo, sv_off) = \
                struct.unpack_from("<IIIIIIII", buf, base)
            if d.type(_ci) != desc:
                continue
            names = []
            if cdo:
                sf, _inf, _dm, _vm = d.class_data(cdo)
                for fidx, _fa in sf:
                    try:
                        _c, _t, n = d.field(fidx)
                        names.append(n)
                    except Exception:
                        names.append("?")
            vals = read_static_values(buf, sv_off)
            print("# %s  [%s]  static_values_off=%d" % (desc, fn, sv_off))
            for i, n in enumerate(names):
                v = vals[i] if i < len(vals) else "<none>"
                print("   static %s = %r" % (n, v))


def cmd_impls(dex_dir, method_name):
    for fn, d in load_all(dex_dir):
        for desc, cdo, _acc, _sup in d.class_defs():
            if cdo == 0:
                continue
            _sf, _if, dm, vm = d.class_data(cdo)
            for midx, _ma, _co in list(dm) + list(vm):
                try:
                    _c, name, sig = d.method_sig(midx)
                except Exception:
                    continue
                if name == method_name:
                    print("[%s] %s.%s%s" % (fn, desc, name, sig))


def cmd_insns(dex_dir, target, method_name):
    desc = "L" + target.replace(".", "/") + ";"
    for fn, d in load_all(dex_dir):
        for cdesc, cdo, _acc, _sup in d.class_defs():
            if cdesc != desc or cdo == 0:
                continue
            _sf, _if, dm, vm = d.class_data(cdo)
            for midx, _ma, co in list(dm) + list(vm):
                try:
                    _c, name, sig = d.method_sig(midx)
                except Exception:
                    continue
                if name != method_name or co == 0:
                    continue
                buf = d.buf
                size = int.from_bytes(buf[co + 12:co + 16], "little")
                print("=== %s.%s%s  [%s]" % (desc, name, sig, fn))
                print("    registers=%d ins=%d outs=%d insns_units=%d"
                      % (u16(buf, co), u16(buf, co + 2), u16(buf, co + 4), size))
                off = co + 16
                i = 0
                while i < size:
                    w = u16(buf, off + i * 2)
                    op = w & 0xFF
                    if op == 0x00 and (w >> 8) == 0x00:
                        n = payload_units(buf, off + i * 2)
                        print("    %04x: %04x  (payload, %d units)" % (i, w, n))
                        i += n
                        continue
                    print("    %04x: %04x  op=%02x" % (i, w, op))
                    i += WIDTH.get(op, 1)


def main():
    mode = sys.argv[1]
    if mode == "static":
        cmd_static(sys.argv[2], sys.argv[3])
    elif mode == "impls":
        cmd_impls(sys.argv[2], sys.argv[3])
    elif mode == "insns":
        cmd_insns(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
