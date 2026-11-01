package jasx.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests of the shared runtime of interactive targets (jasx-spec/ui.md), without any target. */
class UiTest {

    /** A scheduler driven by the test: computations wait until {@link #flush}, as on a target (ui.md §3). */
    static final class TestScheduler implements Scheduler {
        final List<Computation> waiting = new ArrayList<>();

        @Override
        public void schedule(Computation computation) {
            if (!waiting.contains(computation)) {
                waiting.add(computation);
            }
        }

        /** Re-runs what waits, in creation order, holes before effects. */
        void flush() {
            while (!waiting.isEmpty()) {
                List<Computation> now = new ArrayList<>(waiting);
                waiting.clear();
                now.sort(Comparator.comparingLong(Computation::order));
                now.stream().filter(c -> !(c instanceof Effect) && !c.isDisposed()).forEach(Computation::update);
                now.stream().filter(c -> c instanceof Effect && !c.isDisposed()).forEach(Computation::update);
            }
        }
    }

    /** A hole as a target would write one: it records each value it computes. */
    static final class TestHole extends Computation {
        final Supplier<Object> value;
        final List<Object> values = new ArrayList<>();

        TestHole(Scheduler scheduler, Supplier<Object> value) {
            super(scheduler);
            this.value = value;
            update();
        }

        @Override
        public void update() {
            reset();
            values.add(track(value));
        }
    }

    @Nested
    class ReactiveValues {
        final TestScheduler scheduler = new TestScheduler();

        @Test
        void aHoleReRunsWhenAStateItReadChangesAndOnlyThen() {
            State<Integer> count = Reactive.state(1);
            State<String> other = Reactive.state("x");
            TestHole hole = new TestHole(scheduler, () -> count.get() * 10);

            other.set("y");
            scheduler.flush();
            count.set(2);
            count.set(3); // two changes before the flush: one re-run
            scheduler.flush();
            assertEquals(List.of(10, 30), hole.values);
        }

        @Test
        void settingAnEqualValueChangesNothing() {
            State<String> name = Reactive.state("a");
            TestHole hole = new TestHole(scheduler, name::get);
            name.set("a");
            assertTrue(scheduler.waiting.isEmpty());
            assertEquals(List.of("a"), hole.values);
        }

        @Test
        void aDerivedValueIsComputedLazilyAndOnce() {
            State<Integer> count = Reactive.state(2);
            int[] computed = {0};
            Derived<Integer> doubled = Reactive.derived(() -> {
                computed[0]++;
                return count.get() * 2;
            });
            assertEquals(0, computed[0], "not computed before it is read");
            TestHole hole = new TestHole(scheduler, doubled::get);
            assertEquals(4, doubled.get());
            assertEquals(1, computed[0]);

            count.set(5);
            scheduler.flush();
            assertEquals(List.of(4, 10), hole.values);
            assertEquals(2, computed[0]);
        }

        @Test
        void anEffectRunsAfterTheHolesOfTheSameUpdate() {
            State<Integer> count = Reactive.state(0);
            List<String> log = new ArrayList<>();
            Computation owner = new TestHole(scheduler, () -> null);
            Computation.runWithOwner(owner, () -> Reactive.effect(() -> log.add("effect " + count.get())));
            new TestHole(scheduler, () -> log.add("hole " + count.get()));

            count.set(1);
            scheduler.flush();
            assertEquals(List.of("effect 0", "hole 0", "hole 1", "effect 1"), log);
        }

        @Test
        void whatAHoleCreatedIsDisposedWhenItReRuns() {
            State<Integer> outer = Reactive.state(0);
            State<Integer> inner = Reactive.state(0);
            List<TestHole> created = new ArrayList<>();
            List<String> cleanups = new ArrayList<>();
            new TestHole(scheduler, () -> {
                int run = outer.get();
                TestHole nested = new TestHole(scheduler, inner::get);
                nested.onCleanup(() -> cleanups.add("run " + run));
                created.add(nested);
                return run;
            });

            outer.set(1);
            scheduler.flush();
            assertTrue(created.get(0).isDisposed());
            assertEquals(List.of("run 0"), cleanups);

            inner.set(1);
            scheduler.flush();
            assertEquals(List.of(0), created.get(0).values, "the disposed hole no longer updates");
            assertEquals(List.of(0, 1), created.get(1).values);
        }

        @Test
        void untrackedReadsSubscribeNothing() {
            State<Integer> count = Reactive.state(0);
            TestHole hole = new TestHole(scheduler, () -> Reactive.untracked(count::get) + count.peek());
            count.set(1);
            assertTrue(scheduler.waiting.isEmpty());
            assertEquals(List.of(0), hole.values);
        }

        @Test
        void aRunThatThrowsKeepsThePreviousRunAndRetriesOnTheNextChange() {
            State<Integer> n = Reactive.state(0);
            List<TestHole> children = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            Computation hole = new Computation(scheduler) {
                @Override
                public void update() {
                    values.add(retrack(() -> {
                        children.add(new TestHole(scheduler, () -> "child"));
                        if (n.get() == 1) {
                            throw new IllegalStateException("boom (expected by the test)");
                        }
                        return n.get();
                    }));
                }
            };
            hole.update();

            n.set(1);
            assertThrows(IllegalStateException.class, scheduler::flush);
            scheduler.waiting.clear();
            assertFalse(children.get(0).isDisposed(), "the previous run's child is kept");
            assertTrue(children.get(1).isDisposed(), "the failed run's child is undone");

            n.set(2); // still subscribed: it retries
            scheduler.flush();
            assertEquals(List.of(0, 2), values);
            assertTrue(children.get(0).isDisposed(), "dropped once a run succeeded");
            assertFalse(children.get(2).isDisposed());
        }

        @Test
        void concurrentUpdatesOfASharedStateLoseNoChange() throws Exception {
            State<Integer> count = Reactive.state(0);
            int threads = 8;
            int updatesEach = 10_000;
            List<Thread> running = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                running.add(Thread.ofPlatform().start(() -> {
                    for (int j = 0; j < updatesEach; j++) {
                        count.update(n -> n + 1);
                    }
                }));
            }
            for (Thread thread : running) {
                thread.join();
            }
            assertEquals(threads * updatesEach, count.peek());
        }

        @Test
        void withoutASchedulerAnEffectReRunsAtOnce() {
            State<Integer> count = Reactive.state(0);
            List<Integer> seen = new ArrayList<>();
            Reactive.effect(() -> seen.add(count.get()));
            count.set(4);
            assertEquals(List.of(0, 4), seen);
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** A submitted form, as a target would deliver it. */
    record TestForm(Map<String, List<String>> values) implements FormEvent {
        static TestForm of(String... pairs) {
            Map<String, List<String>> values = new LinkedHashMap<>();
            for (int i = 0; i < pairs.length; i += 2) {
                values.computeIfAbsent(pairs[i], k -> new ArrayList<>()).add(pairs[i + 1]);
            }
            return new TestForm(values);
        }

        @Override
        public String type() {
            return "submit";
        }

        @Override
        public String field(String name) {
            List<String> all = fields(name);
            return all.isEmpty() ? "" : all.get(0);
        }

        @Override
        public List<String> fields(String name) {
            return values.getOrDefault(name, List.of());
        }

        @Override
        public Set<String> fieldNames() {
            return values.keySet();
        }

        @Override
        public void resetForm() {}
    }

    enum Priority { LOW, HIGH }

    record NewTodo(String title, int count, Integer limit, boolean urgent, Priority priority, LocalDate due,
                   BigDecimal price, List<String> tags, List<Integer> ids) {}

    record Positive(int value) {
        Positive {
            if (value <= 0) {
                throw new IllegalArgumentException("value must be positive");
            }
        }
    }

    @Nested
    class FormBindingToRecords {
        @Test
        void eachComponentIsReadFromTheFieldOfTheSameName() {
            NewTodo todo = TestForm.of("title", "Milk", "count", " 3 ", "urgent", "on", "priority", "HIGH",
                    "due", "2026-11-01", "price", "2.50", "tags", "a", "tags", "b", "ids", "1", "ids", "2").as(NewTodo.class);
            assertEquals(new NewTodo("Milk", 3, null, true, Priority.HIGH, LocalDate.of(2026, 11, 1),
                    new BigDecimal("2.50"), List.of("a", "b"), List.of(1, 2)), todo);
        }

        @Test
        void missingFieldsAreEmptyNullFalseOrAnEmptyList() {
            NewTodo todo = TestForm.of("count", "0").as(NewTodo.class);
            assertEquals("", todo.title());
            assertNull(todo.limit());
            assertFalse(todo.urgent());
            assertNull(todo.priority());
            assertEquals(List.of(), todo.tags());
        }

        @Test
        void aValueThatCantBeConvertedNamesItsField() {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> TestForm.of("count", "three").as(NewTodo.class));
            assertTrue(error.getMessage().contains("\"count\""), error.getMessage());
            var empty = assertThrows(IllegalArgumentException.class, () -> TestForm.of().as(NewTodo.class));
            assertTrue(empty.getMessage().contains("\"count\" is empty"), empty.getMessage());
        }

        @Test
        void theRecordsOwnValidationIsLetThrough() {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> TestForm.of("value", "-1").as(Positive.class));
            assertEquals("value must be positive", error.getMessage());
        }
    }
}
