##   httpasynclient源代码学习

核心类如下

* IOReactor
  * AbstractMultiworkerIOReactor 
  *  BaseIOReactor
  * DefaultConnectingIOReactor
*  HttpAsyncRequestExecutor
*  DefaultNHttpClientConnection
* DefaultClientExchangeHandlerImpl

* IOSessionImpl
* AbstractNIOConnPool
* 处处皆异步
  *  SessionRequestImpl -> SessionRequestCallback
  *   LeaseRequest











```java
public CloseableHttpAsyncClientBase(
            final NHttpClientConnectionManager connmgr,
            final ThreadFactory threadFactory,
            final NHttpClientEventHandler handler) {
        super();
        this.connmgr = connmgr;
        if (threadFactory != null && handler != null) {
            this.reactorThread = threadFactory.newThread(new Runnable() {

                @Override
                public void run() {
                    try {
                        final IOEventDispatch ioEventDispatch = new InternalIODispatch(handler);
                        connmgr.execute(ioEventDispatch);
                    } catch (final Exception ex) {
                        log.error("I/O reactor terminated abnormally", ex);
                    } finally {
                        status.set(Status.STOPPED);
                    }
                }

            });
        } else {
            this.reactorThread = null;
        }
        this.status = new AtomicReference<Status>(Status.INACTIVE);
    }
```





```java
//AbstractIOReactor
protected void processEvent(final SelectionKey key) {
        final IOSessionImpl session = (IOSessionImpl) key.attachment();
        try {
            if (key.isAcceptable()) {
                acceptable(key);
            }
            if (key.isConnectable()) {
                connectable(key);
            }
            if (key.isReadable()) {
                session.resetLastRead();
                readable(key);
            }
            if (key.isWritable()) {
                session.resetLastWrite();
                writable(key);
            }
        } catch (final CancelledKeyException ex) {
            queueClosedSession(session);
            key.attach(null);
        }
    }

//BaseIOReactor
 @Override
    protected void readable(final SelectionKey key) {
        final IOSession session = getSession(key);
        try {
            // Try to gently feed more data to the event dispatcher
            // if the session input buffer has not been fully exhausted
            // (the choice of 5 iterations is purely arbitrary)
            for (int i = 0; i < 5; i++) {
                this.eventDispatch.inputReady(session);
                if (!session.hasBufferedInput()
                        || (session.getEventMask() & SelectionKey.OP_READ) == 0) {
                    break;
                }
            }
            if (session.hasBufferedInput()) {
                this.bufferingSessions.add(session);
            }
        } catch (final CancelledKeyException ex) {
            queueClosedSession(session);
            key.attach(null);
        } catch (final RuntimeException ex) {
            handleRuntimeException(ex);
        }
    }

//DefaultNHttpClientConnection
 public void consumeInput(final NHttpClientEventHandler handler) {
        if (this.status != ACTIVE) {
            this.session.clearEvent(EventMask.READ);
            return;
        }
        try {
            if (this.response == null) {
                int bytesRead;
                do {
                    bytesRead = this.responseParser.fillBuffer(this.session.channel());
                    if (bytesRead > 0) {
                        this.inTransportMetrics.incrementBytesTransferred(bytesRead);
                    }
                    this.response = this.responseParser.parse();
                } while (bytesRead > 0 && this.response == null);
                if (this.response != null) {
                    if (this.response.getStatusLine().getStatusCode() >= 200) {
                        final HttpEntity entity = prepareDecoder(this.response);
                        this.response.setEntity(entity);
                        this.connMetrics.incrementResponseCount();
                    }
                    this.hasBufferedInput = this.inbuf.hasData();
                    onResponseReceived(this.response);
                    handler.responseReceived(this);
                    if (this.contentDecoder == null) {
                        resetInput();
                    }
                }
                if (bytesRead == -1 && !this.inbuf.hasData()) {
                    handler.endOfInput(this);
                }
            }
            if (this.contentDecoder != null && (this.session.getEventMask() & SelectionKey.OP_READ) > 0) {
                handler.inputReady(this, this.contentDecoder);
                if (this.contentDecoder.isCompleted()) {
                    // Response entity received
                    // Ready to receive a new response
                    resetInput();
                }
            }
        } catch (final HttpException ex) {
            resetInput();
            handler.exception(this, ex);
        } catch (final Exception ex) {
            handler.exception(this, ex);
        } finally {
            // Finally set buffered input flag
            this.hasBufferedInput = this.inbuf.hasData();
        }
    }

```



