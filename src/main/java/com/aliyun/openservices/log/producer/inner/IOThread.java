package com.aliyun.openservices.log.producer.inner;

import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.common.Consts;
import com.aliyun.openservices.log.common.TagContent;
import com.aliyun.openservices.log.exception.LogException;
import com.aliyun.openservices.log.producer.ProducerConfig;
import com.aliyun.openservices.log.request.PutLogsRequest;
import com.aliyun.openservices.log.response.PutLogsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class BlockedData {
    PackageData data;
    int bytes;

    BlockedData(PackageData data, int bytes) {
        super();
        this.data = data;
        this.bytes = bytes;
    }
};

class IOThread extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger(IOThread.class);

    private static final String IO_THREAD_NAME = "log-producer-io-thread";

    private static final String IO_WORKER_BASE_NAME = "log-producer-io-worker-";

    private ExecutorService cachedThreadPool;
    private BlockingQueue<BlockedData> dataQueue = new LinkedBlockingQueue<BlockedData>();
    private ClientPool clientPool;
    private PackageManager packageManager;
    private ProducerConfig producerConfig;
    private AtomicLong sendLogBytes = new AtomicLong(0L);
    private AtomicLong sendLogTimeWindowInMillis = new AtomicLong(0L);

    public static IOThread launch(ClientPool cltPool, PackageManager packageManager,
                                  ProducerConfig producerConfig) {
        IOThread ioThread = new IOThread(cltPool, packageManager, producerConfig);
        ioThread.setName(IO_THREAD_NAME);
        ioThread.setDaemon(true);
        ioThread.start();
        return ioThread;
    }

    private IOThread(ClientPool cltPool, PackageManager packageManager,
                    ProducerConfig producerConfig) {
        this.clientPool = cltPool;
        this.packageManager = packageManager;
        this.producerConfig = producerConfig;
        cachedThreadPool = new ThreadPoolExecutor(0,
                producerConfig.maxIOThreadSizeInPool, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), new NamedThreadFactory(IO_WORKER_BASE_NAME));
    }

    public void addPackage(PackageData data, int bytes) {
        data.markAddToIOBeginTime();
        try {
            dataQueue.put(new BlockedData(data, bytes));
        } catch (InterruptedException e) {
            LOGGER.error("Failed to put data into dataQueue.", e);
        }
        data.markAddToIOEndTime();
    }

    public void shutdown() {
        this.interrupt();
        cachedThreadPool.shutdown();
        while (!dataQueue.isEmpty()) {
            BlockedData bd;
            try {
                bd = dataQueue.poll(producerConfig.packageTimeoutInMS / 2, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LOGGER.error("Failed to poll data from dataQueue.", e);
                break;
            }
            if (bd != null) {
                sendData(bd);
            }
        }
    }

    public void shutdownNow() {
        this.interrupt();
        cachedThreadPool.shutdownNow();
    }

    private void sendData(BlockedData bd) {
        try {
            doSendData(bd);
        } catch (Exception e) {
            LOGGER.error("Failed to send data.", e);
        }
    }

    private void doSendData(BlockedData bd) {
        Client clt = clientPool.getClient(bd.data.project);
        if (clt == null) {
            bd.data.callback(null, new LogException("ProjectConfigNotExist",
                    "the config of project " + bd.data.project
                            + " is not exist", ""), 0);
        } else {
            int retry = 0;
            LogException excep = null;
            PutLogsResponse response = null;
            while (retry++ <= producerConfig.retryTimes) {
                try {
                    if (bd.data.shardHash != null
                            && !bd.data.shardHash.isEmpty()) {
                        PutLogsRequest request = new PutLogsRequest(
                                bd.data.project, bd.data.logstore,
                                bd.data.topic, bd.data.source, bd.data.items,
                                bd.data.shardHash);
                        List<TagContent> tags = new ArrayList<TagContent>();
                        tags.add(new TagContent("__pack_id__", bd.data.getPackageId()));
                        request.SetTags(tags);
                        request.setContentType(producerConfig.logsFormat.equals("protobuf") ?
                                Consts.CONST_PROTO_BUF
                                : Consts.CONST_SLS_JSON);
                        response = clt.PutLogs(request);

                    } else {
                        PutLogsRequest request = new PutLogsRequest(
                                bd.data.project, bd.data.logstore,
                                bd.data.topic, bd.data.source, bd.data.items);
                        List<TagContent> tags = new ArrayList<TagContent>();
                        tags.add(new TagContent("__pack_id__", bd.data.getPackageId()));
                        request.SetTags(tags);
                        request.setContentType(producerConfig.logsFormat.equals("protobuf") ?
                                Consts.CONST_PROTO_BUF
                                : Consts.CONST_SLS_JSON);
                        response = clt.PutLogs(request);
                    }
                    long tmpBytes = sendLogBytes.get();
                    sendLogBytes.set(tmpBytes + bd.bytes);
                    break;
                } catch (LogException e) {
                    excep = new LogException(e.GetErrorCode(),
                            e.GetErrorMessage() + ", itemscount: "
                                    + bd.data.items.size(), e.GetRequestId());
                }
            }
            long currTime = System.currentTimeMillis();
            float sec = (currTime - sendLogTimeWindowInMillis.get()) / 1000.0f;
            float outflow = 0;
            if (sec > 0)
                outflow = sendLogBytes.get() / sec;
            bd.data.callback(response, excep, outflow);
        }
        packageManager.releaseBytes(bd.bytes);
    }

    @Override
    public void run() {
        try {
            handleBlockedData();
        } catch (Exception e) {
            LOGGER.error("Failed to handle BlockedData.", e);
        }
    }

    private void handleBlockedData() {
        while (!isInterrupted()) {
            long currTime = System.currentTimeMillis();
            if ((currTime - sendLogTimeWindowInMillis.get()) > 60 * 1000) {
                sendLogBytes.set(0L);
                sendLogTimeWindowInMillis.set(currTime);
            }

            try {
                final BlockedData bd = dataQueue.poll(
                        producerConfig.packageTimeoutInMS / 2, TimeUnit.MILLISECONDS);
                if (bd != null) {
                    bd.data.markCompleteIOBeginTimeInMillis(dataQueue.size());
                    try {
                        cachedThreadPool.submit(new Runnable() {
                            public void run() {
                                sendData(bd);
                            }
                        });
                    } catch (RejectedExecutionException e) {
                        dataQueue.put(bd);
                    }
                }
            } catch (InterruptedException e) {
                LOGGER.warn("Thread has been interrupted.", e);
                break;
            }
        }
    }
}
