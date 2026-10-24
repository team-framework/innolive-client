"""Validate production PNG, sidecar, and binary glTF exports. Python 3 + Pillow."""
from pathlib import Path
import argparse, json, struct, hashlib, sys
from PIL import Image
p=argparse.ArgumentParser(); p.add_argument('directory',nargs='?',default=str(Path(__file__).parent/'build'));a=p.parse_args()
root=Path(a.directory); results=[]
for name in ('rabbit','owl'):
    im=Image.open(root/'runtime'/f'{name}.png'); assert im.mode=='RGBA'
    assert im.info.get('srgb')==0, 'Explicit PNG sRGB marker missing'
    sidecar=json.loads((root/'runtime'/f'{name}.json').read_text())
    assert sidecar['version']==2 and sidecar['mirrorNegativeYaw'] is True
    assert sidecar['scope']=='on-device-only' and sidecar['coordinateSystem']=='normalized-top-left'
    assert [sidecar['width'],sidecar['height']]==list(im.size) and sidecar['image']==f'{name}.png'
    views=sidecar['views']; yaws=[v['yaw'] for v in views]
    assert yaws[0]==0 and yaws[-1]==90 and yaws==sorted(set(yaws)), 'Yaw views must cover 0..90 ascending'
    for v in views:
        cx,cy,cw,ch=v['cell']; assert cw==views[0]['cell'][2] and ch==views[0]['cell'][3] and cx+cw<=im.width and cy+ch<=im.height
        x,y,w,h=v['faceRect']; assert 0<x<x+w<1 and 0<y<y+h<1
        alpha=im.crop((cx,cy,cx+cw,cy+ch)).getchannel('A'); bbox=alpha.getbbox()
        assert bbox and min(bbox[:2])>0 and bbox[2]<cw and bbox[3]<ch, f'Silhouette clipped at yaw {v["yaw"]}'
        assert alpha.getpixel((int((x+w/2)*cw),int((y+h/2)*ch)))==255, f'Face center must be opaque at yaw {v["yaw"]}'
    raw=(root/'runtime'/f'{name}.glb').read_bytes(); magic,version,length=struct.unpack_from('<III',raw)
    assert magic==0x46546c67 and version==2 and length==len(raw)
    chunk_len,chunk_type=struct.unpack_from('<II',raw,12); assert chunk_type==0x4e4f534a
    gltf=json.loads(raw[20:20+chunk_len]); assert gltf['asset']['version']=='2.0'
    assert not gltf.get('images') and not gltf.get('textures') and not gltf.get('animations') and not gltf.get('cameras')
    assert not any('uri' in b for b in gltf.get('buffers',[]))
    assert all(m.get('alphaMode','OPAQUE')=='OPAQUE' for m in gltf['materials'])
    tris=sum(gltf['accessors'][prim['indices']]['count']//3 for mesh in gltf['meshes'] for prim in mesh['primitives'])
    assert tris<15000
    # Blender 5 saves zstd-compressed .blend files by default.
    blend=(root/'source'/f'{name}.blend').read_bytes();assert blend[:7]==b'BLENDER' or blend[:4]==b'\x28\xb5\x2f\xfd'
    results.append({'id':name,'checks':'passed','png_size':list(im.size),'png_mode':im.mode,'yaw_views':yaws,'cell':views[0]['cell'][2:],'glb_triangles':tris,'glb_bytes':len(raw),'external_dependencies':0,'png_sha256':hashlib.sha256((root/'runtime'/f'{name}.png').read_bytes()).hexdigest(),'glb_sha256':hashlib.sha256(raw).hexdigest()})
report={'validation':'passed','checks':['RGBA yaw-view atlas PNG','0..90 degree yaw views with equal cells','Unclipped nonempty transparent silhouette per view','Opaque face center per view','Valid top-left normalized face rectangle per view','Self-contained glTF 2.0 binary','Opaque materials, no external textures or buffers','Under 15000 triangles per model','Editable Blender file header'],'limits':['Validation is asset-level only. Physical-device face tracking, latency, rotations, image mirroring, and privacy fallbacks must be tested in the clients.'],'assets':results}
(root/'validation-report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