```java
//AbstractClientExchangeHandler 

private void connectionAllocated(final NHttpClientConnection managedConn) {
        try {
            if (this.log.isDebugEnabled()) {
                this.log.debug("[exchange: " + this.id + "] Connection allocated: " + managedConn);
            }
            this.connectionFutureRef.set(null);
            this.managedConnRef.set(managedConn);

            if (this.closed.get()) {
                discardConnection();
                return;
            }

            if (this.connmgr.isRouteComplete(managedConn)) {
                this.routeEstablished.set(true);
                this.routeTrackerRef.set(null);
            }

            final HttpContext context = managedConn.getContext();
            synchronized (context) {
                context.setAttribute(HttpAsyncRequestExecutor.HTTP_HANDLER, this);
              //这里是非常关键的一步 把handler设置到conn的context上下文
                if (managedConn.isStale()) {
                    failed(new ConnectionClosedException("Connection closed"));
                } else {
                    managedConn.requestOutput();
                }
            }
        } catch (final RuntimeException runex) {
            failed(runex);
            throw runex;
        }
    }
```



```java
//NHttpConnectionBase 
@Override
    public void requestInput() {
        this.session.setEvent(EventMask.READ);
    }

    @Override
    public void requestOutput() {
        this.session.setEvent(EventMask.WRITE);
    }

    @Override
    public void suspendInput() {
        this.session.clearEvent(EventMask.READ);
    }

    @Override
    public void suspendOutput() {
        synchronized (this.session) {
            if (!this.outbuf.hasData()) {
                this.session.clearEvent(EventMask.WRITE);
            }
        }
    }
```



```java

//AbstractClientExchangeHandler
final void requestConnection() {
        final HttpRoute route = this.routeRef.get();
        if (this.log.isDebugEnabled()) {
            this.log.debug("[exchange: " + this.id + "] Request connection for " + route);
        }

        discardConnection();

        this.validDurationRef.set(null);
        this.routeTrackerRef.set(null);
        this.routeEstablished.set(false);

        final Object userToken = this.localContext.getUserToken();
        final RequestConfig config = this.localContext.getRequestConfig();
        this.connectionFutureRef.set(this.connmgr.requestConnection(
                route,
                userToken,
                config.getConnectTimeout(),
                config.getConnectionRequestTimeout(),
                TimeUnit.MILLISECONDS,
          		//注意这里的回调
                new FutureCallback<NHttpClientConnection>() {

                    @Override
                    public void completed(final NHttpClientConnection managedConn) {
                        connectionAllocated(managedConn);//注意这里
                    }

                    @Override
                    public void failed(final Exception ex) {
                        connectionRequestFailed(ex);
                    }

                    @Override
                    public void cancelled() {
                        connectionRequestCancelled();
                    }

                }));
    }
```



