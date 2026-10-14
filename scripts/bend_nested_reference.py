"""Independent native-equation replay of patches and shared-stream nested features."""
from test_bend_decoration_counts import provider
from test_bend_decoration_heights import sample as height_sample
from test_bend_block_providers import sample
from test_bend_placement_walker import predicate,shift,terrain
from test_bend_surface_features import place as surface_place
from bend_attachment_reference import place as attachment_place
from test_bend_noise import f32,MASK

KINDS=('vegetation_patch','sequence','overlay','random_selector','simple_selector','boolean_selector','weighted_selector')


def placed(row,rng,world,origin,source):
    def run(index,at):
        if at is None:return False
        if index==len(row['placement']):return place(row['feature'],rng,world,at,source)
        op=row['placement'][index];kind=op['type']
        if kind=='count':
            any_=False
            for _ in range(max(0,provider(op['count'],rng))):any_=run(index+1,at) or any_
            return any_
        if kind=='cuboid':
            h=provider(op['y_size'],rng);w=provider(op['xz_size'],rng);l=provider(op['xz_size'],rng);any_=False
            for x in range(w+1):
                for y in range(h+1):
                    for z in range(l+1):
                        planes=int(x in (0,w))+int(y in (0,h))+int(z in (0,l))
                        if not op.get('include_edges',True) and planes>=2 or not op.get('include_interior',True) and planes==0:continue
                        any_=run(index+1,shift(at,(x,y,z))) or any_
            return any_
        if kind=='count_on_every_layer':
            layer=0;any_=False
            while True:
                found=False;i=0
                while i<provider(op['count'],rng):
                    point=shift(at,(rng.integer(0,15),0,rng.integer(0,15)))
                    if point is not None:
                        y=world.ground(point[0],point[2],layer)
                        if y is not None:
                            found=True;any_=run(index+1,(point[0],y,point[2])) or any_
                    i+=1
                if not found:return any_
                layer+=1
        if kind in ('random_chance','rarity_filter'):
            chance=f32(op['chance']) if kind=='random_chance' else f32(1/f32(op['chance']))
            if rng.unit()>=chance:return False
        elif kind=='in_square':at=shift(at,(rng.integer(0,15),0,rng.integer(0,15)))
        elif kind=='offset':at=shift(at,tuple(provider(op.get(k,0),rng) for k in ('x','y','z')))
        elif kind=='height_range':at=(at[0],height_sample(op['height'],rng,world.minimum,world.maximum-world.minimum),at[2])
        elif kind=='heightmap':
            y=world.height(at[0],at[2],op['map']);at=None if y is None or y<=world.minimum else (at[0],y,at[2])
        else:at=terrain(op,world,at,0)
        return run(index+1,at)
    return run(0,origin)


def java_order(points):
    capacity=16
    while len(points)>capacity*3//4:capacity*=2
    def key(point):
        x,y,z=point;h=(((y+z*31)*31)+x)&MASK
        return (h^(h>>16))&(capacity-1)
    return sorted(points,key=key)


def patch(recipe,rng,world,origin,source):
    def test(p,at):return at is not None and predicate(p,world,at) is True
    rx=provider(recipe['xz_radius'],rng)+1;rz=provider(recipe['xz_radius'],rng)+1;surface=[];direction=recipe['direction']
    for dx in range(-rx,rx+1):
        for dz in range(-rz,rz+1):
            ex,ez=abs(dx)==rx,abs(dz)==rz
            if ex and ez or (ex or ez) and (recipe['extra_edge_column_chance']==0 or rng.unit()>f32(recipe['extra_edge_column_chance'])):continue
            at=shift(origin,(dx,0,dz));steps=0
            while at is not None and test(recipe['air'],at) and steps<recipe['vertical_range']:
                at=shift(at,(0,direction,0));steps+=1
            steps=0
            while at is not None and not test(recipe['air'],at) and steps<recipe['vertical_range']:
                at=shift(at,(0,-direction,0));steps+=1
            ground=None if at is None else shift(at,(0,direction,0))
            if not test(recipe['air'],at) or not test(recipe['sturdy'],ground):continue
            depth=provider(recipe['depth'],rng)
            if recipe['extra_bottom_block_chance']>0 and rng.unit()<f32(recipe['extra_bottom_block_chance']):depth+=1
            pos=ground;ok=True
            for i in range(depth):
                material=sample(recipe['ground'],rng,pos,world,source.get('decoration_provider_noises',[]))
                if material is None or world.material(*pos)==material:continue
                row=next(row for row in recipe['same_blocks'] if row['source']==material)
                if not test(row['predicate'],pos):
                    if not test(recipe['replaceable'],pos):ok=i!=0;break
                    world.put(*pos,material);pos=shift(pos,(0,direction,0))
                    if pos is None:break
            if ok:surface.append(ground)
    surface=java_order(surface)
    if recipe['water_pool']:
        surface=java_order([at for at in surface if all(test(p,at) for p in recipe['enclosed'])])
        for at in surface:world.put(*at,source['water'])
    for ground in surface:
        if recipe['vegetation_chance']>0 and rng.unit()<f32(recipe['vegetation_chance']):
            at=shift(ground,(0,0 if recipe['water_pool'] else -direction,0))
            if at is not None and placed(recipe['vegetation'],rng,world,at,source) and recipe['water_pool']:
                current=world.material(*ground);pair=next((p for p in recipe['waterlogged_states'] if p[0]==current),None)
                if pair is not None:world.put(*ground,pair[1])
    return bool(surface)


def place(feature,rng,world,at,source=None):
    source=world.source if source is None else source;kind=feature['kind']
    if kind=='vegetation_patch':return patch(feature,rng,world,at,source)
    if kind in ('sequence','overlay'):
        any_=False
        for child in feature['features']:
            ok=placed(child,rng,world,at,source)
            if kind=='sequence' and not ok:return False
            any_=any_ or ok
        return True if kind=='sequence' else any_
    if kind=='random_selector':
        for option in feature['options']:
            if rng.unit()<f32(option['chance']):return placed(option['placed'],rng,world,at,source)
        return placed(feature['default'],rng,world,at,source)
    if kind=='simple_selector':
        children=feature['features'];index=rng.integer(0,len(children)-1) if len(children)>1 else 0
        return placed(children[index],rng,world,at,source)
    if kind=='boolean_selector':return placed(feature['when_true'] if rng.unit()<.5 else feature['when_false'],rng,world,at,source)
    if kind=='weighted_selector':
        n=rng.next()%sum(entry['weight'] for entry in feature['entries'])
        for entry in feature['entries']:
            if n<entry['weight']:return placed(entry['placed'],rng,world,at,source)
            n-=entry['weight']
        raise AssertionError('invalid weighted selector')
    if kind=='attachment_growth':return attachment_place(feature,rng,world,at)
    if kind in ('simple_block','block_column','disk'):return surface_place(feature,rng,world,at,source)
    raise AssertionError(kind)
