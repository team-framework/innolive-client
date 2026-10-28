"""Create InnoLive on-device animal filters in Blender 4.3+.

Run: blender -b --factory-startup --python generate_animals.py -- --output ./build
All models are original procedural mesh geometry; no downloaded art or textures.
Blender X = right, Z = up, -Y = camera. glTF is +X right, +Y up, +Z camera.
One unit = interocular distance. Mesh origins are the eye-midpoint plane origin.
"""
import bpy, math, json, argparse, sys, os, struct, zlib
from mathutils import Vector
from pathlib import Path

args = sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else []
p = argparse.ArgumentParser(); p.add_argument('--output', default=str(Path(__file__).parent/'build')); p.add_argument('--samples',type=int,default=96); p.add_argument('--size',type=int,default=1024); p.add_argument('--skip-render',action='store_true'); ns=p.parse_args(args)
OUT=Path(ns.output).resolve(); OUT.mkdir(parents=True,exist_ok=True)
for d in ['source','runtime','previews']: (OUT/d).mkdir(exist_ok=True)

PALETTE={
 'ivory':('F7EBD9',.83), 'cream':('FFEED0',.85), 'lavender':('BC9BB5',.9),
 'pink':('DF9BA7',.82), 'rose':('AB5973',.67), 'ink':('362B3E',.24),
 'white':('FFF9ED',.25), 'cocoa':('87554B',.83), 'dark_cocoa':('65434D',.82),
 'caramel':('B97E60',.88), 'gold':('EFB557',.55), 'pale_gold':('FFE0A0',.68)
}
ASSETS=[]
def tag_srgb(path):
    # PNG pixels are already display-referred sRGB; add the standard explicit
    # color-space marker without decoding or modifying any pixels.
    raw=Path(path).read_bytes()
    pos=8
    while pos<len(raw):
        n=struct.unpack_from('>I',raw,pos)[0]; kind=raw[pos+4:pos+8]
        if kind==b'sRGB': return
        pos+=12+n
    data=b'sRGB'+b'\x00'
    chunk=struct.pack('>I',1)+data+struct.pack('>I',zlib.crc32(data)&0xffffffff)
    Path(path).write_bytes(raw[:33]+chunk+raw[33:])
def srgb(v): return v/12.92 if v<.04045 else ((v+.055)/1.055)**2.4

def setup(name):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene=bpy.context.scene
    scene.render.engine='CYCLES'; scene.cycles.samples=ns.samples
    scene.cycles.use_denoising=False
    scene.render.resolution_x=ns.size; scene.render.resolution_y=ns.size; scene.render.resolution_percentage=100
    scene.render.film_transparent=True
    scene.render.image_settings.file_format='PNG'; scene.render.image_settings.color_mode='RGBA'; scene.render.image_settings.color_depth='8'
    scene.render.image_settings.compression=30
    scene.view_settings.view_transform='AgX'; scene.view_settings.look='AgX - Medium High Contrast'
    scene.view_settings.exposure=0; scene.view_settings.gamma=1
    scene.world=bpy.data.worlds.new('Soft studio world'); scene.world.use_nodes=True
    scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.65,.72,.86,1)
    scene.world.node_tree.nodes['Background'].inputs[1].default_value=.38
    scene.unit_settings.system='NONE'
    mats={}
    for key,(hx,rough) in PALETTE.items():
        c=tuple(srgb(int(hx[i:i+2],16)/255) for i in (0,2,4))+(1,)
        m=bpy.data.materials.new(key); m.diffuse_color=c; m.use_nodes=True
        bsdf=m.node_tree.nodes.get('Principled BSDF'); bsdf.inputs['Base Color'].default_value=c
        bsdf.inputs['Roughness'].default_value=rough; bsdf.inputs['Metallic'].default_value=0
        mats[key]=m
    root=bpy.data.objects.new(name.title()+'Filter',None); scene.collection.objects.link(root)
    root['asset_id']=name; root['scope']='on-device-only'; root['interocular_distance']=1.0
    root['version']='1.0.0'; root['axis_contract']='Blender +X right, +Z up, -Y forward; glTF +X right, +Y up, +Z forward'
    collection=bpy.data.collections.new('FILTER_GEOMETRY'); scene.collection.children.link(collection)
    return scene,mats,root,collection

def register(o,name,mat,root,coll):
    o.name=name; o.data.name=name+'Mesh'
    for c in list(o.users_collection): c.objects.unlink(o)
    coll.objects.link(o); o.parent=root
    if mat: o.data.materials.append(mat)
    if o.type=='MESH':
        for poly in o.data.polygons: poly.use_smooth=True
    return o

