"""Independent Python voxel/RNG replay of registered forest feature recipes."""
import copy

from test_bend_placement_walker import predicate,shift
from test_bend_block_providers import sample
from test_bend_noise import f32,MASK

HORIZONTAL=[(0,0,-1),(1,0,0),(0,0,1),(-1,0,0)]


def below(rng,n):return rng.integer(0,n-1) if n>1 else 0


def at(origin,direction,n=1):return shift(origin,tuple(x*n for x in direction))


def mushroom(recipe,rng,world,origin):
    height=below(rng,3)+4
    if below(rng,12)==0:height*=2
    def test(p,pos,view=world):return pos is not None and predicate(p,view,pos) is True
    def material(provider):return sample(provider,rng,origin,world,world.source.get('decoration_provider_noises',[]),False)
    top=at(origin,(0,1,0),height+1)
    if origin[1]<world.minimum+1 or top is None or top[1]>world.maximum-1 or not test(recipe['support'],at(origin,(0,-1,0))):return False
    for dy in range(height+1):
        radius=0 if recipe['red'] or dy<=3 else recipe['foliage_radius']
        for dx in range(-radius,radius+1):
            for dz in range(-radius,radius+1):
                if not test(recipe['clearance'],shift(origin,(dx,dy,dz))):return False
    # Replacement sees the pre-feature terrain; provider predicates see this
    # feature's earlier accepted writes. Those views deliberately differ.
    snapshot=copy.copy(world);snapshot.patch=dict(world.patch)
    for dy in (range(height-3,height+1) if recipe['red'] else (height,)):
        radius=recipe['foliage_radius']-(recipe['red'] and dy==height)
        for dx in range(-radius,radius+1):
            for dz in range(-radius,radius+1):
                min_x,max_x,min_z,max_z=dx==-radius,dx==radius,dz==-radius,dz==radius
                edge_x,edge_z=min_x or max_x,min_z or max_z
                if recipe['red'] and dy<height and edge_x==edge_z or not recipe['red'] and edge_x and edge_z:continue
                if recipe['red']:
                    center=recipe['foliage_radius']-2
                    faces=(dx<-center,dx>center,dz<-center,dz>center,dy>=height-1)
                else:
                    faces=(min_x or edge_z and dx==1-radius,max_x or edge_z and dx==radius-1,
                           min_z or edge_x and dz==1-radius,max_z or edge_x and dz==radius-1,False)
                mask=sum(int(on)<<i for i,on in enumerate(faces))
                pos=shift(origin,(dx,dy,dz));value=material(recipe['cap'])
                row=next((row for row in recipe['faces'] if row['source']==value),None)
                value=value if row is None else row['states'][mask]
                if test(recipe['replaceable'],pos,snapshot):world.put(*pos,value)
    for dy in range(height):
        pos=at(origin,(0,1,0),dy);value=material(recipe['stem'])
        if test(recipe['replaceable'],pos,snapshot):world.put(*pos,value)
    return True


def fallen(recipe,rng,world,origin):
    def test(p,pos):return pos is not None and predicate(p,world,pos) is True
    def material(provider,pos):return sample(provider,rng,pos,world,world.source.get('decoration_provider_noises',[]),False)
    def decorate(decorators,logs):
        for decorator in decorators:
            kind=decorator['type']
            if kind=='attached_to_logs':
                shuffled=list(logs)
                for n in range(len(shuffled),1,-1):
                    j=below(rng,n);shuffled[n-1],shuffled[j]=shuffled[j],shuffled[n-1]
                for point in shuffled:
                    direction=decorator['directions'][below(rng,len(decorator['directions']))]
                    pos=at(point,direction)
                    if rng.unit()<=f32(decorator['probability']) and test(recipe['air'],pos):world.put(*pos,material(decorator['provider'],pos))
            elif kind=='trunk_vine':
                for point in logs:
                    for i,direction in enumerate((HORIZONTAL[3],HORIZONTAL[1],HORIZONTAL[0],HORIZONTAL[2])):
                        if below(rng,3)>0:
                            pos=at(point,direction)
                            if test(recipe['air'],pos):world.put(*pos,decorator['states'][i])
            elif kind=='shelf_mushroom':
                if rng.unit()>=f32(decorator['probability']) or not logs:continue
                directions=(0,2) if logs[0][0]!=logs[-1][0] else (1,3)
                for point in logs:
                    for direction in directions:
                        if rng.unit()>.25:continue
                        pos=at(point,HORIZONTAL[direction])
                        if not test(recipe['replaceable'],pos) or test(recipe['water'],pos):continue
                        if any(test(recipe['water'],at(pos,d)) for d in HORIZONTAL):continue
                        if any(test(recipe['shelf'],at(p,d)) for p in (pos,point) for d in HORIZONTAL):continue
                        world.put(*pos,decorator['states'][direction][below(rng,2)])
            else:raise AssertionError(kind)
    # Stump/decorators remain even when later horizontal clearance fails.
    world.put(*origin,material(recipe['trunk'],origin));decorate(recipe['stump_decorators'],[origin])
    direction=HORIZONTAL[below(rng,4)]
    from test_bend_decoration_counts import provider
    length=provider(recipe['log_length'],rng)-2
    pos=at(origin,direction,2+below(rng,2));pos=None if pos is None else at(pos,(0,1,0))
    if pos is None:return True
    for _ in range(6):
        if test(recipe['clearance'],pos) and test(recipe['sturdy'],pos):break
        pos=at(pos,(0,-1,0))
        if pos is None:return True
    gap=0
    for i in range(max(0,length)):
        point=at(pos,direction,i)
        if not test(recipe['clearance'],point):return True
        if not test(recipe['sturdy'],point):
            gap+=1
            if gap>2:return True
        else:gap=0
    logs=[]
    for i in range(max(0,length)):
        point=at(pos,direction,i);value=material(recipe['trunk'],point)
        row=next((row for row in recipe['axes'] if row['source']==value),None)
        value=value if row is None else row['states'][int(direction[2]!=0)]
        world.put(*point,value);logs.append(point)
    def bucket(point):
        x,y,z=point;h=(((y+z*31)*31)+x)&MASK
        return (h^(h>>16))&(31 if length>12 else 15)
    logs.sort(key=bucket)
    decorate(recipe['log_decorators'],logs)
    return True


def place(recipe,rng,world,origin):
    return mushroom(recipe,rng,world,origin) if recipe['kind']=='huge_mushroom' else fallen(recipe,rng,world,origin)
