# analyze-LinkedBlockingQueue

## 链表阻塞队列
没什么特别的知识点，会用锁的，都会写这个

### 特点
+ 里面有两把锁(生产者锁和消费者锁)  
默认的添加和获取是只获取其中一把锁，他们同时操作一个链表，
其实会有冲突，但是因为只是一个尾存取和一个头删除，最终结果是不会错误的。
+ 里面也有两个等待队列(aqs知识，生产等待和消费等待)  
因为链表可以设置最大容量，所以如果满了，就不允许添加，需要等待，空了，获取时，也需要等待。
+ 有一个缓冲链表  
有一定容量，加入元素可以存在这里，等待消费线程获取。
默认有一个null值的头节点，初始化时，尾节点也指向它，类似aqs中的CLH链表  

### 具体执行逻辑
+ 加入
先抢锁，进去后如果满了，就等待。执行完后，唤醒一下等待获取的线程。
+ 取出
先抢锁，进去空了，就等待。执行完后，唤醒一下等待加入的线程。

## 知识参考
[链表操作](https://github.com/lilingyan/take-LinkedList-apart)  
[锁操作](https://github.com/lilingyan/take-ReentrantReadWriteLock-apart)