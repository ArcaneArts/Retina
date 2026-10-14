import Base
import ../region.bend as R
import ../region_stream.bend as S
import ../compression.bend as Z
import ./format-fixtures.bend as F
import ./binary-fixtures.bend as Binary
import ../density_lattice.bend as L
import ../density.bend as Q

def lattice_expected(result: Result<&1,&1,U32,L.Layout>,ok: Bool) -> IO(Unit):
  match result:
    case Done{_}: Binary.expected(ok)
    case Fail{code}: Binary.expected(Bool.and(Bool.not(ok),U32.is_eq(code,717)))

def lattice_limits() -> IO(Unit):
  do IO<Unit>:
    _ : Unit <- lattice_expected(L.layout(True{},Q.world(4294966729,4294967232,4294967241),Q.world(54,320,566),4,8,0,123456789,0),True{})
    _ : Unit <- lattice_expected(L.layout(True{},Q.world(0,0,0),Q.world(4096,384,4096),4,8,0,0,0),False{})
    _ : Unit <- lattice_expected(L.layout(True{},Q.world(0,0,0),Q.world(1,1,1),0,8,0,0,0),False{})
    lattice_expected(L.layout(True{},Q.world(0,0,0),Q.world(1,1,1),4,0,0,0,0),False{})

def rows(mode: U32) -> List<R.Record>:
  match mode:
    case 0: F.records(1024n,[])
    case 1: [F.record(1023),R.Record{0,1234,F.large()},F.record(32)]
    case 2: []
    case 3: [F.record(1024)]
    case 4: [F.record(0),F.record(0)]
    case 5: [R.Record{0,0,Z.Bytes{[0:U32*1n],0}}]
    case 6: [R.Record{0,0,Z.Bytes{[0:U32*1n],1044476}}]
    case 7: [R.Record{0,0,Z.Bytes{[0:U32*1n],4294967295}}]
    case 8: [R.Record{0,0,Z.Bytes{[0:U32*1n],4294963200}}]
    case _: [F.record(1023)]

def expected(mode: U32) -> U32:
  match mode:
    case 0: 0
    case 1: 0
    case 2: 0
    case 4: 102
    case 9: 0
    case _: 101

@unsafe
def saved(result: Result<&1,&1,U32,S.Complete>,mode: U32,path: String) -> IO(Unit):
  match result:
    case Fail{code}: F.rejected_region(Fail{code},expected(mode))
    case Done{done}:
      do IO<Unit>:
        _ : Unit <- Binary.expected(U32.is_zero(expected(mode)))
        Binary.unwrapped(S.encoded(done),path ++ ".response")

@unsafe
def appended(result: Result<&1,&1,U32,S.State>,mode: U32,path: String) -> IO(Unit):
  match result:
    case Fail{code}: saved(Fail{code},mode,path)
    case Done{state}:
      do IO<Unit>:
        result : Result<&1,&1,U32,S.Complete> <- S.complete(state)
        saved(result,mode,path)

@unsafe
def one(+mode: U32,+path: String) -> IO(Unit):
  do IO<Unit>:
    state : S.State <- S.begin(path ++ ".mca")
    result : Result<&1,&1,U32,S.State> <- S.records(rows(mode),state)
    appended(result,mode,path)

@unsafe
def cases(+i: U32,+out: String) -> IO(Unit):
  match i:
    case 10: IO.print("PASS: bounded MCA stream fixtures")
    case _:
      do IO<Unit>:
        _ : Unit <- one(i,out ++ "." ++ U32.show(i))
        cases(U32.inc(i),out)

@unsafe
def reference(+out: String) -> IO(Unit):
  do IO<Unit>:
    _ : Unit <- lattice_limits()
    _ : Unit <- F.save_plan(R.plan(rows(0)),out ++ ".reference.0.mca")
    _ : Unit <- F.save_plan(R.plan(rows(1)),out ++ ".reference.1.mca")
    _ : Unit <- F.save_plan(R.plan(rows(2)),out ++ ".reference.2.mca")
    _ : Unit <- F.save_plan(R.plan(rows(9)),out ++ ".reference.9.mca")
    cases(0,out)

@unsafe
def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)

@unsafe
def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> out <> Nil{}: reference(out)
    case _: IO.die(Unit,2,"usage: region-stream output-prefix")
