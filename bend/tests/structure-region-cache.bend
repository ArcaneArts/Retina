import Base
import ../structure_region.bend as Region
import ../structure_candidates.bend as C
import ../structure_metadata.bend as Meta
import ../word64.bend as W

@unsafe
def fill(+i: U32,n: U32,cache: Region.Cache) -> Region.Cache:
  match n:
    case 0: cache
    case _: fill(U32.inc(i),U32.sub(n,1),Region.stored(cache,Region.Entry{W.U64{2147483648,123},C.Start{0,i,0},None{}}))

def missing(value: Maybe<&2,Maybe<&2,Meta.Start>>) -> Bool:
  match value:
    case None{}: True{}
    case _: False{}

def rejected(value: Maybe<&2,Maybe<&2,Meta.Start>>) -> Bool:
  match value:
    case Some{None{}}: True{}
    case _: False{}

@unsafe
def checked(cache: Region.Cache) -> Bool:
  match cache:
    case Region.Cache{+entries,hits,misses}:
      Bool.and(Bool.and(U32.is_eq(U32.from_nat(List.length(&2,Region.Entry,entries)),64),Bool.and(U32.is_zero(hits),U32.is_eq(misses,70))),
        Bool.and(missing(Region.find(entries,W.U64{2147483648,123},C.Start{0,5,0})),
          Bool.and(rejected(Region.find(entries,W.U64{2147483648,123},C.Start{0,6,0})),
            Bool.and(rejected(Region.find(entries,W.U64{2147483648,123},C.Start{0,69,0})),
              Bool.and(missing(Region.find(entries,W.U64{0,123},C.Start{0,69,0})),
                Bool.and(missing(Region.find(entries,W.U64{2147483648,124},C.Start{0,69,0})),missing(Region.find(entries,W.U64{2147483648,123},C.Start{1,69,0}))))))))

def run() -> Bool:
  checked(fill(0,70,Region.empty()))
