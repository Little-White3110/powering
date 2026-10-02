"""核查「有没有把不该打进去的东西打进去」。

重要区分：dex 的 type_ids 池里出现某个类型 ≠ 这个类被打进 APK。
只有 class_defs 里出现的才是真正打包进 dex 的类定义；前者也可能只是
对外部类型（比如 compileOnly 的 xposedstub）的符号引用。
第一版统计只看了 type_ids，把 7 个 xposed 类型引用误报成泄漏，这里修正。

输出：每个关注包「真实打包的类定义数」+「仅类型引用数」。
"""
import collections
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexlib import Dex

APK = r"HolePowerRing\app\build\outputs\apk\release\app-release.apk"
OUT = os.environ.get("TEMP", ".") + r"\dexsize"

WATCH = [
    ("Lde/robv/android/xposed", "xposedstub —— 必须 0 个类定义（compileOnly）"),
    ("Landroidx/compose/material3", "Material3 —— AGENTS.md 要求别混"),
    ("Lorg/jetbrains/compose/material3", "Material3(CMP)"),
    ("Landroidx/window/", "传递依赖：window/embedding"),
    ("Lcom/materialkolor/", "传递依赖：materialkolor"),
    ("Landroidx/emoji2/", "传递依赖：emoji2"),
    ("Landroidx/navigationevent/", "传递依赖：navigationevent"),
    ("Ldev/drewhamilton/poko/", "传递依赖：poko（编译期注解，应为 0）"),
    ("Ltop/yukonga/miuix/kmp/icon", "miuix 图标集"),
    ("Lcom/powerring/hole", "本项目自己的代码"),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    z = zipfile.ZipFile(APK)
    for dn in sorted(n for n in z.namelist() if n.endswith(".dex")):
        p = os.path.join(OUT, os.path.basename(dn))
        # 每次都重新解包：APK 重新构建后 dex 变了，沿用旧文件会得出过期结论
        with open(p, "wb") as f:
            f.write(z.read(dn))

    defined = collections.Counter()
    referenced = collections.Counter()

    for dn in sorted(n for n in z.namelist() if n.endswith(".dex")):
        d = Dex(os.path.join(OUT, os.path.basename(dn)))
        # 注意：class_defs() 返回 (descriptor, class_data_off, access, super)，
        # 必须用它取真实类定义；d.type(i) 取的是类型池，会把「仅被引用的外部类型」
        # （比如 compileOnly 的 xposedstub）也算进来。
        defs = [desc for desc, *_ in d.class_defs()]
        types = [d.type(i) for i in range(d.type_ids_size)]
        defset = set(defs)
        for t in defs:
            defined[t] += 1
        for t in types:
            if t not in defset:
                referenced[t] += 1

    print(f"{'真实类定义':>10} {'仅类型引用':>10}   包 / 说明")
    print("-" * 78)
    for pref, note in WATCH:
        nd = sum(n for t, n in defined.items() if t.startswith(pref))
        nr = sum(n for t, n in referenced.items() if t.startswith(pref))
        mark = ""
        if pref.startswith("Lde/robv") and nd:
            mark = "   <-- 泄漏！"
        print(f"{nd:>10} {nr:>10}   {pref}\n{'':>23}{note}{mark}")

    print("\n=== 全 APK 汇总 ===")
    print(f"真实打包的类定义: {sum(defined.values())}")
    print(f"仅类型引用(外部/未打包): {sum(referenced.values())}")

    # 顶层包分布（按真实类定义）
    top = collections.Counter()
    for t, n in defined.items():
        seg = [p for p in t.split("/") if not p.endswith(";")]
        key = "/".join(seg[:2]) if len(seg) >= 2 else "/".join(seg[:-1])
        top[key] += n
    print("\n=== 真实类定义的包分布（前 15）===")
    for k, n in top.most_common(15):
        print(f"{n:6d} 类  {k}")


if __name__ == "__main__":
    main()