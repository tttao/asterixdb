/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.asterix.transaction.management.service.locking;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import edu.uci.ics.asterix.common.config.AsterixTransactionProperties;
import edu.uci.ics.asterix.common.exceptions.ACIDException;
import edu.uci.ics.asterix.common.transactions.DatasetId;
import edu.uci.ics.asterix.common.transactions.ILockManager;
import edu.uci.ics.asterix.common.transactions.ITransactionContext;
import edu.uci.ics.asterix.common.transactions.ITransactionManager;
import edu.uci.ics.asterix.transaction.management.service.transaction.TransactionManagementConstants.LockManagerConstants.LockMode;
import edu.uci.ics.asterix.transaction.management.service.transaction.TransactionSubsystem;
import edu.uci.ics.hyracks.api.lifecycle.ILifeCycleComponent;

/**
 * An implementation of the ILockManager interface for the
 * specific case of locking protocol with two lock modes: (S) and (X),
 * where S lock mode is shown by 0, and X lock mode is shown by 1.
 * 
 * @author tillw
 */

public class ConcurrentLockManager implements ILockManager, ILifeCycleComponent {

    public static final boolean IS_DEBUG_MODE = false;//true

    private TransactionSubsystem txnSubsystem;

    private ResourceGroupTable table;
    private ResourceArenaManager resArenaMgr;
    private RequestArenaManager reqArenaMgr;
    private JobArenaManager jobArenaMgr;
    private ConcurrentHashMap<Integer, Long> jobIdSlotMap;
    private ThreadLocal<DatasetLockCache> dsLockCache;
        
    enum LockAction {
        GET,
        UPD, // special version of GET that updates the max lock mode
        WAIT,
        CONV // convert (upgrade) a lock (e.g. from S to X)
    }
    
    static LockAction[][] ACTION_MATRIX = {
        // new    NL              IS               IX                S                X
        { LockAction.GET, LockAction.UPD,  LockAction.UPD,  LockAction.UPD,  LockAction.UPD  }, // NL
        { LockAction.GET, LockAction.GET,  LockAction.UPD,  LockAction.UPD,  LockAction.WAIT }, // IS
        { LockAction.GET, LockAction.GET,  LockAction.GET,  LockAction.WAIT, LockAction.WAIT }, // IX
        { LockAction.GET, LockAction.GET,  LockAction.WAIT, LockAction.GET,  LockAction.WAIT }, // S
        { LockAction.GET, LockAction.WAIT, LockAction.WAIT, LockAction.WAIT, LockAction.WAIT }  // X
    };
        
    public ConcurrentLockManager(TransactionSubsystem txnSubsystem) throws ACIDException {
        this.txnSubsystem = txnSubsystem;
        
        this.table = new ResourceGroupTable();
        
        final int lockManagerShrinkTimer = txnSubsystem.getTransactionProperties()
                .getLockManagerShrinkTimer();

        resArenaMgr = new ResourceArenaManager(lockManagerShrinkTimer);
        reqArenaMgr = new RequestArenaManager(lockManagerShrinkTimer);
        jobArenaMgr = new JobArenaManager(lockManagerShrinkTimer);
        jobIdSlotMap = new ConcurrentHashMap<>();
        dsLockCache = new ThreadLocal<DatasetLockCache>() {
            protected DatasetLockCache initialValue() {
                return new DatasetLockCache();
            }
        };
    }

    public AsterixTransactionProperties getTransactionProperties() {
        return this.txnSubsystem.getTransactionProperties();
    }

