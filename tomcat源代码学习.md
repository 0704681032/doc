## tomcat源代码学习



*  maxKeepAliveRequests=100; // as in Apache HTTPD server 一个长连接最多发起多少次请求就关闭,这个参数很容易让人误解

  ```java
  //Http11Processor
  int maxKeepAliveRequests = protocol.getMaxKeepAliveRequests();
  if (maxKeepAliveRequests == 1) {
      keepAlive = false;
  } else if (maxKeepAliveRequests > 0 &&
          socketWrapper.decrementKeepAlive() <= 0) {//注意这里是调用
          //socketWrapper,每个socket独立
      keepAlive = false;
  }
  ```

* acceptCount 其实是系统层面的backlog，又是一个不好的名字

  ```java
  serverSock.socket().bind(addr,getAcceptCount());
  //继续调用下面的
  bind(SocketAddress endpoint, int backlog)
  ```

* maxConnections

  ```java
  private int maxConnections = 10000;
  public void setMaxConnections(int maxCon) {
      this.maxConnections = maxCon;
      LimitLatch latch = this.connectionLimitLatch;
      if (latch != null) {
          // Update the latch that enforces this
          if (maxCon == -1) {
              releaseConnectionLatch();
          } else {
              latch.setLimit(maxCon);
          }
      } else if (maxCon > 0) {
          initializeConnectionLatch();
      }
  }
  ```



Acceptor**

```java


public void run() {

        int errorDelay = 0;

        // Loop until we receive a shutdown command
        while (endpoint.isRunning()) {

            // Loop if endpoint is paused
            while (endpoint.isPaused() && endpoint.isRunning()) {
                state = AcceptorState.PAUSED;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }

            if (!endpoint.isRunning()) {
                break;
            }
            state = AcceptorState.RUNNING;

            try {
                //if we have reached max connections, wait
                endpoint.countUpOrAwaitConnection();

                // Endpoint might have been paused while waiting for latch
                // If that is the case, don't accept new connections
                if (endpoint.isPaused()) {
                    continue;
                }

                U socket = null;
                try {
                    // Accept the next incoming connection from the server
                    // socket
                    socket = endpoint.serverSocketAccept();
                } catch (Exception ioe) {
                    // We didn't get a socket
                    endpoint.countDownConnection();
                    if (endpoint.isRunning()) {
                        // Introduce delay if necessary
                        errorDelay = handleExceptionWithDelay(errorDelay);
                        // re-throw
                        throw ioe;
                    } else {
                        break;
                    }
                }
                // Successful accept, reset the error delay
                errorDelay = 0;

                // Configure the socket
                if (endpoint.isRunning() && !endpoint.isPaused()) {
                    // setSocketOptions() will hand the socket off to
                    // an appropriate processor if successful
                    //这里注册到poller上面
                    if (!endpoint.setSocketOptions(socket)) {
                        endpoint.closeSocket(socket);
                    }
                } else {
                    endpoint.destroySocket(socket);
                }
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                String msg = sm.getString("endpoint.accept.fail");
                // APR specific.
                // Could push this down but not sure it is worth the trouble.
                if (t instanceof Error) {
                    Error e = (Error) t;
                    if (e.getError() == 233) {
                        // Not an error on HP-UX so log as a warning
                        // so it can be filtered out on that platform
                        // See bug 50273
                        log.warn(msg, t);
                    } else {
                        log.error(msg, t);
                    }
                } else {
                        log.error(msg, t);
                }
            }
        }
        state = AcceptorState.ENDED;
    }

```

**NioEndpoint**

```java
 protected boolean setSocketOptions(SocketChannel socket) {
        // Process the connection
        try {
            //disable blocking, APR style, we are gonna be polling it
            socket.configureBlocking(false);
            Socket sock = socket.socket();
            socketProperties.setProperties(sock);

            NioChannel channel = nioChannels.pop();
            if (channel == null) {
                SocketBufferHandler bufhandler = new SocketBufferHandler(
                        socketProperties.getAppReadBufSize(),
                        socketProperties.getAppWriteBufSize(),
                        socketProperties.getDirectBuffer());
                if (isSSLEnabled()) {
                    channel = new SecureNioChannel(socket, bufhandler, selectorPool, this);
                } else {
                    channel = new NioChannel(socket, bufhandler);
                }
            } else {
                channel.setIOChannel(socket);
                channel.reset();
            }
           //随机选择一个pooler然后注册到上面
            getPoller0().register(channel);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error(sm.getString("endpoint.socketOptionsError"), t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(tt);
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }

```



**Poller**