```java
//同样关注这里的回调 
@Override
    public Future<NHttpClientConnection> requestConnection(
            final HttpRoute route,
            final Object state,
            final long connectTimeout,
            final long leaseTimeout,
            final TimeUnit tunit,
            final FutureCallback<NHttpClientConnection> callback) {
        Args.notNull(route, "HTTP route");
        if (this.log.isDebugEnabled()) {
            this.log.debug("Connection request: " + format(route, state) + formatStats(route));
        }
        final BasicFuture<NHttpClientConnection> resultFuture = new BasicFuture<NHttpClientConnection>(callback);
        final HttpHost host;
        if (route.getProxyHost() != null) {
            host = route.getProxyHost();
        } else {
            host = route.getTargetHost();
        }
        final SchemeIOSessionStrategy sf = this.iosessionFactoryRegistry.lookup(
                host.getSchemeName());
        if (sf == null) {
            resultFuture.failed(new UnsupportedSchemeException(host.getSchemeName() +
                    " protocol is not supported"));
            return resultFuture;
        }
        final Future<CPoolEntry> leaseFuture = this.pool.lease(route, state,
                connectTimeout, leaseTimeout, tunit != null ? tunit : TimeUnit.MILLISECONDS,
                new FutureCallback<CPoolEntry>() {

                    @Override
                    public void completed(final CPoolEntry entry) {
                        Asserts.check(entry.getConnection() != null, "Pool entry with no connection");
                        if (log.isDebugEnabled()) {
                            log.debug("Connection leased: " + format(entry) + formatStats(entry.getRoute()));
                        }
                        final NHttpClientConnection managedConn = CPoolProxy.newProxy(entry);
                        if (!resultFuture.completed(managedConn)) {
                            pool.release(entry, true);
                        }
                    }

                    @Override
                    public void failed(final Exception ex) {
                        if (log.isDebugEnabled()) {
                            log.debug("Connection request failed", ex);
                        }
                        resultFuture.failed(ex);
                    }

                    @Override
                    public void cancelled() {
                        log.debug("Connection request cancelled");
                        resultFuture.cancel(true);
                    }

                });
        return new Future<NHttpClientConnection>() {

            @Override
            public boolean cancel(final boolean mayInterruptIfRunning) {
                try {
                    leaseFuture.cancel(mayInterruptIfRunning);
                } finally {
                    return resultFuture.cancel(mayInterruptIfRunning);
                }
            }

            @Override
            public boolean isCancelled() {
                return resultFuture.isCancelled();
            }

            @Override
            public boolean isDone() {
                return resultFuture.isDone();
            }

            @Override
            public NHttpClientConnection get() throws InterruptedException, ExecutionException {
                return resultFuture.get();
            }

            @Override
            public NHttpClientConnection get(final long timeout, final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                return resultFuture.get(timeout, unit);
            }

        };
    }

```



```java
//AbstractNIOConnPool 
/**
     * @since 4.3
     */
    public Future<E> lease(
            final T route, final Object state,
            final long connectTimeout, final long leaseTimeout, final TimeUnit timeUnit,
            final FutureCallback<E> callback) {
        Args.notNull(route, "Route");
        Args.notNull(timeUnit, "Time unit");
        Asserts.check(!this.isShutDown.get(), "Connection pool shut down");
        final BasicFuture<E> future = new BasicFuture<E>(callback);
        final LeaseRequest<T, C, E> leaseRequest = new LeaseRequest<T, C, E>(route, state,
                connectTimeout >= 0 ? timeUnit.toMillis(connectTimeout) : -1,
                leaseTimeout > 0 ? timeUnit.toMillis(leaseTimeout) : 0,
                future);
        this.lock.lock();
        try {
            final boolean completed = processPendingRequest(leaseRequest);
            if (!leaseRequest.isDone() && !completed) {
                this.leasingRequests.add(leaseRequest);
            }
            if (leaseRequest.isDone()) {
                this.completedRequests.add(leaseRequest);
            }
        } finally {
            this.lock.unlock();
        }
        fireCallbacks();
        return new Future<E>() {

            @Override
            public E get() throws InterruptedException, ExecutionException {
                return future.get();
            }

            @Override
            public E get(
                    final long timeout,
                    final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                return future.get(timeout, unit);
            }

            @Override
            public boolean cancel(final boolean mayInterruptIfRunning) {
                try {
                    leaseRequest.cancel();
                } finally {
                    return future.cancel(mayInterruptIfRunning);
                }
            }

            @Override
            public boolean isCancelled() {
                return future.isCancelled();
            }

            @Override
            public boolean isDone() {
                return future.isDone();
            }

        };
    }
```

