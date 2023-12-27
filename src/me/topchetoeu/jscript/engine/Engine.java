package me.topchetoeu.jscript.engine;

import java.util.HashMap;
import java.util.concurrent.PriorityBlockingQueue;

import me.topchetoeu.jscript.Filename;
import me.topchetoeu.jscript.compilation.FunctionBody;
import me.topchetoeu.jscript.engine.values.FunctionValue;
import me.topchetoeu.jscript.events.Awaitable;
import me.topchetoeu.jscript.events.DataNotifier;
import me.topchetoeu.jscript.exceptions.InterruptException;

public class Engine {
    private class UncompiledFunction extends FunctionValue {
        public final Filename filename;
        public final String raw;
        private FunctionValue compiled = null;

        @Override
        public Object call(Context ctx, Object thisArg, Object ...args) {
            if (compiled == null) compiled = ctx.compile(filename, raw);
            return compiled.call(ctx, thisArg, args);
        }

        public UncompiledFunction(Filename filename, String raw) {
            super(filename + "", 0);
            this.filename = filename;
            this.raw = raw;
        }
    }

    private static class Task implements Comparable<Task> {
        public final FunctionValue func;
        public final Object thisArg;
        public final Object[] args;
        public final DataNotifier<Object> notifier = new DataNotifier<>();
        public final Context ctx;
        public final boolean micro;

        public Task(Context ctx, FunctionValue func, Object thisArg, Object[] args, boolean micro) {
            this.ctx = ctx;
            this.func = func;
            this.thisArg = thisArg;
            this.args = args;
            this.micro = micro;
        }

        @Override
        public int compareTo(Task other) {
            return Integer.compare(this.micro ? 0 : 1, other.micro ? 0 : 1);
        }
    }

    private static int nextId = 0;
    public static final HashMap<Long, FunctionBody> functions = new HashMap<>();

    public final Environment globalEnvironment = new Environment();

    public final int id = ++nextId;
    public final boolean debugging;
    public int maxStackFrames = 10000;

    private Thread thread;
    private PriorityBlockingQueue<Task> tasks = new PriorityBlockingQueue<>();

    private void runTask(Task task) {
        try {
            task.notifier.next(task.func.call(task.ctx, task.thisArg, task.args));
        }
        catch (RuntimeException e) {
            if (e instanceof InterruptException) throw e;
            task.notifier.error(e);
        }
    }
    public void run(boolean untilEmpty) {
        while (!untilEmpty || !tasks.isEmpty()) {
            try {
                runTask(tasks.take());
            }
            catch (InterruptedException | InterruptException e) {
                for (var msg : tasks) msg.notifier.error(new InterruptException(e));
                break;
            }
        }
    }

    public Thread start() {
        if (this.thread == null) {
            this.thread = new Thread(() -> run(false), "JavaScript Runner #" + id);
            this.thread.start();
        }
        return this.thread;
    }
    public void stop() {
        thread.interrupt();
        thread = null;
    }
    public boolean inExecThread() {
        return Thread.currentThread() == thread;
    }
    public synchronized boolean isRunning() {
        return this.thread != null;
    }

    public Awaitable<Object> pushMsg(boolean micro, Environment env, FunctionValue func, Object thisArg, Object ...args) {
        var msg = new Task(new Context(this, env), func, thisArg, args, micro);
        tasks.add(msg);
        return msg.notifier;
    }
    public Awaitable<Object> pushMsg(boolean micro, Environment env, Filename filename, String raw, Object thisArg, Object ...args) {
        return pushMsg(micro, env, new UncompiledFunction(filename, raw), thisArg, args);
    }

    public Engine(boolean debugging) {
        this.debugging = debugging;
    }
}
