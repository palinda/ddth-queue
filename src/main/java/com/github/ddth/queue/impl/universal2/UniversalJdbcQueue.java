package com.github.ddth.queue.impl.universal2;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import com.github.ddth.queue.IQueue;
import com.github.ddth.queue.IQueueMessage;
import com.github.ddth.queue.impl.JdbcQueue;
import com.github.ddth.queue.utils.QueueUtils;

/**
 * Universal JDBC implementation of {@link IQueue}.
 * 
 * <p>
 * Queue and Take {@link UniversalQueueMessage}s.
 * </p>
 * 
 * <p>
 * {@code ephemeralDisabled}: when set to {@code true}, ephemeral storage is
 * disabled. Default value is {@code false}
 * </p>
 * 
 * <p>
 * {@code fifo}: when set to {@code true} (which is default) messages are taken
 * in FIFO manner.
 * </p>
 * 
 * <p>
 * Queue db table schema:
 * </p>
 * <ul>
 * <li>{@code queue_id}: {@code bigint, auto increment}, see
 * {@link IQueueMessage#qId()}, {@link #COL_QUEUE_ID}</li>
 * <li>{@code msg_org_timestamp}: {@code datetime}, see
 * {@link IQueueMessage#qOriginalTimestamp()}, {@link #COL_ORG_TIMESTAMP}</li>
 * <li>{@code msg_timestamp}: {@code datetime}, see
 * {@link IQueueMessage#qTimestamp()}, {@link #COL_TIMESTAMP}</li>
 * <li>{@code msg_num_requeues}: {@code int}, see
 * {@link IQueueMessage#qNumRequeues()}, {@link #COL_NUM_REQUEUES}</li>
 * <li>{@code msg_content}: {@code blob}, message's content, see
 * {@link #COL_CONTENT}</li>
 * </ul>
 * 
 * <p>
 * Ephemeral db table schema:
 * </p>
 * <ul>
 * <li>{@code queue_id}: {@code bigint}, see {@link IQueueMessage#qId()},
 * {@link #COL_QUEUE_ID}</li>
 * <li>{@code msg_org_timestamp}: {@code datetime}, see
 * {@link IQueueMessage#qOriginalTimestamp()}, {@link #COL_ORG_TIMESTAMP}</li>
 * <li>{@code msg_timestamp}: {@code datetime}, see
 * {@link IQueueMessage#qTimestamp()}, {@link #COL_TIMESTAMP}</li>
 * <li>{@code msg_num_requeues}: {@code int}, see
 * {@link IQueueMessage#qNumRequeues()}, {@link #COL_NUM_REQUEUES}</li>
 * <li>{@code msg_content}: {@code blob}, message's content, see
 * {@link #COL_CONTENT}</li>
 * </ul>
 * 
 * @author Thanh Ba Nguyen <bnguyen2k@gmail.com>
 * @since 0.3.3
 */
public class UniversalJdbcQueue extends JdbcQueue {

    /** Table's column name to store queue-id */
    public final static String COL_QUEUE_ID = "queue_id";

    /** Table's column name to store message's original timestamp */
    public final static String COL_ORG_TIMESTAMP = "msg_org_timestamp";

    /** Table's column name to store message's timestamp */
    public final static String COL_TIMESTAMP = "msg_timestamp";

    /** Table's column name to store message's number of requeues */
    public final static String COL_NUM_REQUEUES = "msg_num_requeues";

    /** Table's column name to store message's content */
    public final static String COL_CONTENT = "msg_content";

    private boolean fifo = true;
    private boolean ephemeralDisabled = false;

    public UniversalJdbcQueue setFifo(boolean fifo) {
        this.fifo = fifo;
        return this;
    }

    public UniversalJdbcQueue markFifo(boolean fifo) {
        this.fifo = fifo;
        return this;
    }

    public boolean isFifo() {
        return fifo;
    }

    public boolean getFifo() {
        return fifo;
    }

    public UniversalJdbcQueue setEphemeralDisabled(boolean ephemeralDisabled) {
        this.ephemeralDisabled = ephemeralDisabled;
        return this;
    }

    public UniversalJdbcQueue markEphemeralDisabled(boolean setEphemeralDisabled) {
        this.ephemeralDisabled = setEphemeralDisabled;
        return this;
    }

    public boolean isEphemeralDisabled() {
        return ephemeralDisabled;
    }

    public boolean getEphemeralDisabled() {
        return ephemeralDisabled;
    }

    /*----------------------------------------------------------------------*/

    private String SQL_READ_FROM_QUEUE, SQL_READ_FROM_EPHEMERAL;
    private String SQL_GET_ORPHAN_MSGS;
    private String SQL_PUT_NEW_TO_QUEUE, SQL_REPUT_TO_QUEUE, SQL_PUT_TO_EPHEMERAL;
    private String SQL_REMOVE_FROM_QUEUE, SQL_REMOVE_FROM_EPHEMERAL;

