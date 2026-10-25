package com.cryonum.captusbprint.office;

import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic race schedules, not sleeps or a fake Office renderer. */
public final class LatestPreparationCheck {
    enum Error { FAILED, CANCELLED }
    static final class Serial extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>(); boolean stopped;
        @Override public void execute(Runnable r) { if (stopped) throw new AssertionError("AFTER_SHUTDOWN"); tasks.add(r); }
        void one() { tasks.remove().run(); }
        void all() { while (!tasks.isEmpty()) one(); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { throw new AssertionError("UNSAFE_SHUTDOWN"); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long n, TimeUnit u) { return isTerminated(); }
    }
    static final class Owned implements Closeable {
        final int id; int closes; Owned(int id) { this.id = id; }
        @Override public void close() { if (++closes != 1) throw new AssertionError("DOUBLE_CLOSE"); }
    }
    private static int checks;
    private static void check(boolean b) { if (!b) throw new AssertionError("CHECK_"+checks); checks++; }
    private static void scenario(int mode) {
        Serial worker = new Serial(), ui = new Serial();
        List<LatestPreparation.State<Integer,Error>> states = new ArrayList<>();
        List<Owned> values = new ArrayList<>(); AtomicInteger cleanup = new AtomicInteger();
        @SuppressWarnings("unchecked")
        LatestPreparation<Integer,Owned,Integer,Error>[] holder = (LatestPreparation<Integer,Owned,Integer,Error>[])new LatestPreparation<?,?,?,?>[1];
        LatestPreparation<Integer,Owned,Integer,Error> model = new LatestPreparation<>(worker,ui,
            (option,cancel) -> {
                if (mode == 2 && option == 1) { holder[0].replace(2); check(cancel.getAsBoolean()); }
                if (mode == 3 && option == 1) { holder[0].close(); check(cancel.getAsBoolean()); }
                if (mode == 4 && option == 1) throw new IllegalStateException("PRIVATE_SENTINEL");
                Owned value = new Owned(option); values.add(value); return value;
            }, value -> value.id, error -> Error.FAILED, states::add, cleanup::incrementAndGet);
        holder[0] = model;
        long first = model.replace(1);
        if (mode == 0) { worker.all(); model.replace(2); worker.all(); ui.all();
            check(states.size()==2 && states.get(0).phase==LatestPreparation.Phase.LOADING && states.get(1).metadata==2);
            check(values.get(0).closes==1 && values.get(1).closes==0);
        } else if (mode == 1) { model.cancel(); worker.all(); ui.all(); check(values.isEmpty());
            check(states.size()==1 && states.get(0).phase==LatestPreparation.Phase.CANCELLED);
        } else if (mode == 2) { worker.all(); ui.all(); check(values.size()==2 && values.get(0).closes==1);
            check(states.get(states.size()-1).metadata==2);
        } else if (mode == 3) { worker.all(); ui.all(); check(states.isEmpty()); check(values.get(0).closes==1); }
        else if (mode == 4) { worker.all(); ui.all(); check(states.get(states.size()-1).failure==Error.FAILED);
            model.replace(2); worker.all(); ui.all(); check(states.get(states.size()-1).metadata==2);
        } else if (mode == 5) {
            worker.all(); ui.all(); AtomicInteger delivered = new AtomicInteger(), dropped = new AtomicInteger();
            check(model.read(first,(doc,cancel)->doc.id,delivered::set,error->{throw new AssertionError();},dropped::set));
            worker.all(); model.replace(2); worker.all(); ui.all(); check(delivered.get()==0 && dropped.get()==1);
            check(!model.read(first,(doc,cancel)->doc.id,delivered::set,error->{},dropped::set));
        } else if (mode == 6) {
            worker.all(); ui.all(); Owned old=values.get(0); AtomicInteger reads = new AtomicInteger();
            model.read(first,(doc,cancel)->{check(doc.closes==0);reads.incrementAndGet();return doc.id;},v->{},e->{},v->{});
            model.close(); check(old.closes==0 && cleanup.get()==0); worker.all(); ui.all();
            check(reads.get()==0 && old.closes==1 && cleanup.get()==1);
        } else if (mode == 7) {
            worker.all(); ui.all(); model.read(first,(doc,cancel)->{model.replace(2);check(cancel.getAsBoolean());return 7;},
                v->{throw new AssertionError("STALE_READ");},e->{},v->check(v==7));
            worker.all(); ui.all(); check(values.get(0).closes==1 && states.get(states.size()-1).metadata==2);
        }
        model.close(); model.close(); worker.all(); ui.all();
        check(cleanup.get()==1 && worker.isTerminated() && !model.cleanupFailed());
        check(values.stream().allMatch(v->v.closes==1));
    }
    public static void main(String[] args) {
        for (int mode=0;mode<8;mode++) scenario(mode);
        System.out.println("CAPT_LATEST_PREPARATION_PASSED checks="+checks+" (ownership/replacement/late delivery/close)");
    }
}
