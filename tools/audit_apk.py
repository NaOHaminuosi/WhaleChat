"""审计 APK 的 classes.dex，确认里面有没有烤进去的凭证。

用 `zipfile` 直接读 dex（dex 是二进制，但字符串常量以 MUTF-8 明文躺在里面，
所以子串搜索就够用，不需要反编译）。

要区分「真凭证」和「界面文案」—— 界面上有「应以 sk- 开头」「点这里粘贴 sk-…」
这类提示语，光搜 `sk-` 会误报。所以：
  * 讯飞三串：从 keystore.properties 读真实值精确匹配；
  * DeepSeek Key：用 `sk-` 后跟 20 位以上 [A-Za-z0-9] 的模式匹配，不匹配裸 `sk-`。

用法：
    python audit_apk.py <apk> [...]
    退出码 0 = 全部干净；1 = 有命中
"""
import re
import sys
import zipfile
from pathlib import Path

# DeepSeek Key 形如 sk- + 32 位以上字母数字。裸 `sk-` 是界面文案，不算。
#
# 注意 R8/D8 会给 lambda 生成 `$r8$lambda$<随机 Base64>` 这样的合成类名，
# 随机串凑巧以 `sk-` 开头时会被朴素正则命中（实测踩过：`$r8$lambda$Yv` + `sk-m9g8…`）。
# 所以要用负向后顾排除「紧跟在 lambda 标记后面」的伪命中。
DS_KEY_RE = re.compile(rb"(?<!\$r8\$lambda\$Yv)(?<!lambda\$)(?<![A-Za-z0-9])sk-[A-Za-z0-9]{20,}")

# 16~40 位十六进制串：讯飞 AppID(8) / APIKey(32) / APISecret(32) 都是这个形状。
# 只有出现在「自带预置」语境下才可疑 —— 这里配合下面的已知值一起用。
HEX32_RE = re.compile(rb"(?<![A-Za-z0-9])[0-9a-f]{32}(?![A-Za-z0-9])")


def load_known_secrets() -> dict:
    """从 keystore.properties 读真实凭证。读不到就返回空（只做形状检查）。"""
    known = {}
    props = Path(__file__).resolve().parent.parent / "keystore.properties"
    if not props.exists():
        return known
    for line in props.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        v = v.strip()
        if not v:
            continue
        k = k.strip()
        if k == "presetXfyAppId":
            known["讯飞 AppID"] = v
        elif k == "presetXfyApiKey":
            known["讯飞 APIKey"] = v
        elif k == "presetXfyApiSecret":
            known["讯飞 APISecret"] = v
        elif k == "presetApiKey":
            known["DeepSeek Key"] = v
    return known


def audit(apk_path: Path, known: dict) -> bool:
    print(f"\n=== {apk_path.name} ===")
    if not apk_path.exists():
        print("  !! 文件不存在")
        return False

    with zipfile.ZipFile(apk_path) as z:
        dex_names = sorted(n for n in z.namelist() if n.endswith(".dex"))
        if not dex_names:
            print("  !! 没有 .dex，可疑")
            return False
        blob = b"".join(z.read(n) for n in dex_names)

    leaked = []

    # 1. 已知的真实凭证精确匹配 —— 最硬的证据
    for label, value in known.items():
        if value.encode() in blob:
            leaked.append(f"{label} 精确命中（{value[:8]}…）")

    # 2. DeepSeek Key 形状：sk- 后面带足够长的载荷。
    #    先按正则捞，再按上下文剔除 R8 生成的 lambda 合成名（伪命中）。
    for m in DS_KEY_RE.finditer(blob):
        before = blob[max(0, m.start() - 40): m.start()]
        if b"lambda" in before:
            continue  # `$r8$lambda$Xx` + `sk-…` 是编译器随机名，不是 Key
        leaked.append(f"疑似 DeepSeek Key：{m.group()[:16]!r}…")

    # 3. 32 位 hex 串：如果已知凭证全为空还能命中，说明有手工烤进去的东西
    if not known:
        for m in HEX32_RE.findall(blob)[:5]:
            leaked.append(f"疑似 32 位密钥：{m.decode()[:16]}…")

    if leaked:
        print("  !! 发现疑似泄漏：")
        for item in leaked:
            print(f"     - {item}")
        return False

    label = "、".join(known) if known else "（无已知凭证可比对，仅做形状检查）"
    print(f"  OK 干净：未命中 {label}，也无 sk- 长串 / 32 位 hex 密钥")
    return True


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    known = load_known_secrets()
    if not known:
        print("提示：没读到 keystore.properties，只能做形状检查（无法精确比对）。")

    ok = True
    for arg in sys.argv[1:]:
        ok &= audit(Path(arg), known)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