```java
  /**
         * Registers a newly created socket with the poller.
         *
         * @param socket    The newly created socket
         */
        public void register(final NioChannel socket) {
            socket.setPoller(this);
            NioSocketWrapper ka = new NioSocketWrapper(socket, NioEndpoint.this);//很重要的一个类
            socket.setSocketWrapper(ka);
            ka.setPoller(this);
            ka.setReadTimeout(getConnectionTimeout());
            ka.setWriteTimeout(getConnectionTimeout());
            ka.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            ka.setSecure(isSSLEnabled());
            PollerEvent r = eventCache.pop();
            ka.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.
            if ( r==null) r = new PollerEvent(socket,ka,OP_REGISTER);
            else r.reset(socket,ka,OP_REGISTER);
            addEvent(r);
        }
```



**AbstractEndpoint**

```java
 /**
     * Process the given SocketWrapper with the given status. Used to trigger
     * processing as if the Poller (for those endpoints that have one)
     * selected the socket.
     *
     * @param socketWrapper The socket wrapper to process
     * @param event         The socket event to be processed
     * @param dispatch      Should the processing be performed on a new
     *                          container thread
     *
     * @return if processing was triggered successfully
     */
    public boolean processSocket(SocketWrapperBase<S> socketWrapper,
            SocketEvent event, boolean dispatch) {
        try {
            if (socketWrapper == null) {
                return false;
            }
            SocketProcessorBase<S> sc = processorCache.pop();
            if (sc == null) {
                sc = createSocketProcessor(socketWrapper, event);
            } else {
                sc.reset(socketWrapper, event);
            }
            Executor executor = getExecutor();
            if (dispatch && executor != null) {
                executor.execute(sc);//这里异步了
            } else {
                sc.run();
            }
        } catch (RejectedExecutionException ree) {
            getLog().warn(sm.getString("endpoint.executor.fail", socketWrapper) , ree);
            return false;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            getLog().error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }
```



```java
 private void internalDoFilter(ServletRequest request,
                                  ServletResponse response)
        throws IOException, ServletException {

        // Call the next filter if there is one
        if (pos < n) {
            ApplicationFilterConfig filterConfig = filters[pos++];//注意这里的pos++
            try {
                Filter filter = filterConfig.getFilter();

                if (request.isAsyncSupported() && "false".equalsIgnoreCase(
                        filterConfig.getFilterDef().getAsyncSupported())) {
                    request.setAttribute(Globals.ASYNC_SUPPORTED_ATTR, Boolean.FALSE);
                }
                if( Globals.IS_SECURITY_ENABLED ) {
                    final ServletRequest req = request;
                    final ServletResponse res = response;
                    Principal principal =
                        ((HttpServletRequest) req).getUserPrincipal();

                    Object[] args = new Object[]{req, res, this};
                    SecurityUtil.doAsPrivilege ("doFilter", filter, classType, args, principal);
                } else {
                    filter.doFilter(request, response, this);//核心在这里,把filterchain作为参数传递进去,filter主动调用filterchain的方法
                }
            } catch (IOException | ServletException | RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                e = ExceptionUtils.unwrapInvocationTargetException(e);
                ExceptionUtils.handleThrowable(e);
                throw new ServletException(sm.getString("filterChain.filter"), e);
            }
            return;
        }

        // We fell off the end of the chain -- call the servlet instance
        try {
            if (ApplicationDispatcher.WRAP_SAME_OBJECT) {
                lastServicedRequest.set(request);
                lastServicedResponse.set(response);
            }

            if (request.isAsyncSupported() && !servletSupportsAsync) {
                request.setAttribute(Globals.ASYNC_SUPPORTED_ATTR,
                        Boolean.FALSE);
            }
            // Use potentially wrapped request from this point
            if ((request instanceof HttpServletRequest) &&
                    (response instanceof HttpServletResponse) &&
                    Globals.IS_SECURITY_ENABLED ) {
                final ServletRequest req = request;
                final ServletResponse res = response;
                Principal principal =
                    ((HttpServletRequest) req).getUserPrincipal();
                Object[] args = new Object[]{req, res};
                SecurityUtil.doAsPrivilege("service",
                                           servlet,
                                           classTypeUsedInService,
                                           args,
                                           principal);
            } else {//这里调用了service方法
                servlet.service(request, response);
            }
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            e = ExceptionUtils.unwrapInvocationTargetException(e);
            ExceptionUtils.handleThrowable(e);
            throw new ServletException(sm.getString("filterChain.servlet"), e);
        } finally {
            if (ApplicationDispatcher.WRAP_SAME_OBJECT) {
                lastServicedRequest.set(null);
                lastServicedResponse.set(null);
            }
        }
    }

```