    @Override
    public void lock(DatasetId datasetId, int entityHashValue, byte lockMode, ITransactionContext txnContext)
            throws ACIDException {
        
        log("lock", datasetId.getId(), entityHashValue, lockMode, txnContext);
        
        final int dsId = datasetId.getId();        
        final int jobId = txnContext.getJobId().getId();
        
        if (entityHashValue != -1) {
            // get the intention lock on the dataset, if we want to lock an individual item
            final byte dsLockMode = lockMode == LockMode.X ? LockMode.IX : LockMode.IS;
            if (! dsLockCache.get().contains(jobId, dsId, dsLockMode)) {
                lock(datasetId, -1, dsLockMode, txnContext);
                dsLockCache.get().put(jobId, dsId, dsLockMode);
            }
        }

        long jobSlot = findOrAllocJobSlot(jobId);
        
        ResourceGroup group = table.get(datasetId, entityHashValue);
        group.getLatch();

        try {
            validateJob(txnContext);
            
            // 1) Find the resource in the hash table
            long resSlot = findOrAllocResourceSlot(group, dsId, entityHashValue);
            // 2) create a request entry
            long reqSlot = allocRequestSlot(resSlot, jobSlot, lockMode);
            // 3) check lock compatibility
            boolean locked = false;

            while (! locked) {
                int curLockMode = resArenaMgr.getMaxMode(resSlot);
                LockAction act = ACTION_MATRIX[curLockMode][lockMode];
                if (act == LockAction.WAIT) {
                    act = updateActionForSameJob(resSlot, jobSlot, lockMode);
                }
                switch (act) {
                    case UPD:
                        resArenaMgr.setMaxMode(resSlot, lockMode);
                        // no break
                    case GET:
                        addHolder(reqSlot, resSlot, jobSlot);
                        locked = true;
                        break;
                    case WAIT:
                        if (! introducesDeadlock(resSlot, jobSlot)) {
                            addWaiter(reqSlot, resSlot, jobSlot);
                        } else {
                            requestAbort(txnContext);
                        }
                        group.await(txnContext);
                        removeWaiter(reqSlot, resSlot, jobSlot);
                        break;
                    case CONV:
                        // TODO can we have more than on upgrader? Or do we need to
                        // abort if we get a second upgrader?
                        addUpgrader(reqSlot, resSlot, jobSlot);
                        group.await(txnContext);
                        removeUpgrader(reqSlot, resSlot, jobSlot);
                        break;                        
                    default:
                        throw new IllegalStateException();
                }
            }
        } finally {
            group.releaseLatch();
        }
    }

    /**
     * determine if adding a job to the waiters of a resource will introduce a 
     * cycle in the wait-graph where the job waits on itself
     * @param resSlot the slot that contains the information about the resource
     * @param jobSlot the slot that contains the information about the job
     * @return true if a cycle would be introduced, false otherwise
     */
    private boolean introducesDeadlock(long resSlot, long jobSlot) {
        long reqSlot = resArenaMgr.getLastHolder(resSlot);
        while (reqSlot >= 0) {
            long holderJobSlot = reqArenaMgr.getJobSlot(reqSlot);
            if (holderJobSlot == jobSlot) {
                return true;
            }
            long waiter = jobArenaMgr.getLastWaiter(holderJobSlot);
            while (waiter >= 0) {
                long watingOnResSlot = reqArenaMgr.getResourceId(waiter);
                if (introducesDeadlock(watingOnResSlot, jobSlot)) {
                    return true;
                }
                waiter = reqArenaMgr.getNextJobRequest(waiter);
            }
            reqSlot = reqArenaMgr.getNextRequest(reqSlot);
        }
        return false;
    }

    @Override
    public void instantLock(DatasetId datasetId, int entityHashValue, byte lockMode, ITransactionContext txnContext)
            throws ACIDException {
        log("instantLock", datasetId.getId(), entityHashValue, lockMode, txnContext);

        lock(datasetId, entityHashValue, lockMode, txnContext);
        unlock(datasetId, entityHashValue, txnContext);
    }