def sphere(name,pos,scale,mat,root,coll,seg=24,rings=14):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg,ring_count=rings,location=pos)
    o=bpy.context.object; o.scale=scale
    bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    return register(o,name,mat,root,coll)

def ear(name,side,z0,z1,width,depth,y,lean,mat,root,coll,rings=18,segments=16):
    verts=[]; faces=[]
    for i in range(rings+1):
        t=i/rings
        r=max(.015,math.sin(math.pi*t)**.65)*(1-.18*t)
        cx=side*(.48+lean*t+.04*math.sin(t*math.pi)); z=z0+(z1-z0)*t
        for j in range(segments):
            a=2*math.pi*j/segments
            verts.append((cx+width*r*math.cos(a),y+depth*r*math.sin(a),z))
    for i in range(rings):
        for j in range(segments):
            a=i*segments+j; b=i*segments+(j+1)%segments
            faces.append((a,b,b+segments,a+segments))
    faces.extend([tuple(reversed(range(segments))),tuple(rings*segments+j for j in range(segments))])
    mesh=bpy.data.meshes.new(name+'Mesh'); mesh.from_pydata(verts,[],faces); mesh.update()
    o=bpy.data.objects.new(name,mesh); coll.objects.link(o); o.parent=root; mesh.materials.append(mat)
    for f in mesh.polygons:f.use_smooth=True
    return o

def stroke(name,points,r,mat,root,coll):
    c=bpy.data.curves.new(name,'CURVE'); c.dimensions='3D'; c.resolution_u=6; c.bevel_depth=r; c.bevel_resolution=2
    sp=c.splines.new('BEZIER'); sp.bezier_points.add(len(points)-1)
    for bp,co in zip(sp.bezier_points,points): bp.co=co; bp.handle_left_type='AUTO'; bp.handle_right_type='AUTO'
    ob=bpy.data.objects.new(name,c); coll.objects.link(ob); ob.parent=root; c.materials.append(mat)
    bpy.context.view_layer.objects.active=ob; ob.select_set(True); bpy.ops.object.convert(target='MESH'); ob.select_set(False)
    for f in ob.data.polygons:f.use_smooth=True
    return ob

