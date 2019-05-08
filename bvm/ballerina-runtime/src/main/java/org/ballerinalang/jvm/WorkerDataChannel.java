/*
*  Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing,
*  software distributed under the License is distributed on an
*  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*  KIND, either express or implied.  See the License for the
*  specific language governing permissions and limitations
*  under the License.
*/
package org.ballerinalang.jvm;

import org.ballerinalang.jvm.values.ErrorValue;
import org.ballerinalang.jvm.values.RefValue;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This represents a worker data channel that is created for each worker to
 * worker interaction for each function call.
 *
 * @since 0.995.0
 */
public class WorkerDataChannel {

    private Strand receiver;
    private Strand sender;
    private WaitingSender waitingSender;
    private WaitingSender flushSender;
    private ErrorValue error;
    private ErrorValue panic;
    private int senderCounter;
    private int receiverCounter;

    private Lock channelLock;

    public String chnlName;

    @SuppressWarnings("rawtypes")
    private Queue<WorkerResult> channel = new LinkedList<>();

    public WorkerDataChannel() {
        this.channelLock = new ReentrantLock();
        this.senderCounter = 0;
        this.receiverCounter = 0;
    }
    public WorkerDataChannel(String channelName) {
        this.channelLock = new ReentrantLock();
        this.senderCounter = 0;
        this.receiverCounter = 0;
        this.chnlName = channelName;
    }

    public void acquireChannelLock() {
        this.channelLock.lock();
    }

    public void releaseChannelLock() {
        this.channelLock.unlock();
    }

    @SuppressWarnings("rawtypes")
    public void sendData(Object data, Strand sender) {
        this.sender = sender;
        acquireChannelLock();
        this.channel.add(new WorkerResult(data));
        this.senderCounter++;
        if (this.receiver != null) {
            this.receiver.scheduler.unblockStrand(this.receiver);
            this.receiver = null;
        }
        releaseChannelLock();
    }

    /**
     * Put data for async send.
     * @param data - data to be sent over the channel
     * @param waitingCtx - sending context, that will be paused
     * @param retReg - Reg index to assign result of the send
     * @return true if execution can continue
     */
    public boolean syncSendData(RefValue data, Strand waitingCtx, int retReg) {
        // TODO : Fix later for sync send
        try {
            acquireChannelLock();
            this.channel.add(new WorkerResult(data, true));
            this.senderCounter++;
            this.waitingSender = new WaitingSender(waitingCtx, retReg, -1);
            if (this.receiver != null) {
//                BVMScheduler.stateChange(this.receiver, State.PAUSED, State.RUNNABLE);
//                BVMScheduler.schedule(this.receiver);
                this.receiver = null;
            } else if (this.panic != null) {
//                waitingCtx.setError(this.panic);
//                BVM.handleError(waitingCtx);
                this.panic = null;
                return true;
            } else if (this.error != null) {
//                waitingCtx.currentFrame.refRegs[retReg] = this.error;
                this.error = null;
                return true;
            }
            return false;
        } finally {
            releaseChannelLock();
        }
    }
    
    @SuppressWarnings("rawtypes")
    public Object tryTakeData(Strand strand) {
        try {
            acquireChannelLock();
            WorkerResult result = this.channel.peek();
            if (result != null) {
                this.receiverCounter++;
                this.channel.remove();
                // TODO: Fix later for sync send
//                if (result.isSync) {
////                    this.waitingSender.waitingCtx.currentFrame.refRegs[this.waitingSender.returnReg] = null;
//                    //will continue if this is a sync wait, will try to flush again if blocked on flush
////                    BVMScheduler.stateChange(this.waitingSender.waitingCtx, State.PAUSED, State.RUNNABLE);
////                    BVMScheduler.schedule(this.waitingSender.waitingCtx);
//                    this.waitingSender = null;
//                } else if (this.flushSender != null && this.flushSender.flushCount == this.receiverCounter) {
                // TODO: Fix later for flush
////                    this.flushSender.waitingCtx.flushDetail.flushLock.lock();
////                    this.flushSender.waitingCtx.flushDetail.flushedCount++;
////                    if (this.flushSender.waitingCtx.flushDetail.flushedCount
////                            == this.flushSender.waitingCtx.flushDetail.flushChannels.length) {
////                        this.flushSender.waitingCtx.currentFrame.refRegs[this.flushSender.returnReg] = null;
//                        //will continue if this is a sync wait, will try to flush again if blocked on flush
////                        BVMScheduler.stateChange(this.flushSender.waitingCtx, State.PAUSED, State.RUNNABLE);
////                        BVMScheduler.schedule(this.flushSender.waitingCtx);
////                    }
////                    this.flushSender.waitingCtx.flushDetail.flushLock.unlock();
//                    this.flushSender = null;
//                }
//                BVM.copyArgValueForWorkerReceive(ctx.currentFrame, reg, type, result.value);
                return result.value;
            } else if (this.panic != null && this.senderCounter == this.receiverCounter + 1) {
                this.receiverCounter++;
//                ctx.setError(this.panic);
//                BVM.handleError(ctx);
                throw new RuntimeException(this.panic);
            } else if (this.error != null && this.senderCounter == this.receiverCounter + 1) {
                this.receiverCounter++;
                return error;
            } else {
                this.receiver = strand;
                strand.blocked = true;
                strand.yield = true;  // we are going to execute the same worker receive operation later
//                ctx.currentFrame.ip--;
//                BVMScheduler.stateChange(ctx, State.RUNNABLE, State.PAUSED);
                return null;
            }
        } finally {
            releaseChannelLock();
        }
    }

