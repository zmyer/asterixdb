/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.transaction.management.service.logging;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.asterix.common.exceptions.ACIDException;
import org.apache.asterix.common.replication.IReplicationManager;
import org.apache.asterix.common.transactions.ILogBuffer;
import org.apache.asterix.common.transactions.ILogManager;
import org.apache.asterix.common.transactions.ILogReader;
import org.apache.asterix.common.transactions.ILogRecord;
import org.apache.asterix.common.transactions.ITransactionContext;
import org.apache.asterix.common.transactions.ITransactionManager;
import org.apache.asterix.common.transactions.ITransactionSubsystem;
import org.apache.asterix.common.transactions.LogManagerProperties;
import org.apache.asterix.common.transactions.LogSource;
import org.apache.asterix.common.transactions.LogType;
import org.apache.asterix.common.transactions.MutableLong;
import org.apache.asterix.common.transactions.TxnLogFile;
import org.apache.hyracks.api.lifecycle.ILifeCycleComponent;
import org.apache.hyracks.util.InvokeUtil;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

public class LogManager implements ILogManager, ILifeCycleComponent {

    private static final Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger();
    private static final long SMALLEST_LOG_FILE_ID = 0;
    private static final int INITIAL_LOG_SIZE = 0;
    private static final boolean IS_DEBUG_MODE = false;

    private final ITransactionSubsystem txnSubsystem;
    private final LogManagerProperties logManagerProperties;
    private final int numLogPages;
    private final String logDir;
    private final String logFilePrefix;
    private final MutableLong flushLSN;
    private final String nodeId;
    private final HashMap<Long, Integer> txnLogFileId2ReaderCount = new HashMap<>();
    private final long logFileSize;
    private final int logPageSize;
    private final AtomicLong appendLSN;
    private final long maxLogRecordSize;

    private LinkedBlockingQueue<ILogBuffer> emptyQ;
    private LinkedBlockingQueue<ILogBuffer> flushQ;
    private LinkedBlockingQueue<ILogBuffer> stashQ;
    private FileChannel appendChannel;
    private ILogBuffer appendPage;
    private LogFlusher logFlusher;
    private Future<? extends Object> futureLogFlusher;
    protected LinkedBlockingQueue<ILogRecord> flushLogsQ;
    private long currentLogFileId;

    public LogManager(ITransactionSubsystem txnSubsystem) {
        this.txnSubsystem = txnSubsystem;
        logManagerProperties =
                new LogManagerProperties(this.txnSubsystem.getTransactionProperties(), this.txnSubsystem.getId());
        logFileSize = logManagerProperties.getLogPartitionSize();
        maxLogRecordSize = logFileSize - 1;
        logPageSize = logManagerProperties.getLogPageSize();
        numLogPages = logManagerProperties.getNumLogPages();
        logDir = logManagerProperties.getLogDir();
        logFilePrefix = logManagerProperties.getLogFilePrefix();
        flushLSN = new MutableLong();
        appendLSN = new AtomicLong();
        nodeId = txnSubsystem.getId();
        flushLogsQ = new LinkedBlockingQueue<>();
        txnSubsystem.getApplicationContext().getThreadExecutor().execute(new FlushLogsLogger());
        initializeLogManager(SMALLEST_LOG_FILE_ID);
    }

    private void initializeLogManager(long nextLogFileId) {
        emptyQ = new LinkedBlockingQueue<>(numLogPages);
        flushQ = new LinkedBlockingQueue<>(numLogPages);
        stashQ = new LinkedBlockingQueue<>(numLogPages);
        for (int i = 0; i < numLogPages; i++) {
            emptyQ.add(new LogBuffer(txnSubsystem, logPageSize, flushLSN));
        }
        appendLSN.set(initializeLogAnchor(nextLogFileId));
        flushLSN.set(appendLSN.get());
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("LogManager starts logging in LSN: " + appendLSN);
        }
        try {
            setLogPosition(appendLSN.get());
        } catch (IOException e) {
            throw new ACIDException(e);
        }
        initNewPage(INITIAL_LOG_SIZE);
        logFlusher = new LogFlusher(this, emptyQ, flushQ, stashQ);
        futureLogFlusher =
                ((ExecutorService) txnSubsystem.getApplicationContext().getThreadExecutor()).submit(logFlusher);
    }

    @Override
    public void log(ILogRecord logRecord) {
        if (logRecord.getLogType() == LogType.FLUSH) {
            flushLogsQ.add(logRecord);
            return;
        }
        appendToLogTail(logRecord);
    }

    protected void appendToLogTail(ILogRecord logRecord) {
        syncAppendToLogTail(logRecord);
        if (waitForFlush(logRecord) && !logRecord.isFlushed()) {
            InvokeUtil.doUninterruptibly(() -> {
                synchronized (logRecord) {
                    while (!logRecord.isFlushed()) {
                        logRecord.wait();
                    }
                }
            });
        }
    }

    protected static boolean waitForFlush(ILogRecord logRecord) {
        final byte logType = logRecord.getLogType();
        return logType == LogType.JOB_COMMIT || logType == LogType.ABORT || logType == LogType.WAIT;
    }

    synchronized void syncAppendToLogTail(ILogRecord logRecord) {
        if (logRecord.getLogSource() == LogSource.LOCAL && logRecord.getLogType() != LogType.FLUSH) {
            ITransactionContext txnCtx = logRecord.getTxnCtx();
            if (txnCtx.getTxnState() == ITransactionManager.ABORTED && logRecord.getLogType() != LogType.ABORT) {
                throw new ACIDException(
                        "Aborted txn(" + txnCtx.getTxnId() + ") tried to write non-abort type log record.");
            }
        }
        final int logSize = logRecord.getLogSize();
        ensureSpace(logSize);
        appendPage.append(logRecord, appendLSN.get());
        if (logRecord.getLogType() == LogType.FLUSH) {
            logRecord.setLSN(appendLSN.get());
        }
        if (logRecord.isMarker()) {
            logRecord.logAppended(appendLSN.get());
        }
        appendLSN.addAndGet(logSize);
    }

    private void ensureSpace(int logSize) {
        if (!fileHasSpace(logSize)) {
            ensureLastPageFlushed();
            prepareNextLogFile();
        }
        if (!appendPage.hasSpace(logSize)) {
            prepareNextPage(logSize);
        }
    }

    private boolean fileHasSpace(int logSize) {
        if (logSize > maxLogRecordSize) {
            throw new ACIDException("Maximum log record size of (" + maxLogRecordSize + ") exceeded");
        }
        /*
         * To eliminate the case where the modulo of the next appendLSN = 0 (the next
         * appendLSN = the first LSN of the next log file), we do not allow a log to be
         * written at the last offset of the current file.
         */
        return getLogFileOffset(appendLSN.get()) + logSize < logFileSize;
    }

    private void prepareNextPage(int logSize) {
        appendPage.setFull();
        initNewPage(logSize);
    }

    private void initNewPage(int logSize) {
        boolean largePage = logSize > logPageSize;
        // if a new large page will be allocated, we need to stash a normal sized page
        // since our queues have fixed capacity
        ensureAvailablePage(largePage);
        if (largePage) {
            // for now, alloc a new buffer for each large page
            // TODO: pool large pages??
            appendPage = new LogBuffer(txnSubsystem, logSize, flushLSN);
        } else {
            appendPage.reset();
        }
        appendPage.setFileChannel(appendChannel);
        flushQ.add(appendPage);
    }

    private void ensureAvailablePage(boolean stash) {
        final ILogBuffer currentPage = appendPage;
        appendPage = null;
        try {
            appendPage = emptyQ.take();
            if (stash) {
                stashQ.add(appendPage);
            }
        } catch (InterruptedException e) {
            appendPage = currentPage;
            Thread.currentThread().interrupt();
            throw new ACIDException(e);
        }
    }

    private void prepareNextLogFile() {
        final long nextFileBeginLsn = getNextFileFirstLsn();
        try {
            closeCurrentLogFile();
            createNextLogFile();
            InvokeUtil.doIoUninterruptibly(() -> setLogPosition(nextFileBeginLsn));
            // move appendLSN and flushLSN to the first LSN of the next log file
            // only after the file was created and the channel was positioned successfully
            appendLSN.set(nextFileBeginLsn);
            flushLSN.set(nextFileBeginLsn);
            LOGGER.info("Created new txn log file with id({}) starting with LSN = {}", currentLogFileId,
                    nextFileBeginLsn);
        } catch (IOException e) {
            throw new ACIDException(e);
        }
    }

    private long getNextFileFirstLsn() {
        // add the remaining space in the current file
        return appendLSN.get() + (logFileSize - getLogFileOffset(appendLSN.get()));
    }

    private void ensureLastPageFlushed() {
        // Make sure to flush whatever left in the log tail.
        appendPage.setFull();
        synchronized (flushLSN) {
            while (flushLSN.get() != appendLSN.get()) {
                // notification will come from LogBuffer.internalFlush(.)
                try {
                    flushLSN.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ACIDException(e);
                }
            }
        }
    }

    @Override
    public ILogReader getLogReader(boolean isRecoveryMode) {
        return new LogReader(this, logFileSize, logPageSize, flushLSN, isRecoveryMode);
    }

    public LogManagerProperties getLogManagerProperties() {
        return logManagerProperties;
    }

    public ITransactionSubsystem getTransactionSubsystem() {
        return txnSubsystem;
    }

    @Override
    public long getAppendLSN() {
        return appendLSN.get();
    }

    @Override
    public void start() {
        // no op
    }

    @Override
    public void stop(boolean dumpState, OutputStream os) {
        terminateLogFlusher();
        closeCurrentLogFile();
        if (dumpState) {
            dumpState(os);
        }
    }

    @Override
    public void dumpState(OutputStream os) {
        // #. dump Configurable Variables
        dumpConfVars(os);

        // #. dump LSNInfo
        dumpLSNInfo(os);
    }

    private void dumpConfVars(OutputStream os) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\n>>dump_begin\t>>----- [ConfVars] -----");
            sb.append(logManagerProperties.toString());
            sb.append("\n>>dump_end\t>>----- [ConfVars] -----\n");
            os.write(sb.toString().getBytes());
        } catch (Exception e) {
            // ignore exception and continue dumping as much as possible.
            if (IS_DEBUG_MODE) {
                e.printStackTrace();
            }
        }
    }

    private void dumpLSNInfo(OutputStream os) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\n>>dump_begin\t>>----- [LSNInfo] -----");
            sb.append("\nappendLsn: " + appendLSN);
            sb.append("\nflushLsn: " + flushLSN.get());
            sb.append("\n>>dump_end\t>>----- [LSNInfo] -----\n");
            os.write(sb.toString().getBytes());
        } catch (Exception e) {
            // ignore exception and continue dumping as much as possible.
            if (IS_DEBUG_MODE) {
                e.printStackTrace();
            }
        }
    }

    private long initializeLogAnchor(long nextLogFileId) {
        long fileId = 0;
        long offset = 0;
        File fileLogDir = new File(logDir);
        try {
            if (fileLogDir.exists()) {
                List<Long> logFileIds = getLogFileIds();
                if (logFileIds == null) {
                    fileId = nextLogFileId;
                    createFileIfNotExists(getLogFilePath(fileId));
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("created a log file: " + getLogFilePath(fileId));
                    }
                } else {
                    fileId = logFileIds.get(logFileIds.size() - 1);
                    File logFile = new File(getLogFilePath(fileId));
                    offset = logFile.length();
                }
            } else {
                fileId = nextLogFileId;
                createNewDirectory(logDir);
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("created the log directory: " + logManagerProperties.getLogDir());
                }
                createFileIfNotExists(getLogFilePath(fileId));
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("created a log file: " + getLogFilePath(fileId));
                }
            }
        } catch (IOException ioe) {
            throw new IllegalStateException("Failed to initialize the log anchor", ioe);
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("log file Id: " + fileId + ", offset: " + offset);
        }
        return logFileSize * fileId + offset;
    }

    @Override
    public void renewLogFiles() {
        terminateLogFlusher();
        closeCurrentLogFile();
        long lastMaxLogFileId = deleteAllLogFiles();
        initializeLogManager(lastMaxLogFileId + 1);
    }

    @Override
    public void deleteOldLogFiles(long checkpointLSN) {
        Long checkpointLSNLogFileID = getLogFileId(checkpointLSN);
        List<Long> logFileIds = getLogFileIds();
        if (logFileIds != null) {
            //sort log files from oldest to newest
            Collections.sort(logFileIds);
            /**
             * At this point, any future LogReader should read from LSN >= checkpointLSN
             */
            synchronized (txnLogFileId2ReaderCount) {
                for (Long id : logFileIds) {
                    /**
                     * Stop deletion if:
                     * The log file which contains the checkpointLSN has been reached.
                     * The oldest log file being accessed by a LogReader has been reached.
                     */
                    if (id >= checkpointLSNLogFileID
                            || (txnLogFileId2ReaderCount.containsKey(id) && txnLogFileId2ReaderCount.get(id) > 0)) {
                        break;
                    }

                    //delete old log file
                    File file = new File(getLogFilePath(id));
                    file.delete();
                    txnLogFileId2ReaderCount.remove(id);
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("Deleted log file " + file.getAbsolutePath());
                    }
                }
            }
        }
    }

    private void terminateLogFlusher() {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Terminating LogFlusher thread ...");
        }
        logFlusher.terminate();
        try {
            futureLogFlusher.get();
        } catch (ExecutionException | InterruptedException e) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("---------- warning(begin): LogFlusher thread is terminated abnormally --------");
                e.printStackTrace();
                LOGGER.info("---------- warning(end)  : LogFlusher thread is terminated abnormally --------");
            }
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("LogFlusher thread is terminated.");
        }
    }

    private long deleteAllLogFiles() {
        txnLogFileId2ReaderCount.clear();
        List<Long> logFileIds = getLogFileIds();
        if (logFileIds != null) {
            for (Long id : logFileIds) {
                File file = new File(getLogFilePath(id));
                if (!file.delete()) {
                    throw new IllegalStateException("Failed to delete a file: " + file.getAbsolutePath());
                }
            }
            return logFileIds.get(logFileIds.size() - 1);
        } else {
            throw new IllegalStateException("Couldn't find any log files.");
        }
    }

    public List<Long> getLogFileIds() {
        File fileLogDir = new File(logDir);
        String[] logFileNames = null;
        List<Long> logFileIds = null;
        if (fileLogDir.exists()) {
            logFileNames = fileLogDir.list(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    if (name.startsWith(logFilePrefix)) {
                        return true;
                    }
                    return false;
                }
            });
            if (logFileNames != null && logFileNames.length != 0) {
                logFileIds = new ArrayList<>();
                for (String fileName : logFileNames) {
                    logFileIds.add(Long.parseLong(fileName.substring(logFilePrefix.length() + 1)));
                }
                Collections.sort(logFileIds, new Comparator<Long>() {
                    @Override
                    public int compare(Long arg0, Long arg1) {
                        return arg0.compareTo(arg1);
                    }
                });
            }
        }
        return logFileIds;
    }

    private String getLogFilePath(long fileId) {
        return logDir + File.separator + logFilePrefix + "_" + fileId;
    }

    private long getLogFileOffset(long lsn) {
        return lsn % logFileSize;
    }

    public long getLogFileId(long lsn) {
        return lsn / logFileSize;
    }

    private static boolean createFileIfNotExists(String path) throws IOException {
        File file = new File(path);
        File parentFile = file.getParentFile();
        if (parentFile != null) {
            parentFile.mkdirs();
        }
        return file.createNewFile();
    }

    private static boolean createNewDirectory(String path) {
        return (new File(path)).mkdir();
    }

    private void createNextLogFile() throws IOException {
        final long nextFileBeginLsn = getNextFileFirstLsn();
        final long fileId = getLogFileId(nextFileBeginLsn);
        final Path nextFilePath = Paths.get(getLogFilePath(fileId));
        if (nextFilePath.toFile().exists()) {
            LOGGER.warn("Ignored create log file {} since file already exists", nextFilePath.toString());
            return;
        }
        Files.createFile(nextFilePath);
    }

    private void setLogPosition(long lsn) throws IOException {
        final long fileId = getLogFileId(lsn);
        final Path targetFilePath = Paths.get(getLogFilePath(fileId));
        final long targetPosition = getLogFileOffset(lsn);
        final RandomAccessFile raf = new RandomAccessFile(targetFilePath.toFile(), "rw"); // NOSONAR closed when full
        appendChannel = raf.getChannel();
        appendChannel.position(targetPosition);
        currentLogFileId = fileId;
    }

    private void closeCurrentLogFile() {
        if (appendChannel != null && appendChannel.isOpen()) {
            try {
                LOGGER.info("closing current log file with id({})", currentLogFileId);
                appendChannel.close();
            } catch (IOException e) {
                LOGGER.error(() -> "failed to close log file with id(" + currentLogFileId + ")", e);
                throw new ACIDException(e);
            }
        }
    }

    @Override
    public long getReadableSmallestLSN() {
        List<Long> logFileIds = getLogFileIds();
        if (logFileIds != null) {
            return logFileIds.get(0) * logFileSize;
        } else {
            throw new IllegalStateException("Couldn't find any log files.");
        }
    }

    @Override
    public String getNodeId() {
        return nodeId;
    }

    @Override
    public int getLogPageSize() {
        return logPageSize;
    }

    @Override
    public void setReplicationManager(IReplicationManager replicationManager) {
        throw new IllegalStateException("This log manager does not support replication");
    }

    @Override
    public int getNumLogPages() {
        return numLogPages;
    }

    @Override
    public TxnLogFile getLogFile(long LSN) throws IOException {
        long fileId = getLogFileId(LSN);
        String logFilePath = getLogFilePath(fileId);
        File file = new File(logFilePath);
        if (!file.exists()) {
            throw new IOException("Log file with id(" + fileId + ") was not found. Requested LSN: " + LSN);
        }
        RandomAccessFile raf = new RandomAccessFile(new File(logFilePath), "r");
        FileChannel newFileChannel = raf.getChannel();
        TxnLogFile logFile = new TxnLogFile(this, newFileChannel, fileId, fileId * logFileSize);
        touchLogFile(fileId);
        return logFile;
    }

    @Override
    public void closeLogFile(TxnLogFile logFileRef, FileChannel fileChannel) throws IOException {
        if (!fileChannel.isOpen()) {
            LOGGER.warn(() -> "Closing log file with id(" + logFileRef.getLogFileId() + ") with a closed channel.");
        }
        fileChannel.close();
        untouchLogFile(logFileRef.getLogFileId());
    }

    private void touchLogFile(long fileId) {
        synchronized (txnLogFileId2ReaderCount) {
            if (txnLogFileId2ReaderCount.containsKey(fileId)) {
                txnLogFileId2ReaderCount.put(fileId, txnLogFileId2ReaderCount.get(fileId) + 1);
            } else {
                txnLogFileId2ReaderCount.put(fileId, 1);
            }
        }
    }

    private void untouchLogFile(long fileId) {
        synchronized (txnLogFileId2ReaderCount) {
            if (txnLogFileId2ReaderCount.containsKey(fileId)) {
                int newReaderCount = txnLogFileId2ReaderCount.get(fileId) - 1;
                if (newReaderCount < 0) {
                    throw new IllegalStateException(
                            "Invalid log file reader count (ID=" + fileId + ", count: " + newReaderCount + ")");
                }
                txnLogFileId2ReaderCount.put(fileId, newReaderCount);
            } else {
                throw new IllegalStateException("Trying to close log file id(" + fileId + ") which was not opened.");
            }
        }
    }

    /**
     * This class is used to log FLUSH logs.
     * FLUSH logs are flushed on a different thread to avoid a possible deadlock in {@link LogBuffer} batchUnlock
     * which calls {@link org.apache.asterix.common.context.PrimaryIndexOperationTracker} completeOperation. The
     * deadlock happens when completeOperation generates a FLUSH log and there are no empty log buffers available
     * to log it.
     */
    private class FlushLogsLogger implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    final ILogRecord logRecord = flushLogsQ.take();
                    appendToLogTail(logRecord);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}

class LogFlusher implements Callable<Boolean> {
    private static final Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger();
    private static final ILogBuffer POISON_PILL = new LogBuffer(null, ILogRecord.JOB_TERMINATE_LOG_SIZE, null);
    private final LogManager logMgr;//for debugging
    private final LinkedBlockingQueue<ILogBuffer> emptyQ;
    private final LinkedBlockingQueue<ILogBuffer> flushQ;
    private final LinkedBlockingQueue<ILogBuffer> stashQ;
    private volatile ILogBuffer flushPage;
    private volatile boolean stopping;
    private final Semaphore started;

    LogFlusher(LogManager logMgr, LinkedBlockingQueue<ILogBuffer> emptyQ, LinkedBlockingQueue<ILogBuffer> flushQ,
            LinkedBlockingQueue<ILogBuffer> stashQ) {
        this.logMgr = logMgr;
        this.emptyQ = emptyQ;
        this.flushQ = flushQ;
        this.stashQ = stashQ;
        this.started = new Semaphore(0);
    }

    public void terminate() {
        // make sure the LogFlusher thread started before terminating it.
        InvokeUtil.doUninterruptibly(started::acquire);

        stopping = true;

        // we must tell any active flush, if any, to stop
        final ILogBuffer currentFlushPage = this.flushPage;
        if (currentFlushPage != null) {
            currentFlushPage.stop();
        }
        // finally we put a POISON_PILL onto the flushQ to indicate to the flusher it is time to exit
        InvokeUtil.doUninterruptibly(() -> flushQ.put(POISON_PILL));
    }

    @Override
    public Boolean call() {
        started.release();
        boolean interrupted = false;
        try {
            while (true) {
                flushPage = null;
                interrupted = InvokeUtil.doUninterruptiblyGet(() -> flushPage = flushQ.take()) || interrupted;
                if (flushPage == POISON_PILL) {
                    return true;
                }
                flushPage.flush(stopping);

                // TODO(mblow): recycle large pages
                emptyQ.add(flushPage.getLogPageSize() == logMgr.getLogPageSize() ? flushPage : stashQ.remove());
            }
        } catch (Exception e) {
            LOGGER.log(Level.ERROR, "LogFlusher is terminating abnormally. System is in unusable state.", e);
            throw e;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
