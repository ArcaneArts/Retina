"""Independent replay of the registered native attachment approximation."""
from test_bend_placement_walker import predicate,shift
from test_bend_noise import f32

DIRECTIONS=[(0,-1,0),(0,1,0),(0,0,-1),(0,0,1),(-1,0,0),(1,0,0)]


def shuffle(values,rng):
    values=list(values)
    for n in range(len(values),1,-1):
        j=rng.integer(0,n-1);values[n-1],values[j]=values[j],values[n-1]
    return values


def place(recipe,rng,world,origin):
    states={row['source']:row for row in recipe['states']}
    def relative(at,d):return None if at is None else shift(at,DIRECTIONS[d])
    def test(p,at):return at is not None and predicate(p,world,at) is True
    def current(at):return None if at is None else states.get(world.material(*at))
    def put(at,material):
        if at is None or not world.minimum<=at[1]<world.maximum:return False
        world.put(*at,material);return True
    def placed(at,face):
        if at is None or not recipe['face_mask']&(1<<face) or not test(recipe['support'][face],at):return None
        old=world.material(*at)
        if old is None:return None
        row=states.get(old)
        if row and row['faces']&(1<<face):return None
        if row is None:row=states[recipe['wet_initial'] if test(recipe['source_water'],at) else recipe['initial']]
        return row['added'][face]
    def spread(at,face,source):
        source_faces=states[source]['faces']
        for d in shuffle(range(6),rng):
            if d//2==face//2 or source_faces&(1<<d):continue
            for order in recipe['spread_order']:
                pos,new_face=(at,d) if order==0 else (relative(at,d),face) if order==1 else (relative(relative(at,d),face),d^1)
                if pos is None:continue
                replace=test(recipe['air'],pos) or current(pos) is not None or test(recipe['water'],pos) and test(recipe['source_water'],pos)
                if recipe['sculk']:
                    if not test(recipe['spread_support'],relative(pos,new_face)) or not replace and not test(recipe['spread_replaceable'],pos) or order==2 and test(recipe['sturdy'][new_face],relative(at,new_face^1)):continue
                elif not replace:continue
                state=placed(pos,new_face)
                if state is not None:
                    if put(pos,state):return
                    break
    def growth(at,ds):
        if at is None:return False
        for d in ds:
            if not test(recipe['placed_on'],relative(at,d)):continue
            state=placed(at,d)
            if state is None:return False
            put(at,state)
            if rng.unit()<f32(recipe['spread_chance']):spread(at,d,state)
            return True
        return False
    if recipe['vines']:
        if not test(recipe['air'],origin):return False
        for d in recipe['directions']:
            if test(recipe['support'][d],origin):
                put(origin,states[recipe['initial']]['added'][d]);return True
        return False
    if not test(recipe['air'],origin) and not test(recipe['water'],origin):return False
    directions=shuffle(recipe['directions'],rng)
    if growth(origin,directions):return True
    for d in directions:
        placement=shuffle([p for p in recipe['directions'] if p!=(d^1)],rng)
        pos=relative(origin,d)
        if not test(recipe['air'],pos) and not test(recipe['water'],pos) and current(pos) is None:continue
        if growth(pos,placement):return True
    return False
