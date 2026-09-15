"""轻量 DEX 解析库：提取类描述符、方法、字段，供逆向定位使用。

仅实现本任务需要的部分：字符串池 / 类型池 / 类定义 / 类数据(方法与字段)。
"""
import struct


def _uleb128(buf, off):
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


def _uleb128p1(buf, off):
    v, off = _uleb128(buf, off)
    return v - 1, off


class Dex:
    def __init__(self, path):
        with open(path, "rb") as f:
            self.buf = f.read()
        b = self.buf
        assert b[:4] == b"dex\n", "not a dex file"
        (self.file_size, self.header_size, self.endian_tag) = struct.unpack_from("<III", b, 32)
        (self.string_ids_size, self.string_ids_off) = struct.unpack_from("<II", b, 56)
        (self.type_ids_size, self.type_ids_off) = struct.unpack_from("<II", b, 64)
        (self.proto_ids_size, self.proto_ids_off) = struct.unpack_from("<II", b, 72)
        (self.field_ids_size, self.field_ids_off) = struct.unpack_from("<II", b, 80)
        (self.method_ids_size, self.method_ids_off) = struct.unpack_from("<II", b, 88)
        (self.class_defs_size, self.class_defs_off) = struct.unpack_from("<II", b, 96)
        self._strings = {}
        self._types = {}

    # ---------- 基础池 ----------
    def string(self, idx):
        if idx in self._strings:
            return self._strings[idx]
        off = struct.unpack_from("<I", self.buf, self.string_ids_off + idx * 4)[0]
        n, off = _uleb128(self.buf, off)
        # MUTF-8：本任务场景下按 UTF-8 解码足够
        s = self.buf[off:off + n].decode("utf-8", errors="replace")
        self._strings[idx] = s
        return s

    def type(self, idx):
        if idx in self._types:
            return self._types[idx]
        sidx = struct.unpack_from("<I", self.buf, self.type_ids_off + idx * 4)[0]
        t = self.string(sidx)
        self._types[idx] = t
        return t

    def field(self, idx):
        cls, typ = struct.unpack_from("<HH", self.buf, self.field_ids_off + idx * 8)
        nidx = struct.unpack_from("<I", self.buf, self.field_ids_off + idx * 8 + 4)[0]
        return self.type(cls), self.type(typ), self.string(nidx)

    def method(self, idx):
        cls, proto = struct.unpack_from("<HH", self.buf, self.method_ids_off + idx * 8)
        nidx = struct.unpack_from("<I", self.buf, self.method_ids_off + idx * 8 + 4)[0]
        return self.type(cls), proto, self.string(nidx)

    def proto(self, idx):
        shorty, ret, params_off = struct.unpack_from("<III", self.buf, self.proto_ids_off + idx * 12)
        ret_t = self.type(ret)
        ptypes = []
        if params_off:
            size = struct.unpack_from("<I", self.buf, params_off)[0]
            for i in range(size):
                t = struct.unpack_from("<H", self.buf, params_off + 4 + i * 2)[0]
                ptypes.append(self.type(t))
        return ret_t, ptypes

    def method_sig(self, idx):
        cls, proto_idx, name = self.method(idx)
        ret_t, params = self.proto(proto_idx)
        return cls, name, "(" + "".join(params) + ")" + ret_t

    # ---------- 类 ----------
    def class_defs(self):
        """返回 [(descriptor, class_data_off, access_flags, super_idx)]"""
        out = []
        for i in range(self.class_defs_size):
            base = self.class_defs_off + i * 32
            class_idx, access, super_idx, interfaces_off, src_idx, annotations_off, class_data_off, static_off = \
                struct.unpack_from("<IIIIIIII", self.buf, base)
            out.append((self.type(class_idx), class_data_off, access, super_idx))
        return out

    def class_data(self, off):
        """解析 class_data_item，返回 (direct_methods, virtual_methods)
        每项为 (method_idx, access_flags, code_off)；字段同理返回 (field_idx, access)。"""
        if off == 0:
            return [], [], [], []
        buf = self.buf
        sf, off = _uleb128(buf, off)
        inf, off = _uleb128(buf, off)
        dm, off = _uleb128(buf, off)
        vm, off = _uleb128(buf, off)

        def read_fields(count, off, prev):
            res = []
            for _ in range(count):
                diff, off = _uleb128(buf, off)
                acc, off = _uleb128(buf, off)
                prev = prev + diff
                res.append((prev, acc))
            return res, off

        def read_methods(count, off, prev):
            res = []
            for _ in range(count):
                diff, off = _uleb128(buf, off)
                acc, off = _uleb128(buf, off)
                code_off, off = _uleb128(buf, off)
                prev = prev + diff
                res.append((prev, acc, code_off))
            return res, off

        sfields, off = read_fields(sf, off, 0)
        ifields, off = read_fields(inf, off, 0)
        dmethods, off = read_methods(dm, off, 0)
        vmethods, off = read_methods(vm, off, 0)
        return sfields, ifields, dmethods, vmethods

    # ---------- 代码字符串引用（用于定位资源 id / 常量） ----------
    def code_string_refs(self, code_off):
        """扫描 code_item 的指令流，返回该方法的字符串常量列表（粗粒度扫描 opcode 0x1a）。"""
        if code_off == 0:
            return []
        buf = self.buf
        registers_size, ins_size, outs_size, tries_size = struct.unpack_from("<HHHH", buf, code_off)
        debug_info_off, insns_size = struct.unpack_from("<II", buf, code_off + 8)
        insns_off = code_off + 16
        out = []
        i = 0
        n = insns_size * 2
        while i < n:
            unit_off = insns_off + i
            if unit_off + 2 > len(buf):
                break
            op = buf[unit_off]
            if op == 0x1A:  # const-string
                idx = struct.unpack_from("<H", buf, unit_off + 2)[0]
                try:
                    out.append(self.string(idx))
                except Exception:
                    pass
                i += 4
            elif op == 0x1B:  # const-string/jumbo
                idx = struct.unpack_from("<I", buf, unit_off + 2)[0]
                try:
                    out.append(self.string(idx))
                except Exception:
                    pass
                i += 6
            else:
                i += 2
        return out