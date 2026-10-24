"""Render yaw views of the Blender animal heads for side and diagonal faces.

Run: blender -b --factory-startup --python render_pose_views.py -- --output ./build
Then: python3 pack_pose_atlas.py ./build
Opens build/source/{rabbit,owl}.blend, rotates the model root about Blender +Z, and renders
each yaw with one fixed orthographic camera and the same pixel density. Positive yaw turns
the muzzle toward image right; clients mirror these views for negative yaw, so the studio
lights are left/right symmetric. One cell covers every yaw, so the model scale never changes.
"""
import bpy, math, json, argparse, sys
from mathutils import Vector
from pathlib import Path

args = sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else []
p = argparse.ArgumentParser()
p.add_argument('--output', default=str(Path(__file__).parent/'build'))
p.add_argument('--samples', type=int, default=128)
p.add_argument('--step', type=int, default=10)
p.add_argument('--density', type=float, default=172.0, help='Pixels per interocular distance.')
ns = p.parse_args(args)
OUT = Path(ns.output).resolve(); VIEWS = OUT/'views'; VIEWS.mkdir(parents=True, exist_ok=True)
YAWS = list(range(0, 91, ns.step))
# Ears and tufts stay outside the face rectangle, matching the front sprite contract.
NOT_FACE = ('ear', 'tuft')

def geometry(scene):
    return [o for o in scene.objects if o.type == 'MESH']

def bounds(objects, face_only=False):
    xs, zs = [], []
    deps = bpy.context.evaluated_depsgraph_get()
    for o in objects:
        if face_only and any(word in o.name.lower() for word in NOT_FACE): continue
        evaluated = o.evaluated_get(deps)
        for v in evaluated.data.vertices:
            w = evaluated.matrix_world @ v.co
            xs.append(w.x); zs.append(w.z)
    return min(xs), max(xs), min(zs), max(zs)

def symmetric_studio(scene):
    for o in [o for o in scene.objects if o.type == 'LIGHT']: bpy.data.objects.remove(o)
    def light(name, pos, power, size, color):
        data = bpy.data.lights.new(name, 'AREA'); ob = bpy.data.objects.new(name, data); scene.collection.objects.link(ob)
        ob.location = pos; data.energy = power; data.shape = 'DISK'; data.size = size; data.color = color
        ob.rotation_euler = (Vector((0, 0, .4))-ob.location).to_track_quat('-Z', 'Y').to_euler()
    light('Key softbox L', (-3.5, -4.8, 5.5), 300, 4.0, (1, .91, .85))
    light('Key softbox R', (3.5, -4.8, 5.5), 300, 4.0, (1, .91, .85))
    light('Fill softbox', (0, -5, 0), 160, 3.5, (.78, .85, 1))
    light('Rim softbox', (0, 3, 4), 600, 3, (1, .83, .68))

def use_gpu(scene):
    try:
        prefs = bpy.context.preferences.addons['cycles'].preferences
        prefs.compute_device_type = 'METAL'; prefs.get_devices()
        for device in prefs.devices: device.use = True
        scene.cycles.device = 'GPU'
    except Exception as error:
        print('CPU render:', error)

manifest = {'schema_version': 2, 'generator': 'Blender '+bpy.app.version_string, 'yawStepDegrees': ns.step,
            'mirrorNegativeYaw': True, 'animals': []}
for name in ('rabbit', 'owl'):
    bpy.ops.wm.open_mainfile(filepath=str(OUT/'source'/f'{name}.blend'))
    scene = bpy.context.scene
    root = next(o for o in scene.objects if o.get('asset_id') == name)
    meshes = geometry(scene)
    use_gpu(scene)
    scene.cycles.samples = ns.samples
    scene.cycles.use_denoising = True
    symmetric_studio(scene)
    # Union of all yaw silhouettes plus a margin keeps every view unclipped at one scale.
    spans = []
    for yaw in YAWS:
        root.rotation_euler = (0, 0, math.radians(yaw)); bpy.context.view_layer.update()
        spans.append(bounds(meshes))
    margin = .12
    x0 = min(s[0] for s in spans)-margin; x1 = max(s[1] for s in spans)+margin
    # The canvas stays horizontally centered on the eye midpoint so mirroring keeps alignment.
    half = max(-x0, x1); x0, x1 = -half, half
    z0 = min(s[2] for s in spans)-margin; z1 = max(s[3] for s in spans)+margin
    width = int(math.ceil((x1-x0)*ns.density/8))*8; height = int(math.ceil((z1-z0)*ns.density/8))*8
    units_w = width/ns.density; units_h = height/ns.density; cz = (z0+z1)/2
    cam = scene.camera
    cam.data.type = 'ORTHO'; cam.data.sensor_fit = 'AUTO'; cam.data.ortho_scale = max(units_w, units_h)
    cam.data.shift_x = cam.data.shift_y = 0
    cam.location = (0, -9, cz); cam.rotation_euler = (math.pi/2, 0, 0)
    scene.render.resolution_x = width; scene.render.resolution_y = height; scene.render.resolution_percentage = 100
    views = []
    for yaw in YAWS:
        root.rotation_euler = (0, 0, math.radians(yaw)); bpy.context.view_layer.update()
        fx0, fx1, fz0, fz1 = bounds(meshes, face_only=True)
        def u(x): return (x-(-units_w/2))/units_w
        def v(z): return ((cz+units_h/2)-z)/units_h
        face = [u(fx0), v(fz1), u(fx1)-u(fx0), v(fz0)-v(fz1)]
        scene.render.filepath = str(VIEWS/f'{name}-yaw{yaw:02d}.png')
        bpy.ops.render.render(write_still=True)
        views.append({'yaw': yaw, 'image': f'views/{name}-yaw{yaw:02d}.png', 'faceRect': face})
    root.rotation_euler = (0, 0, 0)
    manifest['animals'].append({'id': name, 'cellWidth': width, 'cellHeight': height,
                                'unitsPerCell': [units_w, units_h], 'views': views})
(OUT/'pose-views.json').write_text(json.dumps(manifest, indent=2)+'\n')
print('POSE_VIEWS', json.dumps(manifest))
