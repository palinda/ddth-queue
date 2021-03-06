package com.github.ddth.queue.impl.universal2;

import com.github.ddth.queue.QueueSpec;
import com.github.ddth.queue.impl.JdbcQueueFactory;

/**
 * Factory to create {@link LessLockingUniversalMySQLQueue} instances.
 * 
 * @author Thanh Ba Nguyen <bnguyen2k@gmail.com>
 * @since 0.4.1
 */
public class LessLockingUniversalMySQLQueueFactory
        extends JdbcQueueFactory<LessLockingUniversalMySQLQueue> {

    public final static String SPEC_FIELD_FIFO = "fifo";

    /**
     * {@inheritDoc}
     */
    @Override
    protected LessLockingUniversalMySQLQueue createQueueInstance(final QueueSpec spec) {
        LessLockingUniversalMySQLQueue queue = new LessLockingUniversalMySQLQueue() {
            public void destroy() {
                disposeQueue(spec, this);
                super.destroy();
            }
        };
        Boolean fifo = spec.getField(SPEC_FIELD_FIFO, Boolean.class);
        if (fifo != null) {
            queue.setFifo(fifo.booleanValue());
        }
        return queue;
    }

}
