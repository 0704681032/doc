## kafka源码剖析



```java
//Selector   

@Override
    public void connect(String id, InetSocketAddress address, int sendBufferSize, int receiveBufferSize) throws IOException {
        ensureNotRegistered(id);//确保只有一个连接
        SocketChannel socketChannel = SocketChannel.open();
        SelectionKey key = null;
        try {
            configureSocketChannel(socketChannel, sendBufferSize, receiveBufferSize);
            boolean connected = doConnect(socketChannel, address);
            key = registerChannel(id, socketChannel, SelectionKey.OP_CONNECT);

            if (connected) {
                // OP_CONNECT won't trigger for immediately connected channels
                log.debug("Immediately connected to node {}", id);
                immediatelyConnectedKeys.add(key);
                key.interestOps(0);
            }
        } catch (IOException | RuntimeException e) {
            if (key != null)
                immediatelyConnectedKeys.remove(key);
            channels.remove(id);
            socketChannel.close();
            throw e;
        }
    }
	
  //单一长连接
  private void ensureNotRegistered(String id) {
        if (this.channels.containsKey(id))
            throw new IllegalStateException("There is already a connection for id " + id);
        if (this.closingChannels.containsKey(id))
            throw new IllegalStateException("There is already a connection for id " + id + " that is still being closed");
    }


//KafkaChannel
public void setSend(Send send) {
        if (this.send != null) //检查send
            throw new IllegalStateException("Attempt to begin a send operation with prior send operation still in progress, connection id is " + id);
        this.send = send;
        this.transportLayer.addInterestOps(SelectionKey.OP_WRITE);
    }

//KafkaChannel
 public Send write() throws IOException {
        Send result = null;
        if (send != null && send(send)) {
            result = send;
            send = null;
        }
        return result;
    }

//NetworkClient
  /**
     * Are we connected and ready and able to send more requests to the given connection?
     *
     * @param node The node
     * @param now the current timestamp
     */
    private boolean canSendRequest(String node, long now) {
        return connectionStates.isReady(node, now) && selector.isChannelReady(node) &&
            inFlightRequests.canSendMore(node);
    }
		

//InFlightRequests
    /**
     * Can we send more requests to this node?
     *
     * @param node Node in question
     * @return true iff we have no requests still being sent to the given node
     */
    public boolean canSendMore(String node) {
        Deque<NetworkClient.InFlightRequest> queue = requests.get(node);
        return queue == null || queue.isEmpty() ||
               (queue.peekFirst().send.completed() && queue.size() < this.maxInFlightRequestsPerConnection);
    }
```



```java
//sender
/**
     * Create a produce request from the given record batches
     */
    private void sendProduceRequest(long now, int destination, short acks, int timeout, List<ProducerBatch> batches) {
        if (batches.isEmpty())
            return;

        Map<TopicPartition, MemoryRecords> produceRecordsByPartition = new HashMap<>(batches.size());
        final Map<TopicPartition, ProducerBatch> recordsByPartition = new HashMap<>(batches.size());

        // find the minimum magic version used when creating the record sets
        byte minUsedMagic = apiVersions.maxUsableProduceMagic();
        for (ProducerBatch batch : batches) {
            if (batch.magic() < minUsedMagic)
                minUsedMagic = batch.magic();
        }

        for (ProducerBatch batch : batches) {
            TopicPartition tp = batch.topicPartition;
            MemoryRecords records = batch.records();

            // down convert if necessary to the minimum magic used. In general, there can be a delay between the time
            // that the producer starts building the batch and the time that we send the request, and we may have
            // chosen the message format based on out-dated metadata. In the worst case, we optimistically chose to use
            // the new message format, but found that the broker didn't support it, so we need to down-convert on the
            // client before sending. This is intended to handle edge cases around cluster upgrades where brokers may
            // not all support the same message format version. For example, if a partition migrates from a broker
            // which is supporting the new magic version to one which doesn't, then we will need to convert.
            if (!records.hasMatchingMagic(minUsedMagic))
                records = batch.records().downConvert(minUsedMagic, 0, time).records();
            produceRecordsByPartition.put(tp, records);
            recordsByPartition.put(tp, batch);
        }

        String transactionalId = null;
        if (transactionManager != null && transactionManager.isTransactional()) {
            transactionalId = transactionManager.transactionalId();
        }
        ProduceRequest.Builder requestBuilder = ProduceRequest.Builder.forMagic(minUsedMagic, acks, timeout,
                produceRecordsByPartition, transactionalId);
        RequestCompletionHandler callback = new RequestCompletionHandler() {
            public void onComplete(ClientResponse response) {
                handleProduceResponse(response, recordsByPartition, time.milliseconds());
            }
        };
				//目的地
        String nodeId = Integer.toString(destination);
        ClientRequest clientRequest = client.newClientRequest(nodeId, requestBuilder, now, acks != 0,
                requestTimeoutMs, callback);
        client.send(clientRequest, now);
        log.trace("Sent produce request to {}: {}", nodeId, requestBuilder);
    }

```



```java
//RecordAccumulator
  private final ConcurrentMap<TopicPartition, Deque<ProducerBatch>> batches;
    private final IncompleteBatches incomplete;
    private long nextBatchExpiryTimeMs = Long.MAX_VALUE; // the earliest time (absolute) a batch will expire.


  public void resetNextBatchExpiryTime() {
        nextBatchExpiryTimeMs = Long.MAX_VALUE;
    }

    public void maybeUpdateNextBatchExpiryTime(ProducerBatch batch) {
        if (batch.createdMs + deliveryTimeoutMs  > 0) {
            // the non-negative check is to guard us against potential overflow due to setting
            // a large value for deliveryTimeoutMs
            nextBatchExpiryTimeMs = Math.min(nextBatchExpiryTimeMs, batch.createdMs + deliveryTimeoutMs);
        } else {
            log.warn("Skipping next batch expiry time update due to addition overflow: "
                + "batch.createMs={}, deliveryTimeoutMs={}", batch.createdMs, deliveryTimeoutMs);
        }
    }

  private RecordAppendResult tryAppend(long timestamp, byte[] key, byte[] value, Header[] headers,
                                         Callback callback, Deque<ProducerBatch> deque) {
        ProducerBatch last = deque.peekLast();
        if (last != null) {
            FutureRecordMetadata future = last.tryAppend(timestamp, key, value, headers, callback, time.milliseconds());
            if (future == null)
                last.closeForRecordAppends();
            else
                return new RecordAppendResult(future, deque.size() > 1 || last.isFull(), false);
        }
        return null;
    }

//ProducerBatch
    /**
     * Append the record to the current record set and return the relative offset within that record set
     *
     * @return The RecordSend corresponding to this record or null if there isn't sufficient room.
     */
    public FutureRecordMetadata tryAppend(long timestamp, byte[] key, byte[] value, Header[] headers, Callback callback, long now) {
        if (!recordsBuilder.hasRoomFor(timestamp, key, value, headers)) {
            return null;
        } else {
            Long checksum = this.recordsBuilder.append(timestamp, key, value, headers);
            this.maxRecordSize = Math.max(this.maxRecordSize, AbstractRecords.estimateSizeInBytesUpperBound(magic(),
                    recordsBuilder.compressionType(), key, value, headers));
            this.lastAppendTime = now;
            FutureRecordMetadata future = new FutureRecordMetadata(this.produceFuture, this.recordCount,
                                                                   timestamp, checksum,
                                                                   key == null ? -1 : key.length,
                                                                   value == null ? -1 : value.length,
                                                                   Time.SYSTEM);
            // we have to keep every future returned to the users in case the batch needs to be
            // split to several new batches and resent.
            thunks.add(new Thunk(callback, future));
            this.recordCount++;
            return future;
        }
    }
```

