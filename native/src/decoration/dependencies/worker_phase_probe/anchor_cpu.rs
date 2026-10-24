//! Optional calling-thread CPU for actual anchor initializers. Direct child
//! spans are subtracted once, including children in another task/root context.
//! This is not elapsed time, pure feature-body time, or GPU/service-thread CPU.
use super::*;
use std::{marker::PhantomData, rc::Rc};

#[cfg(test)]
mod tests;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct ContextId { pub(super) tag: u64, pub(super) token: u64 }
#[derive(Clone, Copy)]
struct Key { context: ContextId, tag: u64 }
struct Open {
    key: Key,
    root: usize,
    start: Option<u64>,
    children: Option<u64>,
    last_child_end: Option<u64>,
    clock_bounds_error: bool,
    outermost: bool,
    invalid: bool,
}
#[derive(Default)]
struct Stack { next: u64, frames: Vec<Open> }
#[derive(Default, Clone)]
pub(super) struct Observation {
    inclusive: Option<u64>,
    own: Option<u64>,
    outermost: bool,
    missing: bool,
    reversed: bool,
    invalid_children: bool,
    unavailable_children: bool,
    context_error: bool,
    clock_bounds_error: bool,
    abandoned: bool,
    panicked: bool,
}
impl Stack {
    fn begin(&mut self, context: ContextId, root: usize, start: Option<u64>) -> Key {
        self.next = self.next.checked_add(1).expect("anchor CPU diagnostic tag overflow");
        let key = Key { context, tag: self.next };
        let mut clock_bounds_error = false;
        if let Some(parent) = self.frames.last_mut() {
            clock_bounds_error = parent.start.zip(start).is_some_and(|(a,b)| b<a)
                || parent.last_child_end.zip(start).is_some_and(|(a,b)| b<a);
            parent.clock_bounds_error |= clock_bounds_error;
        }
        self.frames.push(Open { key, root, start, children: Some(0),
            last_child_end: None, clock_bounds_error,
            outermost: self.frames.is_empty(), invalid: false });
        key
    }
    fn end(&mut self, key: Key, end: Option<u64>, abandoned: bool,
        panicked: bool) -> Option<(usize, Observation)> {
        let position = self.frames.iter().rposition(|f| f.key.tag == key.tag
            && f.key.context == key.context)?;
        let lifo = position + 1 == self.frames.len();
        if !lifo || abandoned {
            for frame in &mut self.frames { frame.invalid = true; }
        }
        let frame = self.frames.remove(position);
        let inclusive = frame.start.zip(end).and_then(|(a, b)| b.checked_sub(a));
        let own = inclusive.zip(frame.children).and_then(|(a, b)| a.checked_sub(b));
        let observation = Observation {
            inclusive, own, outermost: frame.outermost,
            missing: frame.start.is_none() || end.is_none(),
            reversed: frame.start.zip(end).is_some_and(|(a, b)| b < a),
            invalid_children: inclusive.zip(frame.children).is_some_and(|(a,b)| b > a),
            unavailable_children: frame.children.is_none(),
            context_error: frame.invalid || !lifo,
            clock_bounds_error: frame.clock_bounds_error
                || frame.last_child_end.zip(end).is_some_and(|(a,b)| b<a),
            abandoned, panicked,
        };
        if let Some(parent) = position.checked_sub(1).and_then(|p| self.frames.get_mut(p)) {
            let inputs = parent.children.zip(inclusive);
            parent.children = inputs.and_then(|(a,b)| a.checked_add(b));
            parent.invalid |= frame.invalid || !lifo || observation.reversed
                || observation.invalid_children || (inputs.is_some() && parent.children.is_none());
            parent.clock_bounds_error |= observation.clock_bounds_error
                || parent.start.zip(end).is_some_and(|(a,b)| b<a);
            parent.last_child_end = parent.last_child_end.into_iter().chain(end).max();
        }
        Some((frame.root, observation))
    }
}
thread_local! { static STACK: RefCell<Stack> = RefCell::new(Stack::default()); }

