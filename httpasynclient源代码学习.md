## httpasynclient源代码学习

核心类如下

* IOReactor
  * AbstractMultiworkerIOReactor 
  *  BaseIOReactor
  * DefaultConnectingIOReactor
*  HttpAsyncRequestExecutor
*  DefaultNHttpClientConnection
* DefaultClientExchangeHandlerImpl













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

