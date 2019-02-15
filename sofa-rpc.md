sofa-rpc



```java
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";

    private final Random random = new Random();

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        int length = invokers.size(); // Number of invokers
        int totalWeight = 0; // The sum of weights
        boolean sameWeight = true; // Every invoker has the same weight?
        for (int i = 0; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);
            totalWeight += weight; // Sum
            if (sameWeight && i > 0
                    && weight != getWeight(invokers.get(i - 1), invocation)) {
                sameWeight = false;
            }
        }
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            int offset = random.nextInt(totalWeight);
            // Return a invoker based on the random value.
            for (int i = 0; i < length; i++) {
                offset -= getWeight(invokers.get(i), invocation);
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(random.nextInt(length));
    }

}
```



```java
 
@Override
    public ProviderInfo doSelect(SofaRequest invocation, List<ProviderInfo> providerInfos) {
        ProviderInfo providerInfo = null;
        int size = providerInfos.size(); // 总个数
        int totalWeight = 0; // 总权重
        boolean isWeightSame = true; // 权重是否都一样
        for (int i = 0; i < size; i++) {
            int weight = getWeight(providerInfos.get(i));
            totalWeight += weight; // 累计总权重
            if (isWeightSame && i > 0 && weight != getWeight(providerInfos.get(i - 1))) {
                isWeightSame = false; // 计算所有权重是否一样
            }
        }
        if (totalWeight > 0 && !isWeightSame) {
            // 如果权重不相同且权重大于0则按总权重数随机
            int offset = random.nextInt(totalWeight);
            // 并确定随机值落在哪个片断上
            for (int i = 0; i < size; i++) {
                offset -= getWeight(providerInfos.get(i));
                if (offset < 0) {
                    providerInfo = providerInfos.get(i);
                    break;
                }
            }
        } else {
            // 如果权重相同或权重为0则均等随机
            providerInfo = providerInfos.get(random.nextInt(size));
        }
        return providerInfo;
    }
```

```java
	//dubbo的最新版本的优化如下
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        int length = invokers.size();
        // Every invoker has the same weight?
        boolean sameWeight = true;
        // the weight of every invokers
        int[] weights = new int[length];
        // the first invoker's weight
        int firstWeight = getWeight(invokers.get(0), invocation);
        weights[0] = firstWeight;
        // The sum of weights
        int totalWeight = firstWeight;
        for (int i = 1; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);
            // save for later use
            weights[i] = weight;
            // Sum
            totalWeight += weight;
            if (sameWeight && weight != firstWeight) {
                sameWeight = false;
            }
        }
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            int offset = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            for (int i = 0; i < length; i++) {
                offset -= weights[i];
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }
```



```java
/**
 * 异步执行运行时
 *
 * @author <a href=mailto:zhanggeng.zg@antfin.com>GengZhang</a>
 */
public class AsyncRuntime {

    /**
     * callback业务线程池（callback+async）
     */
    private static volatile ThreadPoolExecutor asyncThreadPool;

    /**
     * 得到callback用的线程池 默认开始创建
     *
     * @return callback用的线程池
     */
    public static ThreadPoolExecutor getAsyncThreadPool() {
        return getAsyncThreadPool(true);
    }

    /**
     * 得到callback用的线程池
     *
     * @param build 没有时是否构建
     * @return callback用的线程池
     */
    public static ThreadPoolExecutor getAsyncThreadPool(boolean build) {
        if (asyncThreadPool == null && build) {
            synchronized (AsyncRuntime.class) {
                if (asyncThreadPool == null && build) {
                    // 一些系统参数，可以从配置或者注册中心获取。
                    int coresize = RpcConfigs.getIntValue(RpcOptions.ASYNC_POOL_CORE);
                    int maxsize = RpcConfigs.getIntValue(RpcOptions.ASYNC_POOL_MAX);
                    int queuesize = RpcConfigs.getIntValue(RpcOptions.ASYNC_POOL_QUEUE);
                    int keepAliveTime = RpcConfigs.getIntValue(RpcOptions.ASYNC_POOL_TIME);

                    BlockingQueue<Runnable> queue = ThreadPoolUtils.buildQueue(queuesize);
                    NamedThreadFactory threadFactory = new NamedThreadFactory("RPC-CB", true);

                    RejectedExecutionHandler handler = new RejectedExecutionHandler() {
                        private int i = 1;

                        @Override
                        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                            if (i++ % 7 == 0) {
                                i = 1;
                                if (LOGGER.isWarnEnabled()) {
                                    LOGGER.warn("Task:{} has been reject because of threadPool exhausted!" +
                                        " pool:{}, active:{}, queue:{}, taskcnt: {}", r,
                                        executor.getPoolSize(),
                                        executor.getActiveCount(),
                                        executor.getQueue().size(),
                                        executor.getTaskCount());
                                }
                            }
                            throw new RejectedExecutionException("Callback handler thread pool has bean exhausted");
                        }
                    };
                    asyncThreadPool = ThreadPoolUtils.newCachedThreadPool(
                        coresize, maxsize, keepAliveTime, queue, threadFactory, handler);
                }
            }
        }
        return asyncThreadPool;
    }
}

```



```java
/**
 * Common NamedThreadFactory
 *
 * @author <a href=mailto:zhanggeng.zg@antfin.com>GengZhang</a>
 */
public class NamedThreadFactory implements ThreadFactory {

    /**
     * 系统全局线程池计数器
     */
    private static final AtomicInteger POOL_COUNT  = new AtomicInteger();

    /**
     * 当前线程池计数器
     */
    final AtomicInteger                threadCount = new AtomicInteger(1);
    /**
     * 构造函数
     *
     * @param secondPrefix 第二前缀，前面会自动加上第一前缀，后面会自动加上-T-
     * @param daemon 是否守护线程，true的话随主线程退出而退出，false的话则要主动退出
     */
    public NamedThreadFactory(String secondPrefix, boolean daemon) {
        SecurityManager s = System.getSecurityManager();
        group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
        namePrefix = firstPrefix + secondPrefix + "-" + POOL_COUNT.getAndIncrement() + "-T";
        isDaemon = daemon;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(group, r, namePrefix + threadCount.getAndIncrement(), 0);
        t.setDaemon(isDaemon);
        if (t.getPriority() != Thread.NORM_PRIORITY) {
            t.setPriority(Thread.NORM_PRIORITY);
        }
        return t;
    }
```

