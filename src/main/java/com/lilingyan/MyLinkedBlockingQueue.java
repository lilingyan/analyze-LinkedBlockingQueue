package com.lilingyan;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 链表阻塞队列
 * @Author: lilingyan
 * @Date 2019/3/26 10:54
 */
public class MyLinkedBlockingQueue<E> {

    /**
     * 最大容量
     */
    private final int capacity;

    /**
     * 当前使用容量
     */
    private final AtomicInteger count = new AtomicInteger();

    /**
     * 头尾节点指针
     * 指针不是指空的 而是指向一个null初始化的节点(看构造函数)
     * 类似aqs中的CLH队列
     */
    transient Node<E> head;
    private transient Node<E> last;

    /**
     * 取锁
     */
    private final ReentrantLock takeLock = new ReentrantLock();

    /**
     * 等待取元素的线程
     */
    private final Condition notEmpty = takeLock.newCondition();

    /**
     * 存锁
     */
    private final ReentrantLock putLock = new ReentrantLock();

    /**
     * 等待加元素的线程
     */
    private final Condition notFull = putLock.newCondition();

    //=========================构造器==========================
    public MyLinkedBlockingQueue() {
        this(Integer.MAX_VALUE);
    }
    public MyLinkedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        last = head = new Node<E>(null);    //!!!
    }
    //=========================构造器==========================

    //=========================添加==========================
    /**
     * 插入一个数据
     * @param e
     * @return 成功返回true 失败返回false
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();    //自定义的判断 空抛出错误
        /**
         * 判断是否已经超过队列容量限制
         */
        final AtomicInteger count = this.count;
        if (count.get() == capacity)
            return false;
        int c = -1;
        Node<E> node = new Node<E>(e);
        final ReentrantLock putLock = this.putLock;
        putLock.lock();
        try {
            if (count.get() < capacity) {
                enqueue(node);                  //加入队列末尾
                c = count.getAndIncrement();    //获取原来的使用量并加1
                /**
                 * 如果加完这个元素后，还有空余的容量
                 * 则唤醒等待加入元素的线程(如果有)
                 */
                if (c + 1 < capacity)
                    notFull.signal();
            }
        } finally {
            putLock.unlock();
        }
        /**
         * 如果加入元素是第一个
         * 可能是已经被取光了，并且还有等待获取的
         * 所以唤醒一下获取线程(如果有)
         */
        if (c == 0)
            signalNotEmpty();
        return c >= 0;
    }

    /**
     * 添加一个元素
     * 失败则抛错
     * @param e
     * @return
     */
    public boolean add(E e) {
        /**
         * 为符合queue规范
         */
        if (offer(e))
            return true;
        else
            throw new IllegalStateException("Queue full");
    }

    /**
     * 插入元素(会一直等到成功)
     * @param e
     * @throws InterruptedException
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        int c = -1;
        Node<E> node = new Node<E>(e);
        final ReentrantLock putLock = this.putLock;
        final AtomicInteger count = this.count;
        putLock.lockInterruptibly();    //支持中断锁
        /**
         * 因为就一把锁
         * 所以能走到这里的 同时只可能就一个线程
         * 别的都在上一句等待
         */
        try {
            /**
             * 如果满了 就等待
             */
            while (count.get() == capacity) {
                notFull.await();
            }
            //下面和#offer方法一样
            enqueue(node);
            c = count.getAndIncrement();
            if (c + 1 < capacity)
                notFull.signal();
        } finally {
            putLock.unlock();
        }
        if (c == 0)
            signalNotEmpty();
    }
    //=========================添加==========================

    //=========================获取==========================
    /**
     * 取头节点
     * @return  没有就返回空
     */
    public E poll() {
        final AtomicInteger count = this.count;
        if (count.get() == 0)   //空集合
            return null;
        E x = null;
        int c = -1;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            if (count.get() > 0) {
                x = dequeue();
                c = count.getAndDecrement();
                if (c > 1)      //唤醒一下后面等待的获取线程
                    notEmpty.signal();
            }
        } finally {
            takeLock.unlock();
        }
        if (c == capacity)
            signalNotFull();
        return x;
    }
    /**
     * 获取一个元素(没有的话 等待 直到有)
     * 原理与#put()一致
     * @return
     * @throws InterruptedException
     */
    public E take() throws InterruptedException {
        E x;
        int c = -1;
        final AtomicInteger count = this.count;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lockInterruptibly();
        try {
            while (count.get() == 0) {
                notEmpty.await();
            }
            x = dequeue();
            c = count.getAndDecrement();
            if (c > 1)
                notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
        if (c == capacity)
            signalNotFull();
        return x;
    }

    /**
     * 获取一下第一个元素  如果没有 就返回null
     * @return
     */
    public E peek() {
        if (count.get() == 0)
            return null;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            Node<E> first = head.next;
            if (first == null)
                return null;
            else
                return first.item;
        } finally {
            takeLock.unlock();
        }
    }

    /**
     * 把节点批量加入集合
     * 不需要频繁获取锁 所以性能高那么一点
     * @param c
     * @param maxElements
     * @return
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        if (maxElements <= 0)
            return 0;
        boolean signalNotFull = false;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            /**
             * 尽可能多的像集合中添加数据
             *
             * 就是从头遍历
             * 把节点加入到集合
             */
            int n = Math.min(maxElements, count.get());
            Node<E> h = head;
            int i = 0;
            try {
                while (i < n) {
                    Node<E> p = h.next;
                    c.add(p.item);
                    p.item = null;
                    h.next = h;
                    h = p;
                    ++i;
                }
                return n;
            } finally {
                // Restore invariants even if c.add() threw
                if (i > 0) {
                    head = h;
                    signalNotFull = (count.getAndAdd(-i) == capacity);  //原来是否是满队列
                }
            }
        } finally {
            takeLock.unlock();
            if (signalNotFull)  //如果原来是满的 现在少了  所以唤醒一下加入等待的线程
                signalNotFull();
        }
    }
    //=========================获取==========================

    //=========================删除==========================
    /**
     * 删一个节点
     * @param o
     * @return
     */
    public boolean remove(Object o) {
        if (o == null) return false;
        fullyLock();                //修改时读写读锁
        /**
         * 就是一个链表遍历
         * 然后删掉节点
         */
        try {
            for (Node<E> trail = head, p = trail.next;
                 p != null;
                 trail = p, p = p.next) {
                if (o.equals(p.item)) {
                    unlink(p, trail);
                    return true;
                }
            }
            return false;
        } finally {
            fullyUnlock();
        }
    }
    /**
     * 清空
     * 只清空了当前存在队列中的数据
     * 如果是等待线程还没加进来的 执行完clera()后 会直接加进来！！！
     */
    public void clear() {
        fullyLock();
        try {
            for (Node<E> p, h = head; (p = h.next) != null; h = p) {
                h.next = h;
                p.item = null;
            }
            head = last;
            /**
             * 如果原来是满队列
             * 现在清空了
             * 但是等待的线程 被唤醒
             * 还会继续向里添加元素
             */
            if (count.getAndSet(0) == capacity)
                notFull.signal();
        } finally {
            fullyUnlock();
        }
    }
    //=========================删除==========================

    /**
     * 唤醒等待中的获取线程
     */
    private void signalNotEmpty() {
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock();
        try {
            notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
    }

    /**
     * 唤醒等待添加的线程
     */
    private void signalNotFull() {
        final ReentrantLock putLock = this.putLock;
        putLock.lock();
        try {
            notFull.signal();
        } finally {
            putLock.unlock();
        }
    }

    /**
     * 加个尾节点
     * @param node
     */
    private void enqueue(Node<E> node) {
        last = last.next = node;
    }

    /**
     * 弹出队列第一个节点
     * (记得初始化的时候 首尾是指向了一个空内容元素的
     *  所以要取第二个)
     * @return
     */
    private E dequeue() {
        Node<E> h = head;
        Node<E> first = h.next;
        h.next = h; // help GC
        head = first;
        E x = first.item;
        first.item = null;
        return x;
    }
    /**
     * 删掉一个节点
     * @param p
     * @param trail 记录被删掉节点的后指针
     */
    void unlink(Node<E> p, Node<E> trail) {
        p.item = null;
        trail.next = p.next;
        if (last == p)
            last = trail;
        if (count.getAndDecrement() == capacity)
            notFull.signal();
    }

    void fullyLock() {
        putLock.lock();
        takeLock.lock();
    }
    void fullyUnlock() {
        takeLock.unlock();
        putLock.unlock();
    }

    /**
     * 最简单的单向链表
     * @param <E>
     */
    static class Node<E> {
        E item;
        Node<E> next;
        Node(E x) { item = x; }
    }

}