    @Override
    public boolean tryLock(DatasetId datasetId, int entityHashValue, byte lockMode, ITransactionContext txnContext)
            throws ACIDException {
        log("tryLock", datasetId.getId(), entityHashValue, lockMode, txnContext);
        
        final int dsId = datasetId.getId();
        final int jobId = txnContext.getJobId().getId();

        if (entityHashValue != -1) {
            // get the intention lock on the dataset, if we want to lock an individual item
            byte dsLockMode = lockMode == LockMode.X ? LockMode.IX : LockMode.IS;
            if (! dsLockCache.get().contains(jobId, dsId, dsLockMode)) {
                if (! tryLock(datasetId, -1, dsLockMode, txnContext)) {
                    return false;
                }
                dsLockCache.get().put(jobId, dsId, dsLockMode);
            }
        }

        long jobSlot = findOrAllocJobSlot(jobId);
        
        boolean locked = false;

        ResourceGroup group = table.get(datasetId, entityHashValue);
        group.getLatch();

        try {
            validateJob(txnContext);

            // 1) Find the resource in the hash table
            long resSlot = findOrAllocResourceSlot(group, dsId, entityHashValue);
            // 2) create a request entry
            long reqSlot = allocRequestSlot(resSlot, jobSlot, lockMode);
            // 3) check lock compatibility
            
            int curLockMode = resArenaMgr.getMaxMode(resSlot);
            LockAction act = ACTION_MATRIX[curLockMode][lockMode];
            if (act == LockAction.WAIT) {
                act = updateActionForSameJob(resSlot, jobSlot, lockMode);
            }
            switch (act) {
                case UPD:
                    resArenaMgr.setMaxMode(resSlot, lockMode);
                    // no break
                case GET:
                    addHolder(reqSlot, resSlot, jobSlot);
                    locked = true;
                    break;
                case WAIT:
                case CONV:
                    locked = false;
                    break;
                default:
                    throw new IllegalStateException();
            }
            // TODO where do we check for deadlocks?
        } finally {
            group.releaseLatch();
        }
        
        // if we did acquire the dataset lock, but not the entity lock, we keep
        // it anyway and clean it up at the end of the job
        
        return locked;
    }

    @Override
    public boolean instantTryLock(DatasetId datasetId, int entityHashValue, byte lockMode,
            ITransactionContext txnContext) throws ACIDException {
        log("instantTryLock", datasetId.getId(), entityHashValue, lockMode, txnContext);
        
        if (tryLock(datasetId, entityHashValue, lockMode, txnContext)) {
            unlock(datasetId, entityHashValue, txnContext);
            return true;
        }
        return false;
    }

    @Override
    public void unlock(DatasetId datasetId, int entityHashValue, ITransactionContext txnContext) throws ACIDException {
        log("unlock", datasetId.getId(), entityHashValue, LockMode.NL, txnContext);

        ResourceGroup group = table.get(datasetId, entityHashValue);
        group.getLatch();

        try {

            int dsId = datasetId.getId();
            long resource = findResourceInGroup(group, dsId, entityHashValue);

            if (resource < 0) {
                throw new IllegalStateException("resource (" + dsId + ",  " + entityHashValue + ") not found");
            }

            int jobId = txnContext.getJobId().getId();
            long jobSlot = findOrAllocJobSlot(jobId);

            // since locking is properly nested, finding the last holder for a job is good enough        
            long holder = removeLastHolder(resource, jobSlot);

            // deallocate request
            reqArenaMgr.deallocate(holder);
            // deallocate resource or fix max lock mode
            if (resourceNotUsed(resource)) {
                long prev = group.firstResourceIndex.get();
                if (prev == resource) {
                    group.firstResourceIndex.set(resArenaMgr.getNext(resource));
                } else {
                    while (resArenaMgr.getNext(prev) != resource) {
                        prev = resArenaMgr.getNext(prev);
                    }
                    resArenaMgr.setNext(prev, resArenaMgr.getNext(resource));
                }
                resArenaMgr.deallocate(resource);
            } else {
                final int oldMaxMode = resArenaMgr.getMaxMode(resource);
                final int newMaxMode = determineNewMaxMode(resource, oldMaxMode);
                resArenaMgr.setMaxMode(resource, newMaxMode);
                if (oldMaxMode != newMaxMode) {
                    // the locking mode didn't change, current waiters won't be
                    // able to acquire the lock, so we do not need to signal them
                    group.wakeUp();
                }
            }
        } finally {
            group.releaseLatch();
        }

        // dataset intention locks are cleaned up at the end of the job
    }
    
    @Override
    public void releaseLocks(ITransactionContext txnContext) throws ACIDException {
        log("releaseLocks", -1, -1, LockMode.NL, txnContext);

        int jobId = txnContext.getJobId().getId();
        Long jobSlot = jobIdSlotMap.get(jobId);
        if (jobSlot == null) {
            // we don't know the job, so there are no locks for it - we're done
            return;
        }
        long holder = jobArenaMgr.getLastHolder(jobSlot);
        while (holder != -1) {
            long resource = reqArenaMgr.getResourceId(holder);
            int dsId = resArenaMgr.getDatasetId(resource);
            int pkHashVal = resArenaMgr.getPkHashVal(resource);
            unlock(new DatasetId(dsId), pkHashVal, txnContext);
            holder = jobArenaMgr.getLastHolder(jobSlot);
        }
        jobArenaMgr.deallocate(jobSlot);
        
        //System.err.println(table.append(new StringBuilder(), true).toString());
        
        //System.out.println("jobArenaMgr " + jobArenaMgr.addTo(new Stats()).toString());
        //System.out.println("resArenaMgr " + resArenaMgr.addTo(new Stats()).toString());
        //System.out.println("reqArenaMgr " + reqArenaMgr.addTo(new Stats()).toString());
    }
        
