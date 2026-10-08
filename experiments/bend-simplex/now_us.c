// Benchmark-only monotonic timer; same IO effect as Base IO.now, in microseconds.
Term bench_now_run(Env e, Term* f, IoWork* w) {
  return (Term)(io_tick() / 1000);
}
static void __attribute__((constructor)) bench_now_use(void) {
  io_eff(CID(Bench.now), bench_now_run);
}
