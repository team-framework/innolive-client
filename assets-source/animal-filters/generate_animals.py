"""Create InnoLive on-device animal filters in Blender 4.3+.

Run: blender -b --factory-startup --python generate_animals.py -- --output ./build
All models are original procedural mesh geometry; no downloaded art or textures.
Facial features are ray-cast onto the head surface so side and diagonal views stay attached.
Blender X = right, Z = up, -Y = camera. glTF is +X right, +Y up, +Z camera.
One unit = interocular distance. Mesh origins are the eye-midpoint plane origin.
"""
import bpy, math, json, argparse, sys, os, struct, zlib
from mathutils import Vector, Quaternion
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

FRONT=Vector((0,-1,0))
def snap(targets,x,z,lift=0.0):
    # Front-most hit of a ray cast toward +Y; features sit on the head instead of a flat front plane.
    bpy.context.view_layer.update(); best=None; origin=Vector((x,-6,z))
    for o in targets:
        mw=o.matrix_world; inv=mw.inverted()
        hit,loc,nor,_=o.ray_cast(inv@origin,(inv.to_3x3()@Vector((0,1,0))).normalized())
        if hit:
            w=mw@loc; n=(inv.to_3x3().transposed()@nor).normalized()
            if best is None or w.y<best[0].y: best=(w,n)
    assert best, f'No surface at {x},{z}'
    return best[0]+best[1]*lift,best[1]

def orient(o,normal,spin=0.0):
    o.rotation_mode='QUATERNION'
    o.rotation_quaternion=normal.to_track_quat('-Y','Z')@Quaternion((0,1,0),spin)

def facing(normal,bias):
    # bias 0 keeps the surface normal; 1 faces the camera, used for forward-looking eyes.
    return normal.lerp(FRONT,bias).normalized()

def on(name,targets,x,z,scale,mat,root,coll,embed=.5,bias=0.0,spin=0.0,seg=24,rings=14):
    loc,n=snap(targets,x,z); d=facing(n,bias)
    o=sphere(name,(0,0,0),scale,mat,root,coll,seg,rings)
    o.location=loc+d*scale[1]*(1-embed); orient(o,d,spin)
    return o

def child(name,parent,offset,scale,mat,root,coll,seg=12,rings=8):
    # Offsets are in the parent's oriented frame: local -Y faces out of the surface.
    o=sphere(name,(0,0,0),scale,mat,root,coll,seg,rings); o.parent=parent; o.location=offset
    return o

def surface_stroke(name,points,r,mat,targets,root,coll):
    return stroke(name,[snap(targets,x,z,r*.35)[0] for x,z in points],r,mat,root,coll)

def horn(name,base,tip,width,depth,mat,root,coll,front=0.0,rings=18,segments=16):
    # Lofted tuft/ear tapering from a rounded base to a soft tip; front shifts it onto a parent's face.
    verts=[];faces=[]
    for i in range(rings+1):
        t=i/rings; r=max(.04,(1-t)**.8)*(0.9+0.25*math.sin(math.pi*t))
        cx=base[0]+(tip[0]-base[0])*t; cy=base[1]+(tip[1]-base[1])*t-front*r; cz=base[2]+(tip[2]-base[2])*t
        for j in range(segments):
            a=2*math.pi*j/segments; verts.append((cx+width*r*math.cos(a),cy+depth*r*math.sin(a),cz))
    for i in range(rings):
        for j in range(segments):
            a=i*segments+j;b=i*segments+(j+1)%segments;faces.append((a,b,b+segments,a+segments))
    faces.extend([tuple(reversed(range(segments))),tuple(rings*segments+j for j in range(segments))])
    mesh=bpy.data.meshes.new(name+'Mesh');mesh.from_pydata(verts,[],faces);mesh.update()
    o=bpy.data.objects.new(name,mesh);coll.objects.link(o);o.parent=root;mesh.materials.append(mat)
    for f in mesh.polygons:f.use_smooth=True
    return o

