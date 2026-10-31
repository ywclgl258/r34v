"""
从全量中文字体里裁出 App 实际用到的那部分字，生成一个几十 KB 的子集。

为什么需要：
- Web 预览里 Flutter 靠 fonts.gstatic.com 拉 Noto 兜底渲染中文，
  而本机访问 gstatic 不通（实测 TLS 握手失败），界面中文会变成方块；
- 直接把 1.5MB 的全量字体打进 APK 又太浪费。

做法：把 lib/ 下所有 Dart 源文件扫一遍，收集出现过的汉字/假名/谚文，
连同界面固定的标点与常用符号，一起作为子集字符集。

输出 `assets/fonts/NotoSansSC-Subset.ttf`（Flutter 只吃 ttf/otf，不吃 woff2）。

前置：需要全量字体作为输入。首次准备一次即可：
    curl -L -o assets/fonts/NotoSansSC-Regular.woff2 \\
      https://cdn.jsdelivr.net/npm/@fontsource/noto-sans-sc@5.0.0/files/noto-sans-sc-chinese-simplified-400-normal.woff2

用法（改了界面文案后重跑一次）：
    python tool/subset_font.py
"""
import os
import pathlib
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
FULL = ROOT / 'assets' / 'fonts' / 'NotoSansSC-Regular.woff2'
OUT = ROOT / 'assets' / 'fonts' / 'NotoSansSC-Subset.ttf'

# 界面固定会用到、但不一定出现在源码字符串里的字符
EXTRA = (
    '0123456789'
    'abcdefghijklmnopqrstuvwxyz'
    'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
    ' .,:;!?()[]{}<>/\\|-_+=*&%#@~`\'"^$'
    '，。、；：？！“”‘’（）【】《》〈〉—…·～￥'
    '←→↑↓↔⇄•●○■□▲▼★☆✓✗×÷±≤≥≠∞'
)


def kana_and_symbols():
    """
    平假名 + 片假名 + 常见全角符号。

    为什么必须单独加：界面文案是中文，但**视频标题来自站点**，里面常带日文
    （如 `フィオニー`、`ゼンレスゾーンゼロ`）。只扫 lib/ 目录抓不到这些字，
    结果是标题里的假名渲染成方块——而标题恰恰是列表里最显眼的信息。
    假名只有约 100 个字符，全加进来代价很小。
    """
    out = []
    # 平假名 U+3041..U+309F
    out.extend(chr(c) for c in range(0x3041, 0x30A0))
    # 片假名 U+30A0..U+30FF
    out.extend(chr(c) for c in range(0x30A0, 0x3100))
    # 半角片假名 U+FF66..U+FF9F
    out.extend(chr(c) for c in range(0xFF66, 0xFFA0))
    # 全角字母数字与标点 U+FF01..U+FF5E
    out.extend(chr(c) for c in range(0xFF01, 0xFF5F))
    # 日文常见标点
    out.extend('、。〃々〆〇「」『』【】〜・…‥')
    return ''.join(out)


def collect_chars():
    chars = set(EXTRA) | set(kana_and_symbols())
    scanned = 0
    for path in (ROOT / 'lib').rglob('*.dart'):
        try:
            text = path.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            continue
        scanned += 1
        for ch in text:
            # 只留非 ASCII：中文、日文假名、韩文、以及各种全角标点
            if ord(ch) > 0x2E7F:
                chars.add(ch)
    return chars, scanned


def main():
    if not FULL.exists():
        print(f'缺字体源文件: {FULL}', file=sys.stderr)
        print('见本文件开头的 curl 命令，先下载一次。', file=sys.stderr)
        return 1

    chars, scanned = collect_chars()
    cjk = [c for c in chars if 0x4E00 <= ord(c) <= 0x9FFF]
    kana = [c for c in chars if 0x3040 <= ord(c) <= 0x30FF]
    print(f'扫描 {scanned} 个 Dart 文件，收集 {len(chars)} 个字符'
          f'（汉字 {len(cjk)}，假名 {len(kana)}）')

    options = subset.Options()
    options.flavor = None  # 直接出 ttf
    options.desubroutinize = False
    options.layout_features = ['*']
    options.notdef_outline = True
    options.hinting = True

    font = TTFont(str(FULL))
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(text=''.join(sorted(chars)))
    subsetter.subset(font)
    font.save(str(OUT))

    before = FULL.stat().st_size
    after = OUT.stat().st_size
    print(f'原字体 {before / 1024:.0f} KB  →  子集 {after / 1024:.0f} KB '
          f'（省 {100 - after * 100 / before:.0f}%）')
    print(f'输出: {OUT.relative_to(ROOT)}')
    return 0


if __name__ == '__main__':
    sys.exit(main())

