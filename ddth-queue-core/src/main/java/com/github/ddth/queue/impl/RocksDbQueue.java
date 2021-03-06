package com.github.ddth.queue.impl;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.io.FileUtils;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.ddth.queue.IQueue;
import com.github.ddth.queue.IQueueMessage;
import com.github.ddth.queue.impl.rocksdb.RocksDbUtils;
import com.github.ddth.queue.impl.rocksdb.RocksDbWrapper;
import com.github.ddth.queue.utils.QueueException;
import com.github.ddth.queue.utils.QueueUtils;

/**
 * RocksDB implementation of {@link IQueue}.
 * 
 * <p>
 * Implementation:
 * <ul>
 * <li>RocksDB as queue storage</li>
 * </ul>
 * </p>
 * 
 * @author Thanh Ba Nguyen <bnguyen2k@gmail.com>
 * @since 0.4.0
 */
public abstract class RocksDbQueue extends AbstractEphemeralSupportQueue {

    static {
        RocksDB.loadLibrary();
    }

    private Logger LOGGER = LoggerFactory.getLogger(RocksDbQueue.class);

    private byte[] lastFetchedId = null;
    private Lock lockPut = new ReentrantLock(), lockTake = new ReentrantLock();

    private String storageDir = "/tmp/ddth-rocksdb-queue";
    private String cfNameQueue = "queue", cfNameMetadata = "metadata",
            cfNameEphemeral = "ephemeral";
    private DBOptions dbOptions;
    private ReadOptions readOptions;
    private WriteOptions writeOptions;
    private RocksDbWrapper rocksDbWrapper;
    private WriteBatch batchPutToQueue, batchTake;
    private ColumnFamilyHandle cfQueue, cfMetadata, cfEphemeral;
    private RocksIterator itQueue, itEphemeral;

    /**
     * RocksDB's storage directory.
     * 
     * @return
     */
    public String getStorageDir() {
        return storageDir;
    }

    /**
     * Sets RocksDB's storage directory.
     * 
     * @param storageDir
     * @return
     */
    public RocksDbQueue setStorageDir(String storageDir) {
        this.storageDir = storageDir;
        return this;
    }

    /**
     * Name of the ColumnFamily to store queue messages.
     * 
     * @return
     * @since 0.4.0.1
     */
    public String getCfNameQueue() {
        return cfNameQueue;
    }

    /**
     * Sets name of the ColumnFamily to store queue messages.
     * 
     * @param cfNameQueue
     * @return
     * @since 0.4.0.1
     */
    public RocksDbQueue setCfNameQueue(String cfNameQueue) {
        this.cfNameQueue = cfNameQueue;
        return this;
    }

    /**
     * Name of the ColumnFamily to store metadata.
     * 
     * @return
     * @since 0.4.0.1
     */
    public String getCfNameMetadata() {
        return cfNameMetadata;
    }

    /**
     * Sets name of the ColumnFamily to store metadata.
     * 
     * @param cfNameMetadata
     * @return
     * @since 0.4.0.1
     */
    public RocksDbQueue setCfNameMetadata(String cfNameMetadata) {
        this.cfNameMetadata = cfNameMetadata;
        return this;
    }

    /**
     * Name of the ColumnFamily to store ephemeral messages.
     * 
     * @return
     * @since 0.4.0.1
     */
    public String getCfNameEphemeral() {
        return cfNameEphemeral;
    }

    /**
     * Sets name of the ColumnFamily to store ephemeral messages.
     * 
     * @param cfNameEphemeral
     * @return
     * @since 0.4.0.1
     */
    public RocksDbQueue setCfNameEphemeral(String cfNameEphemeral) {
        this.cfNameEphemeral = cfNameEphemeral;
        return this;
    }

    /*----------------------------------------------------------------------*/

    /**
     * Init method.
     * 
     * @return
     */
    public RocksDbQueue init() {
        File STORAGE_DIR = new File(storageDir);
        LOGGER.info("Storage Directory: " + STORAGE_DIR.getAbsolutePath());
        try {
            FileUtils.forceMkdir(STORAGE_DIR);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            batchPutToQueue = new WriteBatch();
            batchTake = new WriteBatch();

            dbOptions = RocksDbUtils.buildDbOptions();
            rocksDbWrapper = RocksDbWrapper.openReadWrite(STORAGE_DIR, dbOptions, null, null,
                    new String[] { cfNameEphemeral, cfNameMetadata, cfNameQueue });
            readOptions = rocksDbWrapper.getReadOptions();
            writeOptions = rocksDbWrapper.getWriteOptions();

            cfEphemeral = rocksDbWrapper.getColumnFamilyHandle(cfNameEphemeral);
            cfMetadata = rocksDbWrapper.getColumnFamilyHandle(cfNameMetadata);
            cfQueue = rocksDbWrapper.getColumnFamilyHandle(cfNameQueue);

            itQueue = rocksDbWrapper.getIterator(cfNameQueue);
            itEphemeral = rocksDbWrapper.getIterator(cfNameEphemeral);
            lastFetchedId = loadLastFetchedId();
        } catch (Exception e) {
            destroy();
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }

        return this;
    }