def feather(name,points,thickness,mat,root,coll):
    # Convex raised feather, softly beveled after mesh construction.
    front=[(x,y-thickness,z) for x,y,z in points]; back=[(x,y+thickness,z) for x,y,z in points]
    n=len(points); verts=front+back
    faces=[tuple(reversed(range(n))),tuple(n+i for i in range(n))]
    faces += [(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
    mesh=bpy.data.meshes.new(name+'Mesh'); mesh.from_pydata(verts,[],faces); mesh.update()
    ob=bpy.data.objects.new(name,mesh); coll.objects.link(ob); ob.parent=root; mesh.materials.append(mat)
    bevel=ob.modifiers.new('Soft feather edges','BEVEL'); bevel.width=.045; bevel.segments=2
    bpy.context.view_layer.objects.active=ob; ob.select_set(True); bpy.ops.object.modifier_apply(modifier=bevel.name); ob.select_set(False)
    for f in mesh.polygons:f.use_smooth=True
    return ob

def eyes(m,root,c,owl=False):
    for side,label in [(-1,'L'),(1,'R')]:
        if owl:
            sphere('Eye socket '+label,(side*.5,-.670,.10),(.49,.15,.57),m['dark_cocoa'],root,c,32,16)
            sphere('Facial disk '+label,(side*.5,-.785,.10),(.442,.115,.51),m['cream'],root,c,32,16)
            sphere('Golden iris '+label,(side*.5,-.900,.13),(.246,.091,.292),m['gold'],root,c,24,14)
            sphere('Pupil '+label,(side*.5,-.973,.13),(.158,.061,.214),m['ink'],root,c,24,14)
            y=-1.032; x=side*.5-.047; z=.212
        else:
            sphere('Eye '+label,(side*.5,-.672,.11),(.155,.105,.213),m['ink'],root,c,24,14)
            y=-.773; x=side*.5-.041; z=.18
        sphere('Eye sparkle '+label,(x,y,z),(.045,.019,.054),m['white'],root,c,12,8)
        sphere('Eye sparkle small '+label,(side*.5+.044,y+.004,.063),(.019,.012,.023),m['white'],root,c,10,6)

def bunny(m,r,c):
    sphere('Full face shell',(0,.04,-.10),(1.05,.69,1.06),m['ivory'],r,c,40,24)
    for side,label in [(-1,'L'),(1,'R')]:
        ear('Outer ear '+label,side,.56,2.55,.275,.17,.03,.18,m['ivory'],r,c)
        ear('Inner ear '+label,side,.92,2.31,.159,.045,-.134,.18,m['lavender'],r,c,16,14)
        sphere('Soft cheek '+label,(side*.76,-.537,-.365),(.207,.08,.131),m['pink'],r,c,20,12)
    eyes(m,r,c)
    for side,label in [(-1,'L'),(1,'R')]:
        sphere('Muzzle '+label,(side*.235,-.666,-.427),(.316,.13,.247),m['cream'],r,c,24,14)
        for n,(x,z) in enumerate([(.43,-.355),(.53,-.41),(.43,-.47)]):
            sphere('Whisker dot '+label+str(n),(side*x,-.764,z),(.022,.013,.022),m['caramel'],r,c,10,6)
        stroke('Smile '+label,[(0,-.805,-.43),(side*.08,-.808,-.55),(side*.18,-.79,-.56),(side*.24,-.78,-.51)],.018,m['dark_cocoa'],r,c)
    sphere('Nose',(0,-.81,-.332),(.137,.078,.09),m['rose'],r,c,20,12)
    sphere('Nose shine',(-.032,-.882,-.309),(.03,.008,.013),m['pink'],r,c,10,6)
    # Short soft central forelock, kept within the silhouette.
    for x,z,angle in [(-.12,.87,-.18),(.06,.94,.1),(.20,.83,.25)]:
        o=sphere('Forelock',(x,-.38,z),(.12,.09,.24),m['ivory'],r,c,16,10); o.rotation_euler[1]=angle

def owl(m,r,c):
    sphere('Full face shell',(0,.07,-.11),(1.075,.72,1.065),m['cocoa'],r,c,40,24)
    for side,label in [(-1,'L'),(1,'R')]:
        # Broad feather tufts are integrated into the head, not cat-like detached ears.
        feather('Crown tuft '+label,[(side*.47,.0,.59),(side*1.03,.0,1.18),(side*.98,.0,.36)],.19,m['cocoa'],r,c)
        feather('Inner crown tuft '+label,[(side*.67,-.205,.66),(side*.95,-.205,1.04),(side*.90,-.205,.56)],.025,m['caramel'],r,c)
    eyes(m,r,c,True)
    # Gold kite-shaped 3D beak, front ridge adds volume.
    verts=[(0,-.83,-.145),(-.158,-.913,-.30),(0,-.84,-.55),(.158,-.913,-.30),(0,-1.095,-.30)]
    faces=[(0,1,4),(1,2,4),(2,3,4),(3,0,4),(0,3,2,1)]
    mesh=bpy.data.meshes.new('BeakMesh'); mesh.from_pydata(verts,[],faces);mesh.update()
    ob=bpy.data.objects.new('Golden beak',mesh);c.objects.link(ob);ob.parent=r;mesh.materials.append(m['gold'])
    mod=ob.modifiers.new('Soft beak edges','BEVEL');mod.width=.025;mod.segments=2
    bpy.context.view_layer.objects.active=ob;bpy.ops.object.modifier_apply(modifier=mod.name)
    for f in mesh.polygons:f.use_smooth=True
    for side,label in [(-1,'L'),(1,'R')]:
        sphere('Cheek blush '+label,(side*.82,-.64,-.40),(.12,.034,.075),m['pink'],r,c,16,10)
        for n,(x,z) in enumerate([(.38,-.77),(.65,-.62),(.18,-.93)]):
            y=-.51 if n!=1 else -.53
            stroke('Feather chevron '+label+str(n),[(side*(x-.07),y,z+.065),(side*x,y-.026,z),(side*(x+.07),y,z+.065)],.026,m['caramel'],r,c)
    # Central forehead marking, a shallow cream V.
    stroke('Forehead V',[(-.16,-.53,.74),(0,-.59,.60),(.16,-.53,.74)],.036,m['caramel'],r,c)

def add_studio(scene):
    def light(name,pos,power,size,color):
        data=bpy.data.lights.new(name,'AREA'); ob=bpy.data.objects.new(name,data);scene.collection.objects.link(ob)
        ob.location=pos;data.energy=power;data.shape='DISK';data.size=size;data.color=color
        ob.rotation_euler=(Vector((0,0,.4))-ob.location).to_track_quat('-Z','Y').to_euler()
    light('Key softbox',(-3.5,-4.8,5.5),500,4.0,(1,.91,.85))
    light('Fill softbox',(4,-3,2),290,3.5,(.78,.85,1))
    light('Rim softbox',(0,3,4),600,3,(1,.83,.68))
    cam=bpy.data.cameras.new('Front orthographic camera');ob=bpy.data.objects.new('Front orthographic camera',cam);scene.collection.objects.link(ob)
    cam.type='ORTHO';cam.ortho_scale=4.12;ob.location=(0,-9,.63)
    ob.rotation_euler=(Vector((0,0,.63))-ob.location).to_track_quat('-Z','Y').to_euler();scene.camera=ob
    return ob

def mesh_stats(coll):
    triangles=0;vertices=0
    for o in coll.objects:
        if o.type=='MESH':o.data.calc_loop_triangles(); triangles+=len(o.data.loop_triangles);vertices+=len(o.data.vertices)
    return {'objects':len(coll.objects),'vertices':vertices,'triangles':triangles}

for name,builder in [('rabbit',bunny),('owl',owl)]:
    scene,mats,root,coll=setup(name);builder(mats,root,coll)
    bpy.ops.object.select_all(action='DESELECT');root.select_set(True)
    for o in coll.objects: o.select_set(True)
    bpy.context.view_layer.objects.active=root
    glb_path=OUT/'runtime'/f'{name}.glb'
    bpy.ops.export_scene.gltf(filepath=str(glb_path),export_format='GLB',use_selection=True,export_yup=True,export_materials='EXPORT',export_animations=False,export_cameras=False,export_lights=False,export_extras=True)
    cam=add_studio(scene)
    scene['asset_scope']='InnoLive client on-device AI only. Do not route frames through server AI.'
    scene['authoring']='Original procedural Blender mesh and material asset, generated by generate_animals.py'
    scene.render.filepath=str(OUT/'runtime'/f'{name}.png')
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'source'/f'{name}.blend'))
    if not ns.skip_render:
        bpy.ops.render.render(write_still=True)
        tag_srgb(scene.render.filepath)
        # Gallery view, same materials/geometry from a visibly three-dimensional angle.
        cam.data.ortho_scale = 4.12 if name=='rabbit' else 2.92
        cam.location=(3.0,-8.0,2.0); target=Vector((0,0,.60 if name=='rabbit' else 0.0))
        cam.rotation_euler=(target-cam.location).to_track_quat('-Z','Y').to_euler()
        scene.render.filepath=str(OUT/'previews'/f'{name}-three-quarter.png');bpy.ops.render.render(write_still=True)
        tag_srgb(scene.render.filepath)
    stats=mesh_stats(coll)
    stats.update({'id':name,'glb':f'runtime/{name}.glb','sprite':f'runtime/{name}.png','blend':f'source/{name}.blend','faceRect':([.5-1.05/4.12,.5+(.63-.96)/4.12,2.1/4.12,2.12/4.12] if name=='rabbit' else [.5-1.075/4.12,.5+(.63-.955)/4.12,2.15/4.12,2.13/4.12]),'anchor':[.5,.5+(.63-(.11 if name=='rabbit' else .13))/4.12],'glb_bytes':glb_path.stat().st_size})
    ASSETS.append(stats)
    (OUT/'runtime'/f'{name}.json').write_text(json.dumps({'id':name,'version':1,'image':f'{name}.png','width':ns.size,'height':ns.size,'faceRect':stats['faceRect'],'anchor':stats['anchor'],'coordinateSystem':'normalized-top-left','fullFace':True,'scope':'on-device-only'},indent=2)+'\n')