pub(in crate::decoration::dependencies) struct Span { key: Option<Key>, _thread: PhantomData<Rc<()>> }
impl Span {
    pub(in crate::decoration::dependencies) fn start(root: usize) -> Self {
        Self::begin(root, cpu::now)
    }
    fn begin(root: usize, clock: impl FnOnce() -> Option<u64>) -> Self {
        // No clock read, frame allocation or owner clone when this separate
        // observer is disabled. Context registration counts actual starts.
        let key = joins::anchor_begin(root).map(|context| {
            let start = clock();
            STACK.with(|stack| stack.borrow_mut().begin(context, root, start))
        });
        Self { key, _thread: PhantomData }
    }
    fn finish(&mut self, clock: impl FnOnce() -> Option<u64>) {
        let Some(key) = self.key.take() else { return };
        let end = clock();
        let context_valid = joins::anchor_current(key.context);
        if let Some((root, observation)) = STACK.with(|stack| {
            let mut stack = stack.borrow_mut();
            if !context_valid {
                if let Some(frame) = stack.frames.iter_mut().find(|f| f.key.tag==key.tag) { frame.invalid=true; }
            }
            stack.end(key, end, false, std::thread::panicking())
        }) {
            joins::anchor_end(key.context, root, observation);
        }
    }
}
impl Drop for Span { fn drop(&mut self) { self.finish(cpu::now); } }

// A task that exits with live anchor guards is invalid. Drain those scalar
// frames now, so a later stale guard cannot charge another context or retain
// live frames. Ancestors are marked invalid instead of receiving false totals.
pub(super) fn close_context(context: ContextId, buffer: &mut Buffer) {
    STACK.with(|stack| {
        let mut stack = stack.borrow_mut();
        while let Some(key) = stack.frames.iter().rev()
            .find(|f| f.key.context == context).map(|f| f.key) {
            if let Some((root, observation)) = stack.end(key, cpu::now(), true,
                std::thread::panicking()) {
                buffer.record(root, observation);
            }
        }
    });
}

#[derive(Default, Clone)]
struct Row {
    started: u64, finished: u64, outermost: u64, panicked: u64,
    abandoned: u64, missing: u64, reversed: u64, invalid_children: u64, unavailable_children: u64,
    context_errors: u64, clock_bounds_errors: u64, overflow: bool,
    inclusive: u64, own: u64, outer_cpu: u64,
}
impl Row {
    fn add(&mut self, other: &Self) {
        macro_rules! sum { ($($field:ident),*) => { $(
            match self.$field.checked_add(other.$field) {
                Some(n) => self.$field = n,
                None => self.overflow = true,
            }
        )* }; }
        sum!(started, finished, outermost, panicked, abandoned, missing, reversed,
            invalid_children, unavailable_children, context_errors, clock_bounds_errors, inclusive, own, outer_cpu);
        self.overflow |= other.overflow;
    }
    fn json(&self) -> Value {
        let invalid = self.started != self.finished || self.reversed > 0
            || self.context_errors > 0 || self.clock_bounds_errors > 0 || self.abandoned > 0 || self.overflow;
        // Missing descendant clocks leave inclusive CPU usable but own CPU
        // unavailable. A known child exceeding its parent is invalid.
        let valid = if invalid || self.invalid_children > 0 { Some(false) }
        else if self.missing > 0 || self.unavailable_children > 0 {
            None
        } else { Some(true) };
        let inclusive_valid = !invalid && self.missing == 0;
        json!({"started":self.started,"finished":self.finished,"outermost":self.outermost,
            "panicked":self.panicked,"abandoned":self.abandoned,"missing_clock_calls":self.missing,
            "reversed_clock_calls":self.reversed,"invalid_child_calls":self.invalid_children,
            "unavailable_child_calls":self.unavailable_children,
            "context_errors":self.context_errors,"overflow":self.overflow,"valid":valid,
            "clock_bounds_errors":self.clock_bounds_errors,
            "inclusive_cpu_ns":inclusive_valid.then_some(self.inclusive),
            "own_cpu_ns":(valid==Some(true)).then_some(self.own),
            "outermost_cpu_ns":inclusive_valid.then_some(self.outer_cpu)})
    }
}
#[derive(Default)]
pub(super) struct Buffer {
    rows: Vec<Row>, touched: Vec<usize>, used: Vec<bool>, invalid_targets: u64,
}
thread_local! { static SPARE: RefCell<Option<Buffer>> = const { RefCell::new(None) }; }
impl Buffer {
    pub(super) fn take(root_count: usize) -> Self {
        let mut buffer = SPARE.with(|v| v.borrow_mut().take()).unwrap_or_default();
        assert!(buffer.touched.is_empty() && buffer.invalid_targets == 0);
        buffer.rows.resize_with(root_count + 1, Row::default);
        buffer.used.resize(root_count + 1, false);
        buffer
    }
    fn row(&mut self, root: usize) -> &mut Row {
        let none = self.rows.len() - 1;
        let index = if root < none { root } else { self.invalid_targets += 1; none };
        if !self.used[index] { self.used[index] = true; self.touched.push(index); }
        &mut self.rows[index]
    }
    pub(super) fn begin(&mut self, root: usize) { self.row(root).started += 1; }
    pub(super) fn record(&mut self, root: usize, observation: Observation) {
        let row = Row { finished: 1, outermost: u64::from(observation.outermost),
            panicked: u64::from(observation.panicked), abandoned: u64::from(observation.abandoned),
            missing: u64::from(observation.missing), reversed: u64::from(observation.reversed),
            invalid_children: u64::from(observation.invalid_children),
            unavailable_children: u64::from(observation.unavailable_children),
            context_errors: u64::from(observation.context_error),
            clock_bounds_errors: u64::from(observation.clock_bounds_error),
            inclusive: observation.inclusive.unwrap_or(0), own: observation.own.unwrap_or(0),
            outer_cpu: if observation.outermost { observation.inclusive.unwrap_or(0) } else { 0 },
            ..Row::default() };
        self.row(root).add(&row);
    }
    pub(super) fn extend(&mut self, mut other: Self) {
        if other.rows.is_empty() { return }
        if self.rows.is_empty() {
            self.rows.resize_with(other.rows.len(), Row::default);
            self.used.resize(other.rows.len(), false);
        }
        assert_eq!(self.rows.len(), other.rows.len());
        for index in other.touched.drain(..) {
            if !self.used[index] { self.used[index] = true; self.touched.push(index); }
            self.rows[index].add(&std::mem::take(&mut other.rows[index]));
            other.used[index] = false;
        }
        self.invalid_targets += std::mem::take(&mut other.invalid_targets);
        SPARE.with(|spare| { let mut spare = spare.borrow_mut(); if spare.is_none() { *spare=Some(other); } });
    }
    pub(super) fn summary(&self) -> Value {
        let mut total = Row::default();
        let mut rows = Vec::new();
        for &index in &self.touched {
            let row = &self.rows[index]; total.add(row);
            rows.push(json!({"executed_root":(index + 1 < self.rows.len()).then_some(index),
                "cpu":row.json()}));
        }
        let mut totals = total.json();
        if self.invalid_targets > 0 {
            totals["valid"] = json!(false);
            for key in ["inclusive_cpu_ns", "own_cpu_ns", "outermost_cpu_ns"] { totals[key]=Value::Null; }
        }
        json!({"enabled":true,"invalid_targets":self.invalid_targets,"totals":totals,"rows":rows,
            "scope":"Calling-thread CPU of actual anchor initializers. Own CPU subtracts direct child anchor intervals exactly once, across reentrant task contexts. It includes non-anchor services/lookup/replay and observer work on that thread, not pure feature code. GPU/service-thread CPU and cached-hit bodies are excluded. Counts group by executed root, distinct from dispatched stage. Sum own CPU reconciles with outermost CPU only across all enclosing and nested contexts together; per-stage totals need not match. Null means unavailable/invalid, never zero work. Normal builds exclude this observer."})
    }
}
