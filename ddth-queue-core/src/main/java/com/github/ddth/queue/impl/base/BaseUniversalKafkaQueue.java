package com.github.ddth.queue.impl.base;

import com.github.ddth.queue.IQueueMessage;
import com.github.ddth.queue.impl.KafkaQueue;
import com.github.ddth.queue.utils.QueueException;

/**
 * Base class for universal Kafka queue implementations.
 * 
 * @author Thanh Nguyen
 * 
 * @param <T>
 * @since 0.3.3
 */
public abstract class BaseUniversalKafkaQueue<T extends BaseUniversalQueueMessage>
        extends KafkaQueue {

    /**
     * {@inheritDoc}
     */
    @Override
    protected byte[] serialize(IQueueMessage _msg) throws QueueException {
        if (_msg == null) {
            return null;
        }
        if (!(_msg instanceof BaseUniversalQueueMessage)) {
            throw new IllegalArgumentException("This method requires an argument of type ["
                    + BaseUniversalQueueMessage.class.getName() + "]!");
        }

        BaseUniversalQueueMessage msg = (BaseUniversalQueueMessage) _msg;
        try {
            return msg.toBytes();
        } catch (Exception e) {
            throw new QueueException.CannotSerializeQueueMessage(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    @Override
    public T take() {
        return (T) super.take();
    }
}