    /**
     * Destroy method.
     */
    public void destroy() {
        try {
            saveLastFetchedId(lastFetchedId);
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }

        try {
            rocksDbWrapper.close();
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }

        RocksDbUtils.closeRocksObjects(batchPutToQueue, batchTake, dbOptions);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        destroy();
    }

    private final static byte[] keyLastFetchedId = "last-fetched-id".getBytes(QueueUtils.UTF8);

    /**
     * Loads last saved last-fetched-id.
     * 
     * @return
     * @since 0.4.0.1
     */
    private byte[] loadLastFetchedId() {
        return rocksDbWrapper.get(cfMetadata, readOptions, keyLastFetchedId);
    }

    /**
     * Saves last-fetched-id.
     * 
     * @param lastFetchedId
     * @since 0.4.0.1
     */
    private void saveLastFetchedId(byte[] lastFetchedId) {
        if (lastFetchedId != null) {
            rocksDbWrapper.put(cfMetadata, writeOptions, keyLastFetchedId, lastFetchedId);
        }
    }

    /**
     * Serializes a queue message to byte[].
     * 
     * @param msg
     * @return
     */
    protected abstract byte[] serialize(IQueueMessage msg);

    /**
     * Deserilizes a queue message.
     * 
     * @param msgData
     * @return
     */
    protected abstract IQueueMessage deserialize(byte[] msgData);

    protected boolean putToQueue(IQueueMessage msg, boolean removeFromEphemeral) {
        byte[] value = serialize(msg);
        lockPut.lock();
        try {
            byte[] key = QueueUtils.IDGEN.generateId128Hex().toLowerCase()
                    .getBytes(QueueUtils.UTF8);
            try {
                batchPutToQueue.put(cfQueue, key, value);
                if (removeFromEphemeral && !isEphemeralDisabled()) {
                    byte[] _key = msg.qId().toString().getBytes(QueueUtils.UTF8);
                    batchPutToQueue.remove(cfEphemeral, _key);
                }
                rocksDbWrapper.write(writeOptions, batchPutToQueue);
            } finally {
                batchPutToQueue.clear();
            }
            return true;
        } finally {
            lockPut.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean queue(IQueueMessage _msg) {
        IQueueMessage msg = _msg.clone();
        Date now = new Date();
        msg.qNumRequeues(0).qOriginalTimestamp(now).qTimestamp(now);
        return putToQueue(msg, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeue(IQueueMessage _msg) {
        IQueueMessage msg = _msg.clone();
        Date now = new Date();
        msg.qIncNumRequeues().qTimestamp(now);
        return putToQueue(msg, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean requeueSilent(IQueueMessage msg) {
        return putToQueue(msg.clone(), true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finish(IQueueMessage msg) {
        if (!isEphemeralDisabled()) {
            byte[] key = msg.qId().toString().getBytes(QueueUtils.UTF8);
            rocksDbWrapper.delete(cfEphemeral, writeOptions, key);
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @throws QueueException.EphemeralIsFull
     *             if the ephemeral storage is full
     */
    @Override
    public IQueueMessage take() throws QueueException.EphemeralIsFull {
        if (!isEphemeralDisabled()) {
            int ephemeralMaxSize = getEphemeralMaxSize();
            if (ephemeralMaxSize > 0 && ephemeralSize() >= ephemeralMaxSize) {
                throw new QueueException.EphemeralIsFull(ephemeralMaxSize);
            }
        }
        lockTake.lock();
        try {
            if (lastFetchedId == null) {
                itQueue.seekToFirst();
            } else {
                itQueue.seek(lastFetchedId);
            }
            if (!itQueue.isValid()) {
                return null;
            }
            lastFetchedId = itQueue.key();
            byte[] value = itQueue.value();
            IQueueMessage msg = deserialize(value);
            try {
                batchTake.remove(cfQueue, lastFetchedId);
                batchTake.put(cfMetadata, keyLastFetchedId, lastFetchedId);
                if (!isEphemeralDisabled() && msg != null) {
                    byte[] _key = msg.qId().toString().getBytes(QueueUtils.UTF8);
                    batchTake.put(cfEphemeral, _key, value);
                }
                rocksDbWrapper.write(writeOptions, batchTake);
            } finally {
                batchTake.clear();
            }
            itQueue.next();
            return msg;
        } finally {
            lockTake.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<IQueueMessage> getOrphanMessages(long thresholdTimestampMs) {
        if (isEphemeralDisabled()) {
            return null;
        }
        synchronized (itEphemeral) {
            Collection<IQueueMessage> orphanMessages = new HashSet<>();
            long now = System.currentTimeMillis();
            itEphemeral.seekToFirst();
            while (itEphemeral.isValid()) {
                byte[] value = itEphemeral.value();
                IQueueMessage msg = deserialize(value);
                if (msg.qTimestamp().getTime() + thresholdTimestampMs < now) {
                    orphanMessages.add(msg);
                }
                itEphemeral.next();
            }
            return orphanMessages;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean moveFromEphemeralToQueueStorage(IQueueMessage msg) {
        return putToQueue(msg, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int queueSize() {
        return (int) rocksDbWrapper.getEstimateNumKeys(cfNameQueue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int ephemeralSize() {
        return (int) rocksDbWrapper.getEstimateNumKeys(cfNameEphemeral);
    }
}
