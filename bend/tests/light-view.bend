import Base
import ../light_region.bend as L
import ../light_volume.bend as V
import ../terrain_surface.bend as S
import ../density.bend as Q

def present(value: Maybe<&2,L.View>) -> U32:
  match value:
    case None{}: 0
    case Some{_}: 1

def store() -> Maybe<&2,L.Store>:
  Some{L.Store{S.Tile{4294966784,4294966784,512,512,123,456},4294967232,384,
    Q.Leaf{L.Tile{96,96,Q.Leaf{V.Uniform{0}}}}}}

def expected(ok: Bool) -> IO(Unit):
  match ok:
    case True{}: IO.pure(Unit,Unit{})
    case False{}: IO.die(Unit,1,"incorrect cached lighting coverage")

@unsafe
def verify(x: U32,z: U32,low: U32,high: U32,minimum: U32,height: U32,wanted: U32) -> IO(Unit):
  expected(U32.is_eq(present(L.view(store(),x,z,low,high,minimum,height)),wanted))

def main() -> IO(Unit):
  do IO<Unit>:
    # Chunk -33 is one chunk beyond the lit region's west/north outer edge.
    _ : Unit <- verify(4294967263,4294967265,123,456,4294967232,384,0)
    _ : Unit <- verify(4294967265,4294967263,123,456,4294967232,384,0)
    _ : Unit <- verify(4294967264,4294967265,123,456,4294967232,384,0)
    _ : Unit <- verify(4294967265,4294967264,123,456,4294967232,384,0)
    _ : Unit <- verify(4294967265,4294967265,123,456,4294967232,384,1)
    _ : Unit <- verify(4294967294,4294967294,123,456,4294967232,384,1)
    _ : Unit <- verify(4294967295,4294967294,123,456,4294967232,384,0)
    _ : Unit <- verify(4294967294,4294967295,123,456,4294967232,384,0)
    _ : Unit <- verify(0,4294967294,123,456,4294967232,384,0)
    _ : Unit <- verify(4294967294,0,123,456,4294967232,384,0)
    _ : Unit <- verify(4294967265,4294967265,124,456,4294967232,384,0)
    _ : Unit <- verify(4294967265,4294967265,123,457,4294967232,384,0)
    _ : Unit <- verify(4294967265,4294967265,123,456,0,384,0)
    _ : Unit <- verify(4294967265,4294967265,123,456,4294967232,256,0)
    IO.print("PASS: lighting coverage boundaries, seed and height identity")