    private long findOrAllocJobSlot(int jobId) {
        Long jobSlot = jobIdSlotMap.get(jobId);
        if (jobSlot == null) {
            jobSlot = new Long(jobArenaMgr.allocate());
            jobArenaMgr.setJobId(jobSlot, jobId);
            Long oldSlot = jobIdSlotMap.putIfAbsent(jobId, jobSlot);
            if (oldSlot != null) {
                // if another thread allocated a slot for this jobId between
                // get(..) and putIfAbsent(..), we'll use that slot and
                // deallocate the one we allocated
                jobArenaMgr.deallocate(jobSlot);
                jobSlot = oldSlot;
            }
        }
        assert(jobSlot >= 0);
        return jobSlot;
    }

    private long findOrAllocResourceSlot(ResourceGroup group, int dsId, int entityHashValue) {
        long resSlot = findResourceInGroup(group, dsId, entityHashValue);
        
        if (resSlot == -1) {
            // we don't know about this resource, let's alloc a slot
            resSlot = resArenaMgr.allocate();
            resArenaMgr.setDatasetId(resSlot, dsId);
            resArenaMgr.setPkHashVal(resSlot, entityHashValue);
            resArenaMgr.setNext(resSlot, group.firstResourceIndex.get());
            group.firstResourceIndex.set(resSlot);
        }
        return resSlot;
    }

    private long allocRequestSlot(long resSlot, long jobSlot, byte lockMode) {
        long reqSlot = reqArenaMgr.allocate();
        reqArenaMgr.setResourceId(reqSlot, resSlot);
        reqArenaMgr.setLockMode(reqSlot, lockMode); // lock mode is a byte!!
        reqArenaMgr.setJobSlot(reqSlot, jobSlot);
        return reqSlot;
    }

    /**
     * when we've got a lock conflict for a different job, we always have to
     * wait, if it is for the same job we either have to
     * a) (wait and) convert the lock once conversion becomes viable or
     * b) acquire the lock if we want to lock the same resource with the same 
     * lock mode for the same job.
     * @param resource the resource slot that's being locked
     * @param job the job slot of the job locking the resource
     * @param lockMode the lock mode that the resource should be locked with
     * @return
     */
    private LockAction updateActionForSameJob(long resource, long job, byte lockMode) {
        // TODO we can reduce the number of things we have to look at by
        // carefully distinguishing the different lock modes
        long holder = resArenaMgr.getLastHolder(resource);
        LockAction res = LockAction.WAIT;
        while (holder != -1) {
            if (job == reqArenaMgr.getJobSlot(holder)) {
                if (reqArenaMgr.getLockMode(holder) == lockMode) {
                    return LockAction.GET;
                } else {
                    res = LockAction.CONV;
                }
            }
            holder = reqArenaMgr.getNextRequest(holder);
        }
        return res;
    }
    
    private long findResourceInGroup(ResourceGroup group, int dsId, int entityHashValue) {
        long resSlot = group.firstResourceIndex.get();
        while (resSlot != -1) {
            // either we already have a lock on this resource or we have a 
            // hash collision
            if (resArenaMgr.getDatasetId(resSlot) == dsId && 
                    resArenaMgr.getPkHashVal(resSlot) == entityHashValue) {
                return resSlot;
            } else {
                resSlot = resArenaMgr.getNext(resSlot);
            }
        }
        return -1;        
    }
    
    private void addHolder(long request, long resource, long job) {
        long lastHolder = resArenaMgr.getLastHolder(resource);
        reqArenaMgr.setNextRequest(request, lastHolder);
        resArenaMgr.setLastHolder(resource, request);
        
        synchronized (jobArenaMgr) {
            long lastJobHolder = jobArenaMgr.getLastHolder(job);
            insertIntoJobQueue(request, lastJobHolder);
            jobArenaMgr.setLastHolder(job, request);
        }
    }
    
