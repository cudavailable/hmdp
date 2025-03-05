package com.hmdp.utils;

public interface ILock {
    /**
     * 尝试上锁（非阻塞）
     * @param timeoutSec
     * @return
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unLock();
}
