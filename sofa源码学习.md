### sofa源码学习

https://www.sofastack.tech/posts/2018-12-06-03

![image.png | left | 747x242](https://cdn.nlark.com/yuque/0/2018/png/172326/1543916153205-742acbc1-efed-4061-b1a8-a9dadaecf648.png)

```java
//server-fail-fast
//RpcCommandDecoderV2 还没解码前 直接new一个对象
 private RpcRequestCommand createRequestCommand(short cmdCode) {
        RpcRequestCommand command = new RpcRequestCommand();
        command.setCmdCode(RpcCommandCode.valueOf(cmdCode));
        command.setArriveTime(System.currentTimeMillis());
        return command;
    }

//RpcRequestProcessor

 @Override
    public void doProcess(final RemotingContext ctx, RpcRequestCommand cmd) throws Exception {
        long currenTimestamp = System.currentTimeMillis();

        preProcessRemotingContext(ctx, cmd, currenTimestamp);
        if (ctx.isTimeoutDiscard() && ctx.isRequestTimeout()) {
            timeoutLog(cmd, currenTimestamp, ctx);// do some log
            return;// then, discard this request
        }
        debugLog(ctx, cmd, currenTimestamp);
        // decode request all 到这里才解码
        if (!deserializeRequestCommand(ctx, cmd, RpcDeserializeLevel.DESERIALIZE_ALL)) {
            return;
        }
        dispatchToUserProcessor(ctx, cmd);
    }


 /**
     * pre process remoting context, initial some useful infos and pass to biz
     * 
     * @param ctx
     * @param cmd
     * @param currenTimestamp
     */
    private void preProcessRemotingContext(RemotingContext ctx, RpcRequestCommand cmd,
                                           long currenTimestamp) {
        ctx.setArriveTimestamp(cmd.getArriveTime());
        ctx.setTimeout(cmd.getTimeout());
        ctx.setRpcCommandType(cmd.getType());
        ctx.getInvokeContext().putIfAbsent(InvokeContext.BOLT_PROCESS_WAIT_TIME,
            currenTimestamp - cmd.getArriveTime());
    }
	
//类 RemotingContext
   /**
     * whether this request already timeout
     * 
     * @return
     */
    public boolean isRequestTimeout() {
        if (this.timeout > 0 && (this.rpcCommandType != RpcCommandType.REQUEST_ONEWAY)
            && (System.currentTimeMillis() - this.arriveTimestamp) > this.timeout) {
            return true;
        }
        return false;
    }

```