    private long removeLastHolder(long resource, long jobSlot) {
        long holder = resArenaMgr.getLastHolder(resource);
        if (holder < 0) {
            throw new IllegalStateException("no holder for resource " + resource);
        }
        
        // remove from the list of holders for a resource
        if (reqArenaMgr.getJobSlot(holder) == jobSlot) {
            // if the head of the queue matches, we need to update the resource
            long next = reqArenaMgr.getNextRequest(holder);
            resArenaMgr.setLastHolder(resource, next);
        } else {
            holder = removeRequestFromQueueForJob(holder, jobSlot);
        }
        
        synchronized (jobArenaMgr) {
            // remove from the list of requests for a job
            long newHead = removeRequestFromJob(jobSlot, holder);
            jobArenaMgr.setLastHolder(jobSlot, newHead);            
        }
        return holder;
    }

    private long removeRequestFromJob(long jobSlot, long holder) {
        long prevForJob = reqArenaMgr.getPrevJobRequest(holder);
        long nextForJob = reqArenaMgr.getNextJobRequest(holder);
        if (nextForJob != -1) {
            reqArenaMgr.setPrevJobRequest(nextForJob, prevForJob);
        }
        if (prevForJob == -1) {
            return nextForJob;
        } else {
            reqArenaMgr.setNextJobRequest(prevForJob, nextForJob);
            return -1;
        }
    }

    private void addWaiter(long request, long resource, long job) {
        long waiter = resArenaMgr.getFirstWaiter(resource);
        reqArenaMgr.setNextRequest(request, -1);
        if (waiter == -1) {
            resArenaMgr.setFirstWaiter(resource, request);
        } else {
            appendToRequestQueue(waiter, request);
        }
        
        synchronized (jobArenaMgr) {
            waiter = jobArenaMgr.getLastWaiter(job);
            insertIntoJobQueue(request, waiter);
            jobArenaMgr.setLastWaiter(job, request);
        }
    }

    private void removeWaiter(long request, long resource, long job) {
        long waiter = resArenaMgr.getFirstWaiter(resource);
        if (waiter == request) {
            long next = reqArenaMgr.getNextRequest(waiter);
            resArenaMgr.setFirstWaiter(resource, next);
        } else {
            waiter = removeRequestFromQueueForSlot(waiter, request);
        }
        synchronized (jobArenaMgr) {
            // remove from the list of requests for a job
            long newHead = removeRequestFromJob(job, waiter);
            jobArenaMgr.setLastWaiter(job, newHead);            
        }
    }

    private void addUpgrader(long request, long resource, long job) {
        long upgrader = resArenaMgr.getFirstUpgrader(resource);
        reqArenaMgr.setNextRequest(request, -1);
        if (upgrader == -1) {
            resArenaMgr.setFirstUpgrader(resource, request);
        } else {
            appendToRequestQueue(upgrader, request);
        }
        synchronized (jobArenaMgr) {
            upgrader = jobArenaMgr.getLastUpgrader(job);
            insertIntoJobQueue(request, upgrader);
            jobArenaMgr.setLastUpgrader(job, request);            
        }
    }

    private void removeUpgrader(long request, long resource, long job) {
        long upgrader = resArenaMgr.getFirstUpgrader(resource);
        if (upgrader == request) {
            long next = reqArenaMgr.getNextRequest(upgrader);
            resArenaMgr.setFirstUpgrader(resource, next);
        } else {
            upgrader = removeRequestFromQueueForSlot(upgrader, request);
        }
        synchronized (jobArenaMgr) {
            // remove from the list of requests for a job
            long newHead = removeRequestFromJob(job, upgrader);
            jobArenaMgr.setLastUpgrader(job, newHead);            
        }
    }

    private void insertIntoJobQueue(long newRequest, long oldRequest) {
        reqArenaMgr.setNextJobRequest(newRequest, oldRequest);
        reqArenaMgr.setPrevJobRequest(newRequest, -1);
        if (oldRequest >= 0) {
            reqArenaMgr.setPrevJobRequest(oldRequest, newRequest);
        }
    }

    private void appendToRequestQueue(long head, long appendee) {
        long next = reqArenaMgr.getNextRequest(head);
        while(next != -1) {
            head = next;
            next = reqArenaMgr.getNextRequest(head);
        }
        reqArenaMgr.setNextRequest(head, appendee);        
    }
        
