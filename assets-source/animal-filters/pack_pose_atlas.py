"""Pack rendered yaw views into one runtime atlas per animal. Python 3 + Pillow.

Run after render_pose_views.py: python3 pack_pose_atlas.py ./build
Writes build/runtime/{animal}.png and {animal}.json (schema version 2).
"""
from pathlib import Path
import argparse, json, struct, zlib
from PIL import Image

p = argparse.ArgumentParser(); p.add_argument('directory', nargs='?', default=str(Path(__file__).parent/'build'))
p.add_argument('--columns', type=int, default=5); a = p.parse_args()
root = Path(a.directory); manifest = json.loads((root/'pose-views.json').read_text())

def tag_srgb(path):
    raw = Path(path).read_bytes(); data = b'sRGB'+b'\x00'
    chunk = struct.pack('>I', 1)+data+struct.pack('>I', zlib.crc32(data) & 0xffffffff)
    Path(path).write_bytes(raw[:33]+chunk+raw[33:])

for animal in manifest['animals']:
    name = animal['id']; w = animal['cellWidth']; h = animal['cellHeight']; views = animal['views']
    columns = min(a.columns, len(views)); rows = (len(views)+columns-1)//columns
    atlas = Image.new('RGBA', (columns*w, rows*h), (0, 0, 0, 0))
    entries = []
    for index, view in enumerate(views):
        image = Image.open(root/view['image']).convert('RGBA'); assert image.size == (w, h)
        x = index % columns*w; y = index//columns*h
        atlas.paste(image, (x, y))
        entries.append({'yaw': view['yaw'], 'cell': [x, y, w, h], 'faceRect': view['faceRect']})
    out = root/'runtime'/f'{name}.png'; out.parent.mkdir(exist_ok=True)
    atlas.save(out, optimize=True); tag_srgb(out)
    front = entries[0]['faceRect']
    (root/'runtime'/f'{name}.json').write_text(json.dumps({
        'id': name, 'version': 2, 'image': f'{name}.png', 'width': atlas.width, 'height': atlas.height,
        'coordinateSystem': 'normalized-top-left', 'cellFaceRect': 'normalized within each cell; excludes ears and tufts',
        'yawConvention': 'positive yaw turns the muzzle toward image right; mirror the cell for negative yaw',
        'mirrorNegativeYaw': True, 'faceRect': front, 'views': entries,
        'fullFace': True, 'scope': 'on-device-only'}, indent=2)+'\n')
    print(name, atlas.size, len(entries), 'views')