    public UniversalJdbcQueue init() {
        super.init();

        SQL_READ_FROM_QUEUE = "SELECT {1}, {2}, {3}, {4}, {5} FROM {0}"
                + (fifo ? (" ORDER BY " + COL_QUEUE_ID + " DESC") : "") + " LIMIT 1";
        SQL_READ_FROM_QUEUE = MessageFormat.format(SQL_READ_FROM_QUEUE, getTableName(),
                COL_QUEUE_ID + " AS " + UniversalQueueMessage.FIELD_QUEUE_ID, COL_ORG_TIMESTAMP
                        + " AS " + UniversalQueueMessage.FIELD_ORG_TIMESTAMP, COL_TIMESTAMP
                        + " AS " + UniversalQueueMessage.FIELD_TIMESTAMP, COL_NUM_REQUEUES + " AS "
                        + UniversalQueueMessage.FIELD_NUM_REQUEUES, COL_CONTENT + " AS "
                        + UniversalQueueMessage.FIELD_CONTENT);

        SQL_READ_FROM_EPHEMERAL = "SELECT {1}, {2}, {3}, {4}, {5} FROM {0} WHERE " + COL_QUEUE_ID
                + "=?";
        SQL_READ_FROM_EPHEMERAL = MessageFormat.format(SQL_READ_FROM_EPHEMERAL,
                getTableNameEphemeral(), COL_QUEUE_ID + " AS "
                        + UniversalQueueMessage.FIELD_QUEUE_ID, COL_ORG_TIMESTAMP + " AS "
                        + UniversalQueueMessage.FIELD_ORG_TIMESTAMP, COL_TIMESTAMP + " AS "
                        + UniversalQueueMessage.FIELD_TIMESTAMP, COL_NUM_REQUEUES + " AS "
                        + UniversalQueueMessage.FIELD_NUM_REQUEUES, COL_CONTENT + " AS "
                        + UniversalQueueMessage.FIELD_CONTENT);

        SQL_GET_ORPHAN_MSGS = "SELECT {1}, {2}, {3}, {4}, {5} FROM {0} WHERE " + COL_TIMESTAMP
                + "<?";
        SQL_GET_ORPHAN_MSGS = MessageFormat.format(SQL_GET_ORPHAN_MSGS, getTableNameEphemeral(),
                COL_QUEUE_ID + " AS " + UniversalQueueMessage.FIELD_QUEUE_ID, COL_ORG_TIMESTAMP
                        + " AS " + UniversalQueueMessage.FIELD_ORG_TIMESTAMP, COL_TIMESTAMP
                        + " AS " + UniversalQueueMessage.FIELD_TIMESTAMP, COL_NUM_REQUEUES + " AS "
                        + UniversalQueueMessage.FIELD_NUM_REQUEUES, COL_CONTENT + " AS "
                        + UniversalQueueMessage.FIELD_CONTENT);

        SQL_PUT_NEW_TO_QUEUE = "INSERT INTO {0} ({1}, {2}, {3}, {4}) VALUES (?, ?, ?, ?)";
        SQL_PUT_NEW_TO_QUEUE = MessageFormat.format(SQL_PUT_NEW_TO_QUEUE, getTableName(),
                COL_ORG_TIMESTAMP, COL_TIMESTAMP, COL_NUM_REQUEUES, COL_CONTENT);

        SQL_REPUT_TO_QUEUE = "INSERT INTO {0} ({1}, {2}, {3}, {4}, {5}) VALUES (?, ?, ?, ?, ?)";
        SQL_REPUT_TO_QUEUE = MessageFormat.format(SQL_REPUT_TO_QUEUE, getTableName(), COL_QUEUE_ID,
                COL_ORG_TIMESTAMP, COL_TIMESTAMP, COL_NUM_REQUEUES, COL_CONTENT);

        SQL_PUT_TO_EPHEMERAL = "INSERT INTO {0} ({1}, {2}, {3}, {4}, {5}) VALUES (?, ?, ?, ?, ?)";
        SQL_PUT_TO_EPHEMERAL = MessageFormat.format(SQL_PUT_TO_EPHEMERAL, getTableNameEphemeral(),
                COL_QUEUE_ID, COL_ORG_TIMESTAMP, COL_TIMESTAMP, COL_NUM_REQUEUES, COL_CONTENT);

        SQL_REMOVE_FROM_QUEUE = "DELETE FROM {0} WHERE " + COL_QUEUE_ID + "=?";
        SQL_REMOVE_FROM_QUEUE = MessageFormat.format(SQL_REMOVE_FROM_QUEUE, getTableName());

        SQL_REMOVE_FROM_EPHEMERAL = "DELETE FROM {0} WHERE " + COL_QUEUE_ID + "=?";
        SQL_REMOVE_FROM_EPHEMERAL = MessageFormat.format(SQL_REMOVE_FROM_EPHEMERAL,
                getTableNameEphemeral());

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected UniversalQueueMessage readFromQueueStorage(JdbcTemplate jdbcTemplate) {
        List<Map<String, Object>> dbRows = jdbcTemplate.queryForList(SQL_READ_FROM_QUEUE);
        if (dbRows != null && dbRows.size() > 0) {
            Map<String, Object> dbRow = dbRows.get(0);
            // ensureContentIsByteArray(dbRow);
            UniversalQueueMessage msg = new UniversalQueueMessage();
            return (UniversalQueueMessage) msg.fromMap(dbRow);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected UniversalQueueMessage readFromEphemeralStorage(JdbcTemplate jdbcTemplate,
            IQueueMessage msg) {
        if (ephemeralDisabled) {
            return null;
        }
        List<Map<String, Object>> dbRows = jdbcTemplate.queryForList(SQL_READ_FROM_EPHEMERAL,
                msg.qId());
        if (dbRows != null && dbRows.size() > 0) {
            Map<String, Object> dbRow = dbRows.get(0);
            // ensureContentIsByteArray(dbRow);
            UniversalQueueMessage myMsg = new UniversalQueueMessage();
            return (UniversalQueueMessage) myMsg.fromMap(dbRow);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Collection<IQueueMessage> getOrphanFromEphemeralStorage(JdbcTemplate jdbcTemplate,
            long thresholdTimestampMs) {
        if (ephemeralDisabled) {
            return null;
        }
        final Date threshold = new Date(thresholdTimestampMs);
        List<Map<String, Object>> dbRows = jdbcTemplate
                .queryForList(SQL_GET_ORPHAN_MSGS, threshold);
        if (dbRows != null && dbRows.size() > 0) {
            Collection<IQueueMessage> result = new ArrayList<IQueueMessage>();
            for (Map<String, Object> dbRow : dbRows) {
                UniversalQueueMessage msg = new UniversalQueueMessage();
                // ensureContentIsByteArray(dbRow);
                msg.fromMap(dbRow);
                result.add(msg);
            }
            return result;
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean putToQueueStorage(JdbcTemplate jdbcTemplate, IQueueMessage _msg) {
        if (!(_msg instanceof UniversalQueueMessage)) {
            throw new IllegalArgumentException("This method requires an argument of type ["
                    + UniversalQueueMessage.class.getName() + "]!");
        }

        UniversalQueueMessage msg = (UniversalQueueMessage) _msg;
        String qid = msg.qId();
        if (StringUtils.isEmpty(qid)) {
            qid = QueueUtils.IDGEN.generateId128Hex();
        }
        int numRows = jdbcTemplate.update(SQL_REPUT_TO_QUEUE, qid, msg.qOriginalTimestamp(),
                msg.qTimestamp(), msg.qNumRequeues(), msg.content());
        return numRows > 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean putToEphemeralStorage(JdbcTemplate jdbcTemplate, IQueueMessage _msg) {
        if (ephemeralDisabled) {
            return true;
        }

        if (!(_msg instanceof UniversalQueueMessage)) {
            throw new IllegalArgumentException("This method requires an argument of type ["
                    + UniversalQueueMessage.class.getName() + "]!");
        }

        UniversalQueueMessage msg = (UniversalQueueMessage) _msg;
        int numRows = jdbcTemplate.update(SQL_PUT_TO_EPHEMERAL, msg.qId(),
                msg.qOriginalTimestamp(), msg.qTimestamp(), msg.qNumRequeues(), msg.content());
        return numRows > 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean removeFromQueueStorage(JdbcTemplate jdbcTemplate, IQueueMessage _msg) {
        if (!(_msg instanceof UniversalQueueMessage)) {
            throw new IllegalArgumentException("This method requires an argument of type ["
                    + UniversalQueueMessage.class.getName() + "]!");
        }

        UniversalQueueMessage msg = (UniversalQueueMessage) _msg;
        int numRows = jdbcTemplate.update(SQL_REMOVE_FROM_QUEUE, msg.qId());
        return numRows > 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean removeFromEphemeralStorage(JdbcTemplate jdbcTemplate, IQueueMessage _msg) {
        if (ephemeralDisabled) {
            return true;
        }

        if (!(_msg instanceof UniversalQueueMessage)) {
            throw new IllegalArgumentException("This method requires an argument of type ["
                    + UniversalQueueMessage.class.getName() + "]!");
        }

        UniversalQueueMessage msg = (UniversalQueueMessage) _msg;
        int numRows = jdbcTemplate.update(SQL_REMOVE_FROM_EPHEMERAL, msg.qId());
        return numRows > 0;
    }

    /*------------------------------------------------------------*/
    /**
     * {@inheritDoc}
     */
    @Override
    public UniversalQueueMessage take() {
        return (UniversalQueueMessage) super.take();
    }

}