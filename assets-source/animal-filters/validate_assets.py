"""Validate production PNG, sidecar, and binary glTF exports. Python 3 + Pillow."""
from pathlib import Path
import argparse, json, struct, hashlib, sys
from PIL import Image
p=argparse.ArgumentParser(); p.add_argument('directory',nargs='?',default=str(Path(__file__).parent/'build'));a=p.parse_args()
root=Path(a.directory); results=[]
for name in ('rabbit','owl'):
    im=Image.open(root/'runtime'/f'{name}.png'); assert im.mode=='RGBA' and im.size==(1024,1024)
    assert im.info.get('srgb')==0, 'Explicit PNG sRGB marker missing'
    alpha=im.getchannel('A'); bbox=alpha.getbbox(); assert bbox and min(bbox[:2])>0 and max(bbox[2:])<1024, 'Silhouette clipped'
    assert alpha.getpixel((512,700))==255,'Full-face center must be opaque'
    sidecar=json.loads((root/'runtime'/f'{name}.json').read_text())
    x,y,w,h=sidecar['faceRect']; ax,ay=sidecar['anchor']
    assert 0<x<x+w<1 and 0<y<y+h<1 and x<ax<x+w and y<ay<y+h
    assert sidecar['scope']=='on-device-only' and sidecar['coordinateSystem']=='normalized-top-left'
    assert sidecar['width']==1024 and sidecar['height']==1024 and sidecar['image']==f'{name}.png'
    raw=(root/'runtime'/f'{name}.glb').read_bytes(); magic,version,length=struct.unpack_from('<III',raw)
    assert magic==0x46546c67 and version==2 and length==len(raw)
    chunk_len,chunk_type=struct.unpack_from('<II',raw,12); assert chunk_type==0x4e4f534a
    gltf=json.loads(raw[20:20+chunk_len]); assert gltf['asset']['version']=='2.0'
    assert not gltf.get('images') and not gltf.get('textures') and not gltf.get('animations') and not gltf.get('cameras')
    assert not any('uri' in b for b in gltf.get('buffers',[]))
    assert all(m.get('alphaMode','OPAQUE')=='OPAQUE' for m in gltf['materials'])
    tris=sum(gltf['accessors'][prim['indices']]['count']//3 for mesh in gltf['meshes'] for prim in mesh['primitives'])
    assert tris<15000
    blend=(root/'source'/f'{name}.blend').read_bytes();assert blend[:7]==b'BLENDER'
    results.append({'id':name,'checks':'passed','png_size':[1024,1024],'png_mode':im.mode,'alpha_bbox':list(bbox),'center_alpha':255,'faceRect':sidecar['faceRect'],'anchor':sidecar['anchor'],'glb_triangles':tris,'glb_bytes':len(raw),'external_dependencies':0,'png_sha256':hashlib.sha256((root/'runtime'/f'{name}.png').read_bytes()).hexdigest(),'glb_sha256':hashlib.sha256(raw).hexdigest()})
report={'validation':'passed','checks':['1024x1024 RGBA PNG','Unclipped nonempty transparent silhouette','Opaque face center','Valid top-left normalized face rectangle and anchor','Self-contained glTF 2.0 binary','Opaque materials, no external textures or buffers','Under 15000 triangles per model','Editable Blender file header'],'limits':['Validation is asset-level only. Physical-device face tracking, latency, rotations, image mirroring, and privacy fallbacks must be tested in the clients.'],'assets':results}
(root/'validation-report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