manifest={
 'schema_version':1,'asset_version':'1.0.0','scope':'on-device-only','generator':'Blender '+bpy.app.version_string,
 'license':'Original artwork created for InnoLive; no third-party model, texture, font, or image dependencies.',
 'glTF':{'version':'2.0','right':'+X','up':'+Y','forward':'+Z','origin':'Model root at origin. Eye centers are x=+/-0.5, Blender z=0.11 for rabbit and 0.13 for owl.','interocular_distance':1.0,'materials':'opaque PBR factors; no image textures; metallic=0'},
 'sprite':{'width':ns.size,'height':ns.size,'format':'PNG RGBA, sRGB display encoding, straight alpha','projection':'orthographic','ortho_scale':4.12,'camera_target_blender':[0,0,.63],'anchor_note':'Use each asset JSON anchor; rabbit eye z=0.11, owl eye z=0.13.','eye_distance_uv':1/4.12,'rendering_note':'Generated from the editable full 3D models; local clients may consume this baked sprite without a 3D runtime.'},
 'assets':ASSETS,
 'limits':['No facial blendshape animation or blink rig is included.','Runtime tracking, mirroring, face-loss behavior, and on-device-only enforcement belong in the clients.','Front PNG is a 2D sprite; GLB is provided for future full-pose 3D rendering.']
}
(OUT/'asset-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('ASSET_MANIFEST',json.dumps(manifest))