    /**
     * 
     * @param head
     * @param reqSlot
     * @return
     */
    private long removeRequestFromQueueForSlot(long head, long reqSlot) {
        long cur = head;
        long prev = cur;
        while (prev != -1) {
            cur = reqArenaMgr.getNextRequest(prev);
            if (cur == -1) {
                throw new IllegalStateException("request " + reqSlot+ " not in queue");
            }
            if (cur == reqSlot) {
                break;
            }
            prev = cur;
        }
        long next = reqArenaMgr.getNextRequest(cur);
        reqArenaMgr.setNextRequest(prev, next);
        return cur;        
    }
    
    /**
     * remove the first request for a given job from a request queue
     * @param head the head of the request queue
     * @param jobSlot the job slot
     * @return the slot of the first request that matched the given job
     */
    private long removeRequestFromQueueForJob(long head, long jobSlot) {
        long holder = head;
        long prev = holder;
        while (prev != -1) {
            holder = reqArenaMgr.getNextRequest(prev);
            if (holder == -1) {
                throw new IllegalStateException("no entry for job " + jobSlot + " in queue");
            }
            if (jobSlot == reqArenaMgr.getJobSlot(holder)) {
                break;
            }
            prev = holder;
        }
        long next = reqArenaMgr.getNextRequest(holder);
        reqArenaMgr.setNextRequest(prev, next);
        return holder;
    }
    
    private int determineNewMaxMode(long resource, int oldMaxMode) {
        int newMaxMode = LockMode.NL;
        long holder = resArenaMgr.getLastHolder(resource);
        while (holder != -1) {
            int curLockMode = reqArenaMgr.getLockMode(holder);
            if (curLockMode == oldMaxMode) {
                // we have another lock of the same mode - we're done
                return oldMaxMode;
            }
            switch (ACTION_MATRIX[newMaxMode][curLockMode]) {
                case UPD:
                    newMaxMode = curLockMode;
                    break;
                case GET:
                    break;
                case WAIT:
                    throw new IllegalStateException("incompatible locks in holder queue");
            }
            holder = reqArenaMgr.getNextRequest(holder);
        }
        return newMaxMode;        
    }
    
    private boolean resourceNotUsed(long resource) {
        return resArenaMgr.getLastHolder(resource) == -1
                && resArenaMgr.getFirstUpgrader(resource) == -1
                && resArenaMgr.getFirstWaiter(resource) == -1;
    }

    private void log(String string, int id, int entityHashValue, byte lockMode, ITransactionContext txnContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("{ op : ").append(string);
        if (id != -1) {
            sb.append(" , dataset : ").append(id);
        }
        if (entityHashValue != -1) {
            sb.append(" , entity : ").append(entityHashValue);
        }
        if (lockMode != LockMode.NL) {
            sb.append(" , mode : ").append(LockMode.toString(lockMode));
        }
        if (txnContext != null) {
            sb.append(" , jobId : ").append(txnContext.getJobId());            
        }
        sb.append(" }");
        //System.err.println("XXX" + sb.toString());
    }

    private void validateJob(ITransactionContext txnContext) throws ACIDException {
        if (txnContext.getTxnState() == ITransactionManager.ABORTED) {
            throw new ACIDException("" + txnContext.getJobId() + " is in ABORTED state.");
        } else if (txnContext.isTimeout()) {
            requestAbort(txnContext);
        }
    }

    private void requestAbort(ITransactionContext txnContext) throws ACIDException {
        txnContext.setTimeout(true);
        throw new ACIDException("Transaction " + txnContext.getJobId()
                + " should abort (requested by the Lock Manager)");
    }

    public StringBuilder append(StringBuilder sb) {
        sb.append(">>dump_begin\t>>----- [resTable] -----\n");
        table.append(sb);
        sb.append(">>dump_end\t>>----- [resTable] -----\n");

        sb.append(">>dump_begin\t>>----- [resArenaMgr] -----\n");
        resArenaMgr.append(sb);
        sb.append(">>dump_end\t>>----- [resArenaMgr] -----\n");
        
        sb.append(">>dump_begin\t>>----- [reqArenaMgr] -----\n");
        reqArenaMgr.append(sb);
        sb.append(">>dump_end\t>>----- [reqArenaMgr] -----\n");
        
        sb.append(">>dump_begin\t>>----- [jobIdSlotMap] -----\n");
        for(Integer i : jobIdSlotMap.keySet()) {
            sb.append(i).append(" : ");
            RecordManagerTypes.Global.append(sb, jobIdSlotMap.get(i));
            sb.append("\n");
        }
        sb.append(">>dump_end\t>>----- [jobIdSlotMap] -----\n");

        sb.append(">>dump_begin\t>>----- [jobArenaMgr] -----\n");
        jobArenaMgr.append(sb);
        sb.append(">>dump_end\t>>----- [jobArenaMgr] -----\n");

        return sb;
    }
    
    public String toString() {
        return append(new StringBuilder()).toString();
    }
    
    @Override
    public String prettyPrint() throws ACIDException {
        StringBuilder s = new StringBuilder("\n########### LockManager Status #############\n");
        return append(s).toString() + "\n";
    }

    @Override
    public void start() {
        //no op
    }

    @Override
    public void stop(boolean dumpState, OutputStream os) {
        if (dumpState) {
            try {
                os.write(toString().getBytes());
                os.flush();
            } catch (IOException e) {
                //ignore
            }
        }
    }
    
    private static class DatasetLockCache {
        private long jobId = -1;
        private HashMap<Integer,Byte> lockCache =  new HashMap<Integer,Byte>();
        
        public boolean contains(final int jobId, final int dsId, byte dsLockMode) {
            if (this.jobId == jobId) {
                final Byte cachedLockMode = this.lockCache.get(dsId);
                if (cachedLockMode != null && cachedLockMode == dsLockMode) {
                    return true;
                }            
            } else {
                this.jobId = -1;
                this.lockCache.clear();
            }
            return false;
        }
        
        public void put(final int jobId, final int dsId, byte dsLockMode) {
            this.jobId = jobId;
            this.lockCache.put(dsId, dsLockMode);
        }
        
        public String toString() {
            return "[ " + jobId + " : " + lockCache.toString() + "]";
        }
    }

    private static class ResourceGroupTable {
        public static final int TABLE_SIZE = 1024; // TODO increase?

        private ResourceGroup[] table;
        
        public ResourceGroupTable() {
            table = new ResourceGroup[TABLE_SIZE];
            for (int i = 0; i < TABLE_SIZE; ++i) {
                table[i] = new ResourceGroup();
            }
        }
        
        ResourceGroup get(DatasetId dId, int entityHashValue) {
            // TODO ensure good properties of hash function
            int h = Math.abs(dId.getId() ^ entityHashValue);
            return table[h % TABLE_SIZE];
        }
        
        public StringBuilder append(StringBuilder sb) {
            return append(sb, false);
        }

        public StringBuilder append(StringBuilder sb, boolean detail) {
            for (int i = 0; i < table.length; ++i) {
                sb.append(i).append(" : ");
                if (detail) {
                    sb.append(table[i]);
                } else {
                    sb.append(table[i].firstResourceIndex);
                }
                sb.append('\n');
            }
            return sb;
        }
    }
    
    private static class ResourceGroup {
        private ReentrantReadWriteLock latch;
        private Condition condition;
        AtomicLong firstResourceIndex;

        ResourceGroup() {
            latch = new ReentrantReadWriteLock();
            condition = latch.writeLock().newCondition();
            firstResourceIndex = new AtomicLong(-1);
        }
        
        void getLatch() {
            log("latch " + toString());
            latch.writeLock().lock();
        }
        
        void releaseLatch() {
            log("release " + toString());
            latch.writeLock().unlock();
        }
        
        boolean hasWaiters() {
            return latch.hasQueuedThreads();
        }
        
        void await(ITransactionContext txnContext) throws ACIDException {
            log("wait for " + toString());
            try {
                condition.await();
            } catch (InterruptedException e) {
                throw new ACIDException(txnContext, "interrupted", e);
            }
        }
        
        void wakeUp() {
            log("notify " + toString());
            condition.signalAll();
        }
        
        void log(String s) {
            //System.out.println("XXXX " + s);
        }
        
        public String toString() {
            return "{ id : " + hashCode()
                    + ", first : " + firstResourceIndex.toString() 
                    + ", waiters : " + (hasWaiters() ? "true" : "false") + " }";
        }
    }
}