    /**
     * Method to flush channel.
     *
     * @param ctx waiting for flush
     * @param retReg registry
     * @return flushable or not
     */
    public boolean flushChannel(Strand ctx, int retReg) {
        acquireChannelLock();
        try {
            if (this.panic != null) {
//                ctx.setError(this.panic);
//                BVM.handleError(ctx);
                return true;
            } else if (this.error != null) {
//                ctx.currentFrame.refRegs[retReg] = this.error;
                return true;
            } else if (this.receiverCounter == this.senderCounter) {
//                ctx.flushDetail.flushLock.lock();
//                ctx.flushDetail.flushedCount++;
//                if (ctx.flushDetail.flushedCount == ctx.flushDetail.flushChannels.length) {
//                    ctx.currentFrame.refRegs[retReg] = null;
//                    ctx.flushDetail.flushLock.unlock();
//                    return true;
//                }
//                ctx.flushDetail.flushLock.unlock();
                return false;
            }
            this.flushSender = new WaitingSender(ctx, retReg, this.senderCounter);
            return false;
        } finally {
            releaseChannelLock();
        }
    }

    /**
     * Set the state as error if the receiving worker is in error state.
     * @param error the BError of the receiving worker
     */
    public void setSendError(ErrorValue error) {
        acquireChannelLock();
        this.error = error;
        this.senderCounter++;
        if (this.receiver != null) {
//            BVMScheduler.stateChange(this.receiver, State.PAUSED, State.RUNNABLE);
//            BVMScheduler.schedule(this.receiver);
            this.receiver.scheduler.unblockStrand(this.receiver);
            this.receiver = null;
        }
        releaseChannelLock();
    }

    /**
     * Method to set reciever errors.
     *
     * @param error to be set
     */
    public void setReceiveError(ErrorValue error) {
        acquireChannelLock();
        this.error = error;
        this.receiverCounter++;
        if (this.flushSender != null) {
//            this.flushSender.waitingCtx.currentFrame.refRegs[this.flushSender.returnReg] = this.error;
//            BVMScheduler.stateChange(this.flushSender.waitingCtx, State.PAUSED, State.RUNNABLE);
//            BVMScheduler.schedule(this.flushSender.waitingCtx);
            this.flushSender = null;
        } else if (this.waitingSender != null) {
//            this.waitingSender.waitingStrand.scheduler.unblockStrand(this.waitingSender.waitingStrand);
//            this.waitingSender.waitingCtx.currentFrame.refRegs[waitingSender.returnReg] = this.error;
//            BVMScheduler.stateChange(this.waitingSender.waitingCtx, State.PAUSED, State.RUNNABLE);
//            BVMScheduler.schedule(this.waitingSender.waitingCtx);
            this.waitingSender = null;
        }
        releaseChannelLock();
    }

    /**
     * Method to set sender panics.
     *
     * @param panic to be set
     */
    public void setSendPanic(ErrorValue panic) {
        acquireChannelLock();
        this.panic  = panic;
        this.senderCounter++;
        if (this.receiver != null) {
//            BVMScheduler.stateChange(this.receiver, State.PAUSED, State.RUNNABLE);
//            BVMScheduler.schedule(this.receiver);
            this.receiver.scheduler.unblockStrand(this.receiver);
            this.receiver = null;
        }
        releaseChannelLock();
    }

    /**
     * Method to set receiver panics.
     *
     * @param panic to be set
     */
    public void setReceiverPanic(ErrorValue panic) {
        acquireChannelLock();
        this.panic  = panic;
        this.receiverCounter++;
        if (this.flushSender != null) {
//            this.flushSender.waitingCtx.setError(this.panic);
//            BVM.handleError(this.flushSender.waitingCtx);
//            BVMScheduler.stateChange(this.flushSender.waitingCtx, State.PAUSED, State.RUNNABLE);
//            BVMScheduler.schedule(this.flushSender.waitingCtx);
//            this.flushSender = null;
        } else if (this.waitingSender != null) {
//            this.waitingSender.waitingCtx.setError(this.panic);
//            BVM.handleError(this.waitingSender.waitingCtx);
//            BVMScheduler.stateChange(this.waitingSender.waitingCtx, State.PAUSED, State.RUNNABLE);
//            BVMScheduler.schedule(this.waitingSender.waitingCtx);
//            this.waitingSender = null;
        }
        releaseChannelLock();
    }

    @SuppressWarnings("rawtypes")
    public synchronized WorkerResult tryTakeData() {
        return this.channel.poll();
    }

    /**
     * This represents a worker result value. This is done as a value to be used in the
     * queues used for worker communication. In this way, the queue can distinguish the
     * case of a value not there when the peek, by returning null, and then, if they
     * return a WorkerResult, the value inside it can be either null or not to mention
     * Ballerina null value and non-null value.
     */
    public static class WorkerResult {

        public Object value;
        public boolean isSync;


        public WorkerResult(Object value) {
            this.value = value;
        }

        public WorkerResult(Object value, boolean sync) {
            this.value = value;
            this.isSync = sync;
        }

    }

    /**
     * This represents the sender of the channel. If the sender is available, then we assume it is waiting for the
     * data retrieval. Upon fetching data, it will be resumed if a sync send or will try to flush.
     */
    public static class WaitingSender {

        public Strand waitingStrand;
        public int returnReg;
        public int flushCount;

        public WaitingSender(Strand strand, int reg, int flushCount) {
            this.waitingStrand = strand;
            this.returnReg = reg;
            this.flushCount = flushCount;
        }
    }
}
