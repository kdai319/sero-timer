package com.serotonin.timer;

import java.util.concurrent.ExecutorService;

class TimerThread extends Thread {
    /**
     * This flag is set to false by the reaper to inform us that there are no more live references to our Timer object.
     * Once this flag is true and there are no more tasks in our queue, there is no work left for us to do, so we
     * terminate gracefully. Note that this field is protected by queue's monitor!
     */
    boolean newTasksMayBeScheduled = true;

    /**
     * Our Timer's queue. We store this reference in preference to a reference to the Timer so the reference graph
     * remains acyclic. Otherwise, the Timer would never be garbage-collected and this thread would never go away.
     */
    private final TaskQueue queue;

    private final ExecutorService executorService;

    TimerThread(TaskQueue queue, ExecutorService executorService) {
        this.queue = queue;
        this.executorService = executorService;
    }

    @Override
    public void run() {
        try {
            mainLoop();
        }
        finally {
            // Someone killed this Thread, behave as if Timer was cancelled
            synchronized (queue) {
                newTasksMayBeScheduled = false;
                queue.clear(); // Eliminate obsolete references
            }
        }
    }

    void execute(Runnable command) {
        executorService.execute(command);
    }

    void execute(final ScheduledRunnable command, final long fireTime) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                command.run(fireTime);
            }
        });
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * The main timer loop. (See class comment.)
     */
    private void mainLoop() {
        while (true) {
            try {
                TimerTask task;
                boolean taskFired;
                synchronized (queue) {
                    // Wait for queue to become non-empty
                    while (queue.isEmpty() && newTasksMayBeScheduled)
                        queue.wait();
                    if (queue.isEmpty())
                        break; // Queue is empty and will forever remain; die

                    // Queue nonempty; look at first evt and do the right thing
                    long executionTime;
                    task = queue.getMin();
                    synchronized (task.lock) {
                        if (task.state == TimerTask.CANCELLED) {
                            queue.removeMin();
                            continue; // No action required, poll queue again
                        }
                        executionTime = task.trigger.nextExecutionTime;
                        if (taskFired = (executionTime <= System.currentTimeMillis())) {
                            long next = task.trigger.calculateNextExecutionTime();
                            if (next <= 0) { // Non-repeating, remove
                                queue.removeMin();
                                task.state = TimerTask.EXECUTED;
                            }
                            else
                                // Repeating task, reschedule
                                queue.rescheduleMin(next);
                        }
                    }
                    if (!taskFired) {// Task hasn't yet fired; wait
                        long wait = executionTime - System.currentTimeMillis();
                        if (wait > 0)
                            queue.wait(wait);
                    }
                }
                if (taskFired) // Task fired; run it, holding no locks
                    executorService.execute(task);
            }
            catch (InterruptedException e) {
                // no op
            }
        }
    }
}