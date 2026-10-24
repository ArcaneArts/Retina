//! Sample complete anchor initializers and clock their outermost Planner.column
//! calls. Fold recursive calls in the same anchor; subtract child anchor CPU.
//! The ratio uses the SAME selected anchors as its denominator, not all anchors.
use super::*;
#[cfg(test)]
mod tests;
pub(super) const SCOPE: &str = "Deterministic 1/64 sample of executed (root,x,z) anchor keys. Every outermost Planner.column call within each selected anchor is measured, including direct material-mask and earlier-height queries. Recursive column calls in the same anchor fold into that outer span without extra clocks or duplicate CPU. A nested selected anchor opens its own span; its inclusive anchor CPU is subtracted from the parent. View cache hits, cache publication and scalar/derived-mask reads after the planner call are outside these spans. The planner call can reuse a ready state; it is not necessarily a column initializer. Load own CPU subtracts direct child anchor intervals, including children in reentrant task contexts. Denominator is own CPU of these same selected anchors, including empty/ineligible initializers. Ratios describe the sampled anchors only, not all process CPU, all anchors or elapsed time. Observer work remains included. Different salts assess spatial sample sensitivity; no per-load event history or generation owners are retained.";

pub(super) fn selected(root: usize, x: i32, z: i32, salt: u64) -> bool {
    fn mix(mut n: u64) -> u64 {
        n = (n ^ (n >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
        n = (n ^ (n >> 27)).wrapping_mul(0x94d049bb133111eb);
        n ^ (n >> 31)
    }
    mix(mix((u64::from(x as u32) << 32) | u64::from(z as u32)) ^ mix(root as u64) ^ salt) & 63 == 0
}

#[derive(Default, Clone)]
pub(super) struct Totals {
    sampled_anchors: u64, calls: u64, finished: u64, panicked_calls: u64,
    invalid_samples: u64, unavailable_samples: u64, overflow: bool,
    anchor_own: u64, own: u64, inclusive: u64,
}
impl Totals {
    pub(super) fn add(&mut self, other: &Self) {
        macro_rules! sum { ($($field:ident),*) => { $(
            match self.$field.checked_add(other.$field) {
                Some(n) => self.$field = n,
                None => self.overflow = true,
            }
        )* }; }
        sum!(sampled_anchors, calls, finished, panicked_calls, invalid_samples,
            unavailable_samples, anchor_own, own, inclusive);
        self.overflow |= other.overflow;
    }
    pub(super) fn json(&self) -> Value {
        let valid = if self.overflow || self.invalid_samples > 0 || self.calls != self.finished {
            Some(false)
        } else if self.unavailable_samples > 0 { None } else { Some(true) };
        json!({"sampled_anchors":self.sampled_anchors,"calls":self.calls,"finished":self.finished,
            "panicked_calls":self.panicked_calls,"invalid_samples":self.invalid_samples,
            "unavailable_samples":self.unavailable_samples,"overflow":self.overflow,"valid":valid,
            "sampled_anchor_own_cpu_ns":(valid==Some(true)).then_some(self.anchor_own),
            "load_own_cpu_ns":(valid==Some(true)).then_some(self.own),
            "load_inclusive_cpu_ns":(valid==Some(true)).then_some(self.inclusive),
            "load_fraction_of_sampled_anchor_own_cpu":(valid==Some(true) && self.anchor_own>0)
                .then(|| self.own as f64/self.anchor_own as f64)})
    }
}
pub(in crate::decoration::dependencies::worker_phase_probe) fn invalidate_json(value: &mut Value) {
    value["valid"] = json!(false);
    for key in ["sampled_anchor_own_cpu_ns", "load_own_cpu_ns", "load_inclusive_cpu_ns",
        "load_fraction_of_sampled_anchor_own_cpu"] { value[key] = Value::Null; }
}
struct Active { id: u64, start: Option<u64>, children: Option<u64> }
#[derive(Default)]
pub(super) struct State {
    totals: Totals,
    active: Option<Active>,
    last_end: Option<u64>,
    invalid: bool,
    unavailable: bool,
}
impl State {
    fn begin(&mut self, start: Option<u64>, children: Option<u64>, parent_start: Option<u64>,
        last_child_end: Option<u64>) -> Option<u64> {
        if self.active.is_some() { self.invalid = true; return None }
        self.totals.add(&Totals { calls: 1, ..Totals::default() });
        if self.totals.overflow { self.invalid = true; return None }
        for bound in [parent_start, last_child_end, self.last_end] {
            self.invalid |= bound.zip(start).is_some_and(|(a,b)| b<a);
        }
        let id = self.totals.calls;
        self.active = Some(Active { id, start, children });
        Some(id)
    }
    pub(super) fn child_start(&mut self, start: Option<u64>) {
        if let Some(active) = &self.active {
            self.invalid |= active.start.zip(start).is_some_and(|(a,b)| b<a);
        }
    }
    fn end(&mut self, id: u64, end: Option<u64>, children: Option<u64>,
        last_child_end: Option<u64>, context_valid: bool, panicked: bool) {
        if !self.active.as_ref().is_some_and(|active| active.id == id) {
            self.invalid = true; return
        }
        let active = self.active.take().unwrap();
        let inclusive = active.start.zip(end).and_then(|(a,b)| b.checked_sub(a));
        let nested = active.children.zip(children).and_then(|(a,b)| b.checked_sub(a));
        let own = inclusive.zip(nested).and_then(|(a,b)| a.checked_sub(b));
        self.invalid |= !context_valid || active.start.zip(end).is_some_and(|(a,b)| b<a)
            || active.children.zip(children).is_some_and(|(a,b)| b<a)
            || inclusive.zip(nested).is_some_and(|(a,b)| b>a)
            || last_child_end.zip(end).is_some_and(|(a,b)| b<a);
        self.unavailable |= inclusive.is_none() || nested.is_none();
        self.last_end = self.last_end.into_iter().chain(end).max();
        self.totals.add(&Totals { finished:1, panicked_calls:u64::from(panicked),
            own:own.unwrap_or(0), inclusive:inclusive.unwrap_or(0), ..Totals::default() });
    }
    pub(super) fn finish(mut self, anchor: &Observation, end: Option<u64>) -> Totals {
        self.invalid |= self.last_end.zip(end).is_some_and(|(a,b)| b<a);
        self.invalid |= self.active.is_some() || anchor.reversed || anchor.context_error
            || anchor.clock_bounds_error || anchor.abandoned || anchor.invalid_children;
        self.unavailable |= anchor.missing || anchor.unavailable_children || anchor.own.is_none();
        self.invalid |= anchor.own.is_some_and(|own| self.totals.own > own);
        self.totals.add(&Totals { sampled_anchors:1, anchor_own:anchor.own.unwrap_or(0),
            invalid_samples:u64::from(self.invalid), unavailable_samples:u64::from(self.unavailable),
            ..Totals::default() });
        self.totals
    }
}

pub(in crate::decoration::dependencies) struct Span {
    key: Option<(Key, u64)>, _thread: PhantomData<Rc<()>>,
}
impl Span {
    pub(in crate::decoration::dependencies) fn start() -> Self { Self::begin_outermost(cpu::now) }
    fn begin_outermost(clock: impl FnOnce() -> Option<u64>) -> Self {
        Self::begin_mode(true, clock)
    }
    #[cfg(test)]
    fn begin(clock: impl FnOnce() -> Option<u64>) -> Self { Self::begin_mode(false, clock) }
    fn begin_mode(outermost_only: bool, clock: impl FnOnce() -> Option<u64>) -> Self {
        let key = STACK.with(|stack| stack.borrow().frames.last()
            .filter(|frame| frame.column_load.as_ref().is_some_and(|state|
                !outermost_only || state.active.is_none())).map(|frame| frame.key));
        let key = key.and_then(|key| {
            if !joins::anchor_current(key.context) {
                STACK.with(|stack| {
                    if let Some(state) = stack.borrow_mut().frames.last_mut()
                        .filter(|frame| frame.key == key).and_then(|frame| frame.column_load.as_mut()) {
                        state.invalid = true;
                    }
                });
                return None
            }
            let start = clock();
            STACK.with(|stack| {
                let mut stack = stack.borrow_mut();
                let frame = stack.frames.last_mut().filter(|frame| frame.key == key)?;
                frame.column_load.as_mut()?.begin(start, frame.children, frame.start,
                    frame.last_child_end).map(|id| (key,id))
            })
        });
        Self { key, _thread:PhantomData }
    }
    fn finish(&mut self, clock: impl FnOnce() -> Option<u64>) {
        let Some((key,id)) = self.key.take() else { return };
        let end = clock();
        let context_valid = joins::anchor_current(key.context);
        STACK.with(|stack| {
            let mut stack = stack.borrow_mut();
            let last_key = stack.frames.last().map(|frame| frame.key);
            if let Some(frame) = stack.frames.iter_mut().find(|frame| frame.key == key) {
                if let Some(state) = &mut frame.column_load {
                    state.end(id,end,frame.children,frame.last_child_end,
                        context_valid && last_key==Some(key),std::thread::panicking());
                }
            }
            // A removed anchor was already marked invalid and collected. Its
            // stale guard cannot charge the current anchor, context or worker.
        });
    }
}
impl Drop for Span { fn drop(&mut self) { self.finish(cpu::now); } }
