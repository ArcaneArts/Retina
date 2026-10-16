import Base
import ../light_reset.bend as L
import ../compression.bend as Z
import ./binary-fixtures.bend as T

def arguments(args: List<String>) -> IO(Unit):
  match args:
    case _ <> input <> output <> Nil{}:
      do IO<Unit>:
        result : Result<&1,&1,U32,Z.Bytes> <- L.run(input)
        T.unwrapped(result,output)
    case _: IO.die(Unit,2,"usage: light-reset input.rbp output.record")

def main() -> IO(Unit):
  do IO<Unit>:
    args : List<String> <- IO.args()
    arguments(args)
