"""按指令宽度遍历 code_item，导出方法体内引用的字段/方法/字符串。

用法: python method_refs.py <dex目录> <类全名> [方法名过滤]
依赖 dexlib.Dex 的原始缓冲，不做完整反编译，只做引用扫描。
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexlib import Dex

# 每个 opcode 的指令宽度（16bit code unit 数），按 dalvik 指令表
WIDTH = {}
for _op in range(0x00, 0x100):
    WIDTH[_op] = 1
_ONE = [0x00, 0x01, 0x04, 0x07, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
        0x12, 0x1D, 0x1E, 0x21, 0x27, 0x28, 0x73, 0x79, 0x7A]
_TWO = [0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1F, 0x20,
        0x22, 0x23, 0x29]
_THREE = [0x03, 0x06, 0x09, 0x14, 0x17, 0x1B, 0x24, 0x25, 0x26, 0x2A, 0x2B, 0x2C]
for _op in _ONE:
    WIDTH[_op] = 1
for _op in _TWO:
    WIDTH[_op] = 2
for _op in _THREE:
    WIDTH[_op] = 3
for _op in range(0x2D, 0x3E):      # cmp-*
    WIDTH[_op] = 2
for _op in range(0x38, 0x3E):      # if-*z
    WIDTH[_op] = 2
for _op in range(0x44, 0x6E):      # aget/aput/iget/iput/sget/sput
    WIDTH[_op] = 2
for _op in range(0x6E, 0x73):      # invoke-kind 35c
    WIDTH[_op] = 3
for _op in range(0x74, 0x79):      # invoke-kind/range 3rc
    WIDTH[_op] = 3
for _op in range(0x90, 0xB0):      # binop 23x
    WIDTH[_op] = 2
for _op in range(0xB0, 0xD0):      # binop/2addr 12x
    WIDTH[_op] = 1
for _op in range(0xD0, 0xE3):      # binop/lit16 & lit8
    WIDTH[_op] = 2
WIDTH[0x51] = 5                    # const-wide
WIDTH[0xFA] = 4                    # invoke-polymorphic
WIDTH[0xFB] = 4
WIDTH[0xFC] = 3
WIDTH[0xFD] = 3
WIDTH[0xFE] = 2
WIDTH[0xFF] = 2

FIELD_OPS = set(range(0x52, 0x6E))
INVOKE_OPS = set(range(0x6E, 0x73)) | set(range(0x74, 0x79))
INVOKE_POLY = {0xFA, 0xFB}


def payload_units(buf, off):
    """0x0100/0x0200/0x0300 payload 伪指令的真实长度（单位）。"""
    ident = int.from_bytes(buf[off:off + 2], "little")
    size = int.from_bytes(buf[off + 2:off + 4], "little")
    if ident == 0x0100:      # packed-switch-payload
        return 4 + size * 2
    if ident == 0x0200:      # sparse-switch-payload
        return 2 + size * 4
    if ident == 0x0300:      # fill-array-data-payload
        ew = int.from_bytes(buf[off + 4:off + 6], "little")
        return 4 + (size * ew + 1) // 2
    return 1


def scan(d, code_off):
    """返回 (fields, methods, strings) 引用集合。"""
    buf = d.buf
    if code_off == 0:
        return [], [], []
    insns_size = int.from_bytes(buf[code_off + 12:code_off + 16], "little")
    insns_off = code_off + 16
    fields, methods, strings = set(), set(), set()
    i, n = 0, insns_size * 2
    while i + 2 <= n:
        op = buf[insns_off + i]
        low = buf[insns_off + i + 1]
        if op == 0x00 and low == 0x00:
            i += payload_units(buf, insns_off + i) * 2
            continue
        if op in FIELD_OPS:
            fields.add(int.from_bytes(buf[insns_off + i + 2:insns_off + i + 4], "little"))
        elif op in INVOKE_OPS or op in INVOKE_POLY:
            methods.add(int.from_bytes(buf[insns_off + i + 2:insns_off + i + 4], "little"))
        elif op == 0x1A:
            strings.add(d.string(int.from_bytes(buf[insns_off + i + 2:insns_off + i + 4], "little")))
        i += WIDTH.get(op, 1) * 2
    return fields, methods, strings


def main():
    dex_dir, target = sys.argv[1], sys.argv[2]
    name_filter = sys.argv[3] if len(sys.argv) > 3 else None
    target_desc = "L" + target.replace(".", "/") + ";"
    files = sorted(f for f in os.listdir(dex_dir) if f.startswith("classes") and f.endswith(".dex"))
    for fn in files:
        d = Dex(os.path.join(dex_dir, fn))
        for desc, cdo, acc, sup in d.class_defs():
            if desc != target_desc:
                continue
            _sf, _if, dm, vm = d.class_data(cdo)
            for midx, ma, co in list(dm) + list(vm):
                _c, name, sig = d.method_sig(midx)
                if name_filter and name_filter not in name:
                    continue
                fs, ms, ss = scan(d, co)
                print("=" * 70)
                print("%s%s" % (name, sig))
                for fidx in sorted(fs):
                    try:
                        owner, t, fname = d.field(fidx)
                        print("    field: %s->%s %s : %s" % (owner, t, fname, owner))
                    except Exception:
                        pass
                for sidx in sorted(ss):
                    print("    str  : %s" % sidx)
                for j in sorted(ms):
                    try:
                        owner, _p, mname = d.method(j)
                        print("    call : %s.%s" % (owner, mname))
                    except Exception:
                        pass


if __name__ == "__main__":
    main()