def bunny(m,r,c):
    # A deep head keeps eyes, muzzle and cheeks inside the silhouette at every yaw.
    shell=sphere('Full face shell',(0,.22,-.10),(1.05,.92,1.06),m['ivory'],r,c,48,32)
    for side,label in [(-1,'L'),(1,'R')]:
        ear('Outer ear '+label,side,.56,2.55,.275,.17,.15,.18,m['ivory'],r,c)
        ear('Inner ear '+label,side,.92,2.31,.159,.045,-.014,.18,m['lavender'],r,c,16,14)
    for side,label in [(-1,'L'),(1,'R')]:
        on('Soft cheek '+label,[shell],side*.76,-.365,(.207,.08,.131),m['pink'],r,c,embed=.55,seg=20,rings=12)
        eye=on('Eye '+label,[shell],side*.5,.11,(.155,.105,.213),m['ink'],r,c,embed=.45,bias=.35)
        child('Eye sparkle '+label,eye,(-.041,-.095,.07),(.045,.019,.054),m['white'],r,c)
        child('Eye sparkle small '+label,eye,(.044,-.092,-.047),(.019,.012,.023),m['white'],r,c,10,6)
    muzzles=[on('Muzzle '+label,[shell],side*.235,-.427,(.316,.13,.247),m['cream'],r,c,embed=.4)
             for side,label in [(-1,'L'),(1,'R')]]
    face=muzzles+[shell]
    for side,label in [(-1,'L'),(1,'R')]:
        for n,(x,z) in enumerate([(.43,-.355),(.50,-.41),(.43,-.47)]):
            on('Whisker dot '+label+str(n),face,side*x,z,(.022,.013,.022),m['caramel'],r,c,embed=.3,seg=10,rings=6)
        surface_stroke('Smile '+label,[(0,-.43),(side*.08,-.55),(side*.18,-.56),(side*.24,-.51)],.018,m['dark_cocoa'],face,r,c)
    nose=on('Nose',face,0,-.332,(.137,.078,.09),m['rose'],r,c,embed=.3,seg=20,rings=12)
    child('Nose shine',nose,(-.032,-.072,.023),(.03,.008,.013),m['pink'],r,c,10,6)
    # Forelock tufts stand up from the crown instead of lying along it.
    for x,z,angle in [(-.12,.80,-.18),(.06,.86,.1),(.20,.77,.25)]:
        on('Forelock',[shell],x,z,(.12,.09,.24),m['ivory'],r,c,embed=.6,bias=.8,spin=angle,seg=16,rings=10)

def owl(m,r,c):
    shell=sphere('Full face shell',(0,.22,-.11),(1.075,.95,1.065),m['cocoa'],r,c,48,32)
    for side,label in [(-1,'L'),(1,'R')]:
        # Tapered 3D tufts replace flat feather slabs so the profile stays rounded.
        horn('Crown tuft '+label,(side*.55,.12,.55),(side*1.0,.05,1.24),.26,.2,m['cocoa'],r,c)
        horn('Inner crown tuft '+label,(side*.6,.12,.68),(side*.95,.05,1.14),.12,.05,m['caramel'],r,c,front=.17,rings=14,segments=12)
    for side,label in [(-1,'L'),(1,'R')]:
        # A shallower disk stack keeps the eyes from standing off the head in profile.
        socket=on('Eye socket '+label,[shell],side*.5,.10,(.49,.12,.57),m['dark_cocoa'],r,c,embed=.6,bias=.3,seg=32,rings=16)
        child('Facial disk '+label,socket,(0,-.07,0),(.442,.09,.51),m['cream'],r,c,32,16)
        child('Golden iris '+label,socket,(0,-.14,.03),(.246,.07,.292),m['gold'],r,c,24,14)
        child('Pupil '+label,socket,(0,-.19,.03),(.158,.05,.214),m['ink'],r,c,24,14)
        child('Eye sparkle '+label,socket,(-.047,-.235,.112),(.045,.019,.054),m['white'],r,c)
        child('Eye sparkle small '+label,socket,(.044,-.232,-.037),(.019,.012,.023),m['white'],r,c,10,6)
    # Gold kite-shaped 3D beak rooted in the head surface.
    y0=snap([shell],0,-.30)[0].y
    verts=[(0,y0+.02,-.145),(-.158,y0-.06,-.30),(0,y0+.01,-.55),(.158,y0-.06,-.30),(0,y0-.27,-.30)]
    faces=[(0,1,4),(1,2,4),(2,3,4),(3,0,4),(0,3,2,1)]
    mesh=bpy.data.meshes.new('BeakMesh'); mesh.from_pydata(verts,[],faces);mesh.update()
    ob=bpy.data.objects.new('Golden beak',mesh);c.objects.link(ob);ob.parent=r;mesh.materials.append(m['gold'])
    mod=ob.modifiers.new('Soft beak edges','BEVEL');mod.width=.025;mod.segments=2
    bpy.context.view_layer.objects.active=ob;bpy.ops.object.modifier_apply(modifier=mod.name)
    for f in mesh.polygons:f.use_smooth=True
    for side,label in [(-1,'L'),(1,'R')]:
        on('Cheek blush '+label,[shell],side*.82,-.40,(.12,.034,.075),m['pink'],r,c,embed=.5,seg=16,rings=10)
        for n,(x,z) in enumerate([(.38,-.77),(.65,-.62),(.18,-.93)]):
            surface_stroke('Feather chevron '+label+str(n),[(side*(x-.07),z+.065),(side*x,z),(side*(x+.07),z+.065)],.026,m['caramel'],[shell],r,c)
    surface_stroke('Forehead V',[(-.16,.74),(0,.60),(.16,.74)],.036,m['caramel'],[shell],r,c)

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
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'source'/f'{name}.blend'))
    if not ns.skip_render:
        # Runtime sprites come from render_pose_views.py and pack_pose_atlas.py. Gallery view, same materials/geometry from a visibly three-dimensional angle.
        cam.data.ortho_scale = 4.12 if name=='rabbit' else 2.92
        cam.location=(3.0,-8.0,2.0); target=Vector((0,0,.60 if name=='rabbit' else 0.0))
        cam.rotation_euler=(target-cam.location).to_track_quat('-Z','Y').to_euler()
        scene.render.filepath=str(OUT/'previews'/f'{name}-three-quarter.png');bpy.ops.render.render(write_still=True)
        tag_srgb(scene.render.filepath)
    stats=mesh_stats(coll)
    stats.update({'id':name,'glb':f'runtime/{name}.glb','sprite':f'runtime/{name}.png','blend':f'source/{name}.blend','glb_bytes':glb_path.stat().st_size})
    ASSETS.append(stats)
manifest={
 'schema_version':2,'asset_version':'2.0.0','scope':'on-device-only','generator':'Blender '+bpy.app.version_string,
 'license':'Original artwork created for InnoLive; no third-party model, texture, font, or image dependencies.',
 'glTF':{'version':'2.0','right':'+X','up':'+Y','forward':'+Z','origin':'Model root at origin. Eye centers are x=+/-0.5, Blender z=0.11 for rabbit and 0.13 for owl.','interocular_distance':1.0,'materials':'opaque PBR factors; no image textures; metallic=0'},
 'sprite':{'format':'PNG RGBA yaw-view atlas, sRGB display encoding, straight alpha','projection':'orthographic','views':'render_pose_views.py renders 0..90 degree yaw; pack_pose_atlas.py writes runtime PNG/JSON; clients mirror negative yaw.','rendering_note':'Generated from the editable full 3D models; local clients consume baked yaw views without a 3D runtime.'},
 'assets':ASSETS,
 'limits':['No facial blendshape animation or blink rig is included.','Runtime tracking, mirroring, face-loss behavior, and on-device-only enforcement belong in the clients.','Sprites cover yaw only; pitch uses the nearest yaw view. GLB is provided for future full-pose 3D rendering.']
}
(OUT/'asset-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('ASSET_MANIFEST',json.dumps(manifest))
