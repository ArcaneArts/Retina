import Base
import ../forest_data.bend as D
import ../huge_mushroom.bend as M
import ../fallen_tree.bend as F
import ../block_provider.bend as B
import ../int_provider.bend as I
import ../placement_data.bend as P
import ../placement_world.bend as E
import ../surface_plants.bend as S
import ../density.bend as Q
import ../word64.bend as W
import ../placement_random.bend as R
import ./tree-world.bend as World

@unsafe
def mushroom() -> D.Mushroom:
  D.Mushroom{True{},2,B.State{1},B.State{1},[D.Mapped{1,Q.prepared_table(U32,repeated(32n))}],P.Always{},P.Always{},P.Always{}}

def repeated(n: Nat) -> List<&2,U32>:
  match n:
    case 0n: []
    case 1n+p: 1 <> repeated(p)

def fallen() -> D.Fallen:
  D.Fallen{B.State{1},I.Constant{7},[D.Mapped{1,Q.prepared_table(U32,[1,1])}],[],[],
    P.Material{P.Position{0,0,0},Q.prepared_table(U32,[0,5])},P.Material{P.Position{0,4294967295,0},Q.prepared_table(U32,[1,9,10])},
    P.Material{P.Position{0,0,0},Q.Leaf{0}},P.Always{},P.Material{P.Position{0,0,0},Q.Leaf{2}},P.Material{P.Position{0,0,0},Q.Leaf{5}}}

def policy() -> P.Policy:
  P.Policy{Q.prepared_table(U32,[0,63,51,63,39,3,51,15,0,63,63,0,0,0,0,0]),Q.prepared_table(U32,[64,0,64,128,16,64,64,2,0,1,1,0,0,0,0,0]),Q.Leaf{Q.Empty{}},2,6,3,False{}}

@unsafe
def size(patch: E.Patch) -> U32:
  match patch:
    case E.Empty{}: 0
    case E.Node{_,_,_,a,b}: U32.inc(U32.add(size(a),size(b)))

def written(result: S.Placement) -> IO(Unit):
  match result:
    case S.Placement{state,E.World{_,_,patch,_},ok}: IO.print(U32.show(W.flag(ok)) ++ " " ++ U32.show(R.low(state)) ++ " " ++ U32.show(size(patch)))

def main() -> IO(Unit):
  do IO<Unit>:
    _ : Unit <- IO.print(U32.show(W.flag(D.mushroom_valid(mushroom(),16,0))) ++ " " ++ U32.show(W.flag(D.fallen_valid(fallen(),16,0))))
    _ : Unit <- written(M.place(mushroom(),W.U64{2147483648,123456789},Q.Empty{},World.world(policy(),12),World.point(12)))
    written(F.place(fallen(),W.U64{2147483648,123456789},Q.Empty{},World.world(policy(),12),World.point(12)))
