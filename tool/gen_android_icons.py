from PIL import Image
import os

# 生成 Android 各密度的启动图标。
# 不依赖 flutter_launcher_icons（那需要跑 Flutter 工具链），
# 纯 Pillow 直接出图，CI 里任何环境都能跑。
S = 1024
src = Image.open('assets/icon.png').convert('RGBA')

# mipmap 尺寸：与 Android 官方推荐一致
densities = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

base = 'android/app/src/main/res'
for folder, size in densities.items():
    out_dir = os.path.join(base, folder)
    os.makedirs(out_dir, exist_ok=True)
    icon = src.resize((size, size), Image.LANCZOS)
    icon.save(os.path.join(out_dir, 'ic_launcher.png'))
    print(f'{folder}/ic_launcher.png  {size}x{size}')

# 自适应图标前景：内容缩到 66% 安全区，避免圆形遮罩切掉
safe = int(S * 0.66)
fg = Image.new('RGBA', (S, S), (0, 0, 0, 0))
inner = src.resize((safe, safe), Image.LANCZOS)
fg.paste(inner, ((S - safe) // 2, (S - safe) // 2), inner)

for folder, size in densities.items():
    out_dir = os.path.join(base, folder)
    f = fg.resize((size, size), Image.LANCZOS)
    f.save(os.path.join(out_dir, 'ic_launcher_foreground.png'))
    print(f'{folder}/ic_launcher_foreground.png  {size}x{size}')

# 自适应图标背景色（站点主红）
os.makedirs(os.path.join(base, 'values'), exist_ok=True)
with open(os.path.join(base, 'values', 'colors.xml'), 'w', encoding='utf-8') as fh:
    fh.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
    fh.write('    <color name="ic_launcher_background">#D1404A</color>\n')
    fh.write('</resources>\n')
print('values/colors.xml')
