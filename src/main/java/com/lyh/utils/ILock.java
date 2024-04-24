package com.lyh.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec timeoutSec
     * @return boolean
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unLock();
}
