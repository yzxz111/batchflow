package com.batchflow;

public class LeaseLostException extends RuntimeException {
    public LeaseLostException() { super("任务租约已失效或任务已取消"); }
}
