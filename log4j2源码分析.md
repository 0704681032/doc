





```java
//递归调用自己 ConfigurationScheduler 
public class CronRunnable implements Runnable {

        private final CronExpression cronExpression;
        private final Runnable runnable;
        private CronScheduledFuture<?> scheduledFuture;

        public CronRunnable(final Runnable runnable, final CronExpression cronExpression) {
            this.cronExpression = cronExpression;
            this.runnable = runnable;
        }

        public void setScheduledFuture(final CronScheduledFuture<?> future) {
            this.scheduledFuture = future;
        }

        @Override
        public void run() {
            try {
                final long millis = scheduledFuture.getFireTime().getTime() - System.currentTimeMillis();
                if (millis > 0) {
                    LOGGER.debug("{} Cron thread woke up {} millis early. Sleeping", name, millis);
                    try {
                        Thread.sleep(millis);
                    } catch (final InterruptedException ie) {
                        // Ignore the interruption.
                    }
                }
                runnable.run();
            } catch(final Throwable ex) {
                LOGGER.error("{} caught error running command", name, ex);
            } finally {
                final Date fireDate = cronExpression.getNextValidTimeAfter(new Date());
                final ScheduledFuture<?> future = schedule(this, nextFireInterval(fireDate), TimeUnit.MILLISECONDS);
                LOGGER.debug("{} Cron expression {} scheduled to fire again at {}", name, cronExpression.getCronExpression(),
                        fireDate);
                scheduledFuture.reset(future, fireDate);
            }
        }

        @Override
        public String toString() {
            return "CronRunnable{" + cronExpression.getCronExpression() + " - " + scheduledFuture.getFireTime();
        }
    }
```





```java
//CompositeAction
public boolean execute() throws IOException {
        if (stopOnError) {
            for (final Action action : actions) {
                if (!action.execute()) {
                    return false;
                }
            }

            return true;
        }
        boolean status = true;
        IOException exception = null;

        for (final Action action : actions) {
            try {
                status &= action.execute();
            } catch (final IOException ex) {
                status = false;

                if (exception == null) {
                    exception = ex;
                }
            }
        }

        if (exception != null) {
            throw exception;
        }

        return status;
    }

```



```java
//log4j2大量运用composite模式	
private CompositeFilter(final Filter[] filters) {
        this.filters = filters == null ? EMPTY_FILTERS : filters;
    }

    public CompositeFilter addFilter(final Filter filter) {
        if (filter == null) {
            // null does nothing
            return this;
        }
        if (filter instanceof CompositeFilter) {
            final int size = this.filters.length + ((CompositeFilter) filter).size();
            final Filter[] copy = Arrays.copyOf(this.filters, size);
            final int index = this.filters.length;
            for (final Filter currentFilter : ((CompositeFilter) filter).filters) {
                copy[index] = currentFilter;
            }
            return new CompositeFilter(copy);
        }
        final Filter[] copy = Arrays.copyOf(this.filters, this.filters.length + 1);
        copy[this.filters.length] = filter;
        return new CompositeFilter(copy);
    }
 @Override
    public Result filter(final Logger logger, final Level level, final Marker marker, final String msg,
            final Object... params) {
        Result result = Result.NEUTRAL;
        for (int i = 0; i < filters.length; i++) {
            result = filters[i].filter(logger, level, marker, msg, params);
            if (result == Result.ACCEPT || result == Result.DENY) {
                return result;
            }
        }
        return result;
    }
```

