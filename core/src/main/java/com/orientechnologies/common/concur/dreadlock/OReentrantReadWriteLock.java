package com.orientechnologies.common.concur.dreadlock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

public class OReentrantReadWriteLock {

  /**
   * Synchronization implementation for OReentrantReadWriteLock.
   */
  private final Sync sync = new Sync(this);

  private final ReadLock  readLock  = new ReadLock(this);
  private final WriteLock writeLock = new WriteLock(this);

  private final OLockWaitForVertex waitForVertex = new OLockWaitForVertex(this);
  private final OThreadLocalWaitForVertex threadLocalWaitForVertex;

  public OReentrantReadWriteLock(OThreadLocalWaitForVertex threadLocalWaitForVertex) {
    this.threadLocalWaitForVertex = threadLocalWaitForVertex;
  }

  public Lock getReadLock() {
    return readLock;
  }

  public Lock getWriteLock() {
    return writeLock;
  }

  static class Sync extends OAbstractQueuedSynchronizer {
    private final OReentrantReadWriteLock lock;

    /*
     * Read vs write count extraction constants and functions.
     * Lock state is logically divided into two unsigned shorts:
     * The lower one representing the exclusive (writer) lock hold count,
     * and the upper the shared (reader) hold count.
     */
    static final int SHARED_SHIFT   = 16;
    static final int SHARED_UNIT    = (1 << SHARED_SHIFT);
    static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;
    static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;

    /**
     * Returns the number of shared holds represented in count
     */
    static int sharedCount(int c) {
      return c >>> SHARED_SHIFT;
    }

    /**
     * Returns the number of exclusive holds represented in count
     */
    static int exclusiveCount(int c) {
      return c & EXCLUSIVE_MASK;
    }

    /**
     * A counter for per-thread read hold counts.
     * Maintained as a ThreadLocal; cached in cachedHoldCounter
     */
    static final class HoldCounter {
      int count = 0;
      final long tid = Thread.currentThread().getId();
    }

    /**
     * ThreadLocal subclass. Easiest to explicitly define for sake
     * of deserialization mechanics.
     */
    static final class ThreadLocalHoldCounter extends ThreadLocal<HoldCounter> {
      public HoldCounter initialValue() {
        return new HoldCounter();
      }
    }

    /**
     * The number of reentrant read locks held by current thread.
     * Initialized only in constructor.
     * Removed whenever a thread's read hold count drops to 0.
     */
    private transient ThreadLocalHoldCounter readHolds;

    /**
     * The hold count of the last thread to successfully acquire
     * readLock. This saves ThreadLocal lookup in the common case
     * where the next thread to release is the last one to
     * acquire. This is non-volatile since it is just used
     * as a heuristic, and would be great for threads to cache.
     * <p>
     * <p>Can outlive the Thread for which it is caching the read
     * hold count, but avoids garbage retention by not retaining a
     * reference to the Thread.
     * <p>
     * <p>Accessed via a benign data race; relies on the memory
     * model's final field and out-of-thin-air guarantees.
     */
    private transient HoldCounter cachedHoldCounter;

    /**
     * firstReader is the first thread to have acquired the read lock.
     * firstReaderHoldCount is firstReader's hold count.
     * <p>
     * <p>More precisely, firstReader is the unique thread that last
     * changed the shared count from 0 to 1, and has not released the
     * read lock since then; null if there is no such thread.
     * <p>
     * <p>Cannot cause garbage retention unless the thread terminated
     * without relinquishing its read locks, since tryReleaseShared
     * sets it to null.
     * <p>
     * <p>Accessed via a benign data race; relies on the memory
     * model's out-of-thin-air guarantees for references.
     * <p>
     * <p>This allows tracking of read holds for uncontended read
     * locks to be very cheap.
     */
    private transient Thread firstReader = null;
    private transient int firstReaderHoldCount;

    Sync(final OReentrantReadWriteLock lock) {
      readHolds = new ThreadLocalHoldCounter();
      this.lock = lock;
      setState(getState()); // ensures visibility of readHolds
    }

    /**
     * Returns true if the current thread, when trying to acquire
     * the read lock, and otherwise eligible to do so, should block
     * because of policy for overtaking other waiting threads.
     */
    private boolean readerShouldBlock() {
     /* As a heuristic to avoid indefinite writer starvation,
      * block if the thread that momentarily appears to be head
      * of queue, if one exists, is a waiting writer.  This is
      * only a probabilistic effect since a new reader will not
      * block if there is a waiting writer behind other enabled
      * readers that have not yet drained from the queue.
      */
      return apparentlyFirstQueuedIsExclusive();
    }

    protected final boolean tryRelease(int releases) {
      if (!isHeldExclusively())
        throw new IllegalMonitorStateException();

      final OThreadWaitForVertex threadWaitForVertex = lock.threadLocalWaitForVertex.get();
      assert threadWaitForVertex.getWaitingFor() == null;

      int nextc = getState() - releases;
      boolean free = exclusiveCount(nextc) == 0;

      if (free) {
        lock.waitForVertex.removeAcquiredBy(threadWaitForVertex);
        setExclusiveOwnerThread(null);
      }

      setState(nextc);
      return free;
    }

    protected final boolean tryAcquire(int acquires) {
      /*
       * Walkthrough:
       * 1. If read count nonzero or write count nonzero
       *    and owner is a different thread, fail.
       * 2. If count would saturate, fail. (This can only
       *    happen if count is already nonzero.)
       * 3. Otherwise, this thread is eligible for lock if
       *    it is either a reentrant acquire or
       *    queue policy allows it. If so, update state
       *    and set owner.
       */

      final OThreadWaitForVertex threadWaitForVertex = lock.threadLocalWaitForVertex.get();

      Thread current = Thread.currentThread();
      int c = getState();
      int w = exclusiveCount(c);
      if (c != 0) {
        // (Note: if c != 0 and w == 0 then shared count != 0)
        if (w == 0 || current != getExclusiveOwnerThread()) {
          threadWaitForVertex.setWaitingFor(lock.waitForVertex);
          return false;
        }

        if (w + exclusiveCount(acquires) > MAX_COUNT) {
          threadWaitForVertex.setWaitingFor(null);

          throw new Error("Maximum lock count exceeded");
        }

        // Reentrant acquire
        setState(c + acquires);

        assert threadWaitForVertex.getWaitingFor() == null;
        assert lock.waitForVertex.getAcquiredBy().contains(threadWaitForVertex);

        return true;
      }

      if (!compareAndSetState(c, c + acquires)) {
        threadWaitForVertex.setWaitingFor(lock.waitForVertex);
        return false;
      }

      setExclusiveOwnerThread(current);

      threadWaitForVertex.setWaitingFor(null);
      lock.waitForVertex.addAcquiredBy(threadWaitForVertex);

      return true;
    }

    protected final boolean tryReleaseShared(int unused) {
      Thread current = Thread.currentThread();
      if (firstReader == current) {
        // assert firstReaderHoldCount > 0;
        if (firstReaderHoldCount == 1)
          firstReader = null;
        else
          firstReaderHoldCount--;
      } else {
        HoldCounter rh = cachedHoldCounter;
        if (rh == null || rh.tid != current.getId())
          rh = readHolds.get();
        int count = rh.count;

        if (count <= 1) {
          readHolds.remove();
          if (count <= 0)
            throw unmatchedUnlockException();
        }

        --rh.count;
      }
      for (; ; ) {
        int c = getState();
        int nextc = c - SHARED_UNIT;
        if (compareAndSetState(c, nextc)) {
          // Releasing the read lock has no effect on readers,
          // but it may allow waiting writers to proceed if
          // both read and write locks are now free.

          final boolean released = nextc == 0;

          if (released) {
            final OThreadWaitForVertex threadWaitForVertex = lock.threadLocalWaitForVertex.get();
            lock.waitForVertex.removeAcquiredBy(threadWaitForVertex);
          }

          return released;
        }
      }
    }

    private IllegalMonitorStateException unmatchedUnlockException() {
      return new IllegalMonitorStateException("attempt to unlock read lock, not locked by current thread");
    }

    protected final int tryAcquireShared(int unused) {
     /*
      * Walkthrough:
      * 1. If write lock held by another thread, fail.
      * 2. Otherwise, this thread is eligible for
      *    lock wrt state, so ask if it should block
      *    because of queue policy. If not, try
      *    to grant by CASing state and updating count.
      *    Note that step does not check for reentrant
      *    acquires, which is postponed to full version
      *    to avoid having to check hold count in
      *    the more typical non-reentrant case.
      * 3. If step 2 fails either because thread
      *    apparently not eligible or CAS fails or count
      *    saturated, chain to version with full retry loop.
      */

      final OThreadWaitForVertex threadWaitForVertex = lock.threadLocalWaitForVertex.get();

      Thread current = Thread.currentThread();
      int c = getState();

      if (exclusiveCount(c) != 0 && getExclusiveOwnerThread() != current) {
        threadWaitForVertex.setWaitingFor(lock.waitForVertex);

        return -1;
      }

      int r = sharedCount(c);
      if (!readerShouldBlock() &&
          r < MAX_COUNT &&
          compareAndSetState(c, c + SHARED_UNIT)) {

        if (r == 0) {
          firstReader = current;
          firstReaderHoldCount = 1;
        } else if (firstReader == current) {
          firstReaderHoldCount++;
        } else {
          HoldCounter rh = cachedHoldCounter;

          if (rh == null || rh.tid != current.getId())
            cachedHoldCounter = rh = readHolds.get();
          else if (rh.count == 0)
            readHolds.set(rh);
          rh.count++;
        }

        threadWaitForVertex.setWaitingFor(null);
        lock.waitForVertex.addAcquiredBy(threadWaitForVertex);

        return 1;
      }

      return fullTryAcquireShared(current, threadWaitForVertex);
    }

    /**
     * Full version of acquire for reads, that handles CAS misses
     * and reentrant reads not dealt with in tryAcquireShared.
     */
    final int fullTryAcquireShared(Thread current, final OThreadWaitForVertex threadWaitForVertex) {
     /*
      * This code is in part redundant with that in
      * tryAcquireShared but is simpler overall by not
      * complicating tryAcquireShared with interactions between
      * retries and lazily reading hold counts.
      */

      HoldCounter rh = null;
      for (; ; ) {
        int c = getState();
        if (exclusiveCount(c) != 0) {
          if (getExclusiveOwnerThread() != current) {
            threadWaitForVertex.setWaitingFor(lock.waitForVertex);

            return -1;
          }
          // else we hold the exclusive lock; blocking here
          // would cause deadlock.
        } else if (readerShouldBlock()) {
          // Make sure we're not acquiring read lock reentrantly
          if (firstReader == current) {
            // assert firstReaderHoldCount > 0;
          } else {
            if (rh == null) {
              rh = cachedHoldCounter;
              if (rh == null || rh.tid != current.getId()) {
                rh = readHolds.get();
                if (rh.count == 0)
                  readHolds.remove();
              }
            }

            if (rh.count == 0) {
              threadWaitForVertex.setWaitingFor(lock.waitForVertex);
              return -1;
            }
          }
        }

        if (sharedCount(c) == MAX_COUNT) {
          threadWaitForVertex.setWaitingFor(null);
          throw new Error("Maximum lock count exceeded");
        }

        if (compareAndSetState(c, c + SHARED_UNIT)) {
          if (sharedCount(c) == 0) {
            firstReader = current;
            firstReaderHoldCount = 1;
          } else if (firstReader == current) {
            firstReaderHoldCount++;
          } else {
            if (rh == null)
              rh = cachedHoldCounter;
            if (rh == null || rh.tid != current.getId())
              rh = readHolds.get();
            else if (rh.count == 0)
              readHolds.set(rh);
            rh.count++;
            cachedHoldCounter = rh; // cache for release
          }

          threadWaitForVertex.setWaitingFor(null);
          lock.waitForVertex.addAcquiredBy(threadWaitForVertex);

          return 1;
        }
      }
    }

    /**
     * Performs tryLock for write, enabling barging in both modes.
     * This is identical in effect to tryAcquire except for lack
     * of calls to writerShouldBlock.
     */
    final boolean tryWriteLock() {
      Thread current = Thread.currentThread();
      int c = getState();
      if (c != 0) {
        int w = exclusiveCount(c);
        if (w == 0 || current != getExclusiveOwnerThread())
          return false;
        if (w == MAX_COUNT)
          throw new Error("Maximum lock count exceeded");
      }
      if (!compareAndSetState(c, c + 1))
        return false;
      setExclusiveOwnerThread(current);
      return true;
    }

    /**
     * Performs tryLock for read, enabling barging in both modes.
     * This is identical in effect to tryAcquireShared except for
     * lack of calls to readerShouldBlock.
     */
    final boolean tryReadLock() {
      Thread current = Thread.currentThread();
      for (; ; ) {
        int c = getState();
        if (exclusiveCount(c) != 0 && getExclusiveOwnerThread() != current)
          return false;
        int r = sharedCount(c);
        if (r == MAX_COUNT)
          throw new Error("Maximum lock count exceeded");
        if (compareAndSetState(c, c + SHARED_UNIT)) {
          if (r == 0) {
            firstReader = current;
            firstReaderHoldCount = 1;
          } else if (firstReader == current) {
            firstReaderHoldCount++;
          } else {
            HoldCounter rh = cachedHoldCounter;
            if (rh == null || rh.tid != current.getId())
              cachedHoldCounter = rh = readHolds.get();
            else if (rh.count == 0)
              readHolds.set(rh);
            rh.count++;
          }
          return true;
        }
      }
    }

    protected final boolean isHeldExclusively() {
      // While we must in general read state before owner,
      // we don't need to do so to check if current thread is owner
      return getExclusiveOwnerThread() == Thread.currentThread();
    }

    // Methods relayed to outer class

    final ConditionObject newCondition() {
      return new ConditionObject();
    }

    final Thread getOwner() {
      // Must read state before owner to ensure memory consistency
      return ((exclusiveCount(getState()) == 0) ? null : getExclusiveOwnerThread());
    }

    final int getReadLockCount() {
      return sharedCount(getState());
    }

    final boolean isWriteLocked() {
      return exclusiveCount(getState()) != 0;
    }

    final int getWriteHoldCount() {
      return isHeldExclusively() ? exclusiveCount(getState()) : 0;
    }

    final int getReadHoldCount() {
      if (getReadLockCount() == 0)
        return 0;

      Thread current = Thread.currentThread();
      if (firstReader == current)
        return firstReaderHoldCount;

      HoldCounter rh = cachedHoldCounter;
      if (rh != null && rh.tid == current.getId())
        return rh.count;

      int count = readHolds.get().count;
      if (count == 0)
        readHolds.remove();
      return count;
    }

    /**
     * Reconstitutes the instance from a stream (that is, deserializes it).
     */
    private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
      s.defaultReadObject();
      readHolds = new ThreadLocalHoldCounter();
      setState(0); // reset to unlocked state
    }

    final int getCount() {
      return getState();
    }
  }

  public static class ReadLock implements Lock, java.io.Serializable {
    private static final long serialVersionUID = -5992448646407690164L;
    private final Sync sync;

    /**
     * Constructor for use by subclasses
     *
     * @param lock the outer lock object
     * @throws NullPointerException if the lock is null
     */
    protected ReadLock(OReentrantReadWriteLock lock) {
      sync = lock.sync;
    }

    /**
     * Acquires the read lock.
     * <p>
     * <p>Acquires the read lock if the write lock is not held by
     * another thread and returns immediately.
     * <p>
     * <p>If the write lock is held by another thread then
     * the current thread becomes disabled for thread scheduling
     * purposes and lies dormant until the read lock has been acquired.
     */
    public void lock() {
      sync.acquireShared(1);
    }

    /**
     * Acquires the read lock unless the current thread is
     * {@linkplain Thread#interrupt interrupted}.
     * <p>
     * <p>Acquires the read lock if the write lock is not held
     * by another thread and returns immediately.
     * <p>
     * <p>If the write lock is held by another thread then the
     * current thread becomes disabled for thread scheduling
     * purposes and lies dormant until one of two things happens:
     * <p>
     * <ul>
     * <p>
     * <li>The read lock is acquired by the current thread; or
     * <p>
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread.
     * <p>
     * </ul>
     * <p>
     * <p>If the current thread:
     * <p>
     * <ul>
     * <p>
     * <li>has its interrupted status set on entry to this method; or
     * <p>
     * <li>is {@linkplain Thread#interrupt interrupted} while
     * acquiring the read lock,
     * <p>
     * </ul>
     * <p>
     * then {@link InterruptedException} is thrown and the current
     * thread's interrupted status is cleared.
     * <p>
     * <p>In this implementation, as this method is an explicit
     * interruption point, preference is given to responding to
     * the interrupt over normal or reentrant acquisition of the
     * lock.
     *
     * @throws InterruptedException if the current thread is interrupted
     */
    public void lockInterruptibly() throws InterruptedException {
      sync.acquireSharedInterruptibly(1);
    }

    /**
     * Acquires the read lock only if the write lock is not held by
     * another thread at the time of invocation.
     * <p>
     * <p>Acquires the read lock if the write lock is not held by
     * another thread and returns immediately with the value
     * {@code true}. Even when this lock has been set to use a
     * fair ordering policy, a call to {@code tryLock()}
     * <em>will</em> immediately acquire the read lock if it is
     * available, whether or not other threads are currently
     * waiting for the read lock.  This &quot;barging&quot; behavior
     * can be useful in certain circumstances, even though it
     * breaks fairness. If you want to honor the fairness setting
     * for this lock, then use {@link #tryLock(long, TimeUnit)
     * tryLock(0, TimeUnit.SECONDS) } which is almost equivalent
     * (it also detects interruption).
     * <p>
     * <p>If the write lock is held by another thread then
     * this method will return immediately with the value
     * {@code false}.
     *
     * @return {@code true} if the read lock was acquired
     */
    public boolean tryLock() {
      return sync.tryReadLock();
    }

    /**
     * Acquires the read lock if the write lock is not held by
     * another thread within the given waiting time and the
     * current thread has not been {@linkplain Thread#interrupt
     * interrupted}.
     * <p>
     * <p>Acquires the read lock if the write lock is not held by
     * another thread and returns immediately with the value
     * {@code true}. If this lock has been set to use a fair
     * ordering policy then an available lock <em>will not</em> be
     * acquired if any other threads are waiting for the
     * lock. This is in contrast to the {@link #tryLock()}
     * method. If you want a timed {@code tryLock} that does
     * permit barging on a fair lock then combine the timed and
     * un-timed forms together:
     * <p>
     * <pre> {@code
     * if (lock.tryLock() ||
     *     lock.tryLock(timeout, unit)) {
     *   ...
     * }}</pre>
     * <p>
     * <p>If the write lock is held by another thread then the
     * current thread becomes disabled for thread scheduling
     * purposes and lies dormant until one of three things happens:
     * <p>
     * <ul>
     * <p>
     * <li>The read lock is acquired by the current thread; or
     * <p>
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <p>
     * <li>The specified waiting time elapses.
     * <p>
     * </ul>
     * <p>
     * <p>If the read lock is acquired then the value {@code true} is
     * returned.
     * <p>
     * <p>If the current thread:
     * <p>
     * <ul>
     * <p>
     * <li>has its interrupted status set on entry to this method; or
     * <p>
     * <li>is {@linkplain Thread#interrupt interrupted} while
     * acquiring the read lock,
     * <p>
     * </ul> then {@link InterruptedException} is thrown and the
     * current thread's interrupted status is cleared.
     * <p>
     * <p>If the specified waiting time elapses then the value
     * {@code false} is returned.  If the time is less than or
     * equal to zero, the method will not wait at all.
     * <p>
     * <p>In this implementation, as this method is an explicit
     * interruption point, preference is given to responding to
     * the interrupt over normal or reentrant acquisition of the
     * lock, and over reporting the elapse of the waiting time.
     *
     * @param timeout the time to wait for the read lock
     * @param unit    the time unit of the timeout argument
     * @return {@code true} if the read lock was acquired
     * @throws InterruptedException if the current thread is interrupted
     * @throws NullPointerException if the time unit is null
     */
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
      return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }

    /**
     * Attempts to release this lock.
     * <p>
     * <p>If the number of readers is now zero then the lock
     * is made available for write lock attempts.
     */
    public void unlock() {
      sync.releaseShared(1);
    }

    /**
     * Throws {@code UnsupportedOperationException} because
     * {@code ReadLocks} do not support conditions.
     *
     * @throws UnsupportedOperationException always
     */
    public Condition newCondition() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns a string identifying this lock, as well as its lock state.
     * The state, in brackets, includes the String {@code "Read locks ="}
     * followed by the number of held read locks.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
      int r = sync.getReadLockCount();
      return super.toString() +
          "[Read locks = " + r + "]";
    }
  }

  public static class WriteLock implements Lock, java.io.Serializable {
    private static final long serialVersionUID = -4992448646407690164L;
    private final Sync sync;

    /**
     * Constructor for use by subclasses
     *
     * @param lock the outer lock object
     * @throws NullPointerException if the lock is null
     */
    protected WriteLock(OReentrantReadWriteLock lock) {
      sync = lock.sync;
    }

    /**
     * Acquires the write lock.
     * <p>
     * <p>Acquires the write lock if neither the read nor write lock
     * are held by another thread
     * and returns immediately, setting the write lock hold count to
     * one.
     * <p>
     * <p>If the current thread already holds the write lock then the
     * hold count is incremented by one and the method returns
     * immediately.
     * <p>
     * <p>If the lock is held by another thread then the current
     * thread becomes disabled for thread scheduling purposes and
     * lies dormant until the write lock has been acquired, at which
     * time the write lock hold count is set to one.
     */
    public void lock() {
      sync.acquire(1);
    }

    /**
     * Acquires the write lock unless the current thread is
     * {@linkplain Thread#interrupt interrupted}.
     * <p>
     * <p>Acquires the write lock if neither the read nor write lock
     * are held by another thread
     * and returns immediately, setting the write lock hold count to
     * one.
     * <p>
     * <p>If the current thread already holds this lock then the
     * hold count is incremented by one and the method returns
     * immediately.
     * <p>
     * <p>If the lock is held by another thread then the current
     * thread becomes disabled for thread scheduling purposes and
     * lies dormant until one of two things happens:
     * <p>
     * <ul>
     * <p>
     * <li>The write lock is acquired by the current thread; or
     * <p>
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread.
     * <p>
     * </ul>
     * <p>
     * <p>If the write lock is acquired by the current thread then the
     * lock hold count is set to one.
     * <p>
     * <p>If the current thread:
     * <p>
     * <ul>
     * <p>
     * <li>has its interrupted status set on entry to this method;
     * or
     * <p>
     * <li>is {@linkplain Thread#interrupt interrupted} while
     * acquiring the write lock,
     * <p>
     * </ul>
     * <p>
     * then {@link InterruptedException} is thrown and the current
     * thread's interrupted status is cleared.
     * <p>
     * <p>In this implementation, as this method is an explicit
     * interruption point, preference is given to responding to
     * the interrupt over normal or reentrant acquisition of the
     * lock.
     *
     * @throws InterruptedException if the current thread is interrupted
     */
    public void lockInterruptibly() throws InterruptedException {
      sync.acquireInterruptibly(1);
    }

    /**
     * Acquires the write lock only if it is not held by another thread
     * at the time of invocation.
     * <p>
     * <p>Acquires the write lock if neither the read nor write lock
     * are held by another thread
     * and returns immediately with the value {@code true},
     * setting the write lock hold count to one. Even when this lock has
     * been set to use a fair ordering policy, a call to
     * {@code tryLock()} <em>will</em> immediately acquire the
     * lock if it is available, whether or not other threads are
     * currently waiting for the write lock.  This &quot;barging&quot;
     * behavior can be useful in certain circumstances, even
     * though it breaks fairness. If you want to honor the
     * fairness setting for this lock, then use {@link
     * #tryLock(long, TimeUnit) tryLock(0, TimeUnit.SECONDS) }
     * which is almost equivalent (it also detects interruption).
     * <p>
     * <p>If the current thread already holds this lock then the
     * hold count is incremented by one and the method returns
     * {@code true}.
     * <p>
     * <p>If the lock is held by another thread then this method
     * will return immediately with the value {@code false}.
     *
     * @return {@code true} if the lock was free and was acquired
     * by the current thread, or the write lock was already held
     * by the current thread; and {@code false} otherwise.
     */
    public boolean tryLock() {
      return sync.tryWriteLock();
    }

    /**
     * Acquires the write lock if it is not held by another thread
     * within the given waiting time and the current thread has
     * not been {@linkplain Thread#interrupt interrupted}.
     * <p>
     * <p>Acquires the write lock if neither the read nor write lock
     * are held by another thread
     * and returns immediately with the value {@code true},
     * setting the write lock hold count to one. If this lock has been
     * set to use a fair ordering policy then an available lock
     * <em>will not</em> be acquired if any other threads are
     * waiting for the write lock. This is in contrast to the {@link
     * #tryLock()} method. If you want a timed {@code tryLock}
     * that does permit barging on a fair lock then combine the
     * timed and un-timed forms together:
     * <p>
     * <pre> {@code
     * if (lock.tryLock() ||
     *     lock.tryLock(timeout, unit)) {
     *   ...
     * }}</pre>
     * <p>
     * <p>If the current thread already holds this lock then the
     * hold count is incremented by one and the method returns
     * {@code true}.
     * <p>
     * <p>If the lock is held by another thread then the current
     * thread becomes disabled for thread scheduling purposes and
     * lies dormant until one of three things happens:
     * <p>
     * <ul>
     * <p>
     * <li>The write lock is acquired by the current thread; or
     * <p>
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <p>
     * <li>The specified waiting time elapses
     * <p>
     * </ul>
     * <p>
     * <p>If the write lock is acquired then the value {@code true} is
     * returned and the write lock hold count is set to one.
     * <p>
     * <p>If the current thread:
     * <p>
     * <ul>
     * <p>
     * <li>has its interrupted status set on entry to this method;
     * or
     * <p>
     * <li>is {@linkplain Thread#interrupt interrupted} while
     * acquiring the write lock,
     * <p>
     * </ul>
     * <p>
     * then {@link InterruptedException} is thrown and the current
     * thread's interrupted status is cleared.
     * <p>
     * <p>If the specified waiting time elapses then the value
     * {@code false} is returned.  If the time is less than or
     * equal to zero, the method will not wait at all.
     * <p>
     * <p>In this implementation, as this method is an explicit
     * interruption point, preference is given to responding to
     * the interrupt over normal or reentrant acquisition of the
     * lock, and over reporting the elapse of the waiting time.
     *
     * @param timeout the time to wait for the write lock
     * @param unit    the time unit of the timeout argument
     * @return {@code true} if the lock was free and was acquired
     * by the current thread, or the write lock was already held by the
     * current thread; and {@code false} if the waiting time
     * elapsed before the lock could be acquired.
     * @throws InterruptedException if the current thread is interrupted
     * @throws NullPointerException if the time unit is null
     */
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
      return sync.tryAcquireNanos(1, unit.toNanos(timeout));
    }

    /**
     * Attempts to release this lock.
     * <p>
     * <p>If the current thread is the holder of this lock then
     * the hold count is decremented. If the hold count is now
     * zero then the lock is released.  If the current thread is
     * not the holder of this lock then {@link
     * IllegalMonitorStateException} is thrown.
     *
     * @throws IllegalMonitorStateException if the current thread does not
     *                                      hold this lock
     */
    public void unlock() {
      sync.release(1);
    }

    /**
     * Returns a {@link Condition} instance for use with this
     * {@link Lock} instance.
     * <p>The returned {@link Condition} instance supports the same
     * usages as do the {@link Object} monitor methods ({@link
     * Object#wait() wait}, {@link Object#notify notify}, and {@link
     * Object#notifyAll notifyAll}) when used with the built-in
     * monitor lock.
     * <p>
     * <ul>
     * <p>
     * <li>If this write lock is not held when any {@link
     * Condition} method is called then an {@link
     * IllegalMonitorStateException} is thrown.  (Read locks are
     * held independently of write locks, so are not checked or
     * affected. However it is essentially always an error to
     * invoke a condition waiting method when the current thread
     * has also acquired read locks, since other threads that
     * could unblock it will not be able to acquire the write
     * lock.)
     * <p>
     * <li>When the condition {@linkplain Condition#await() waiting}
     * methods are called the write lock is released and, before
     * they return, the write lock is reacquired and the lock hold
     * count restored to what it was when the method was called.
     * <p>
     * <li>If a thread is {@linkplain Thread#interrupt interrupted} while
     * waiting then the wait will terminate, an {@link
     * InterruptedException} will be thrown, and the thread's
     * interrupted status will be cleared.
     * <p>
     * <li> Waiting threads are signalled in FIFO order.
     * <p>
     * <li>The ordering of lock reacquisition for threads returning
     * from waiting methods is the same as for threads initially
     * acquiring the lock, which is in the default case not specified,
     * but for <em>fair</em> locks favors those threads that have been
     * waiting the longest.
     * <p>
     * </ul>
     *
     * @return the Condition object
     */
    public Condition newCondition() {
      return sync.newCondition();
    }

    /**
     * Returns a string identifying this lock, as well as its lock
     * state.  The state, in brackets includes either the String
     * {@code "Unlocked"} or the String {@code "Locked by"}
     * followed by the {@linkplain Thread#getName name} of the owning thread.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
      Thread o = sync.getOwner();
      return super.toString() + ((o == null) ? "[Unlocked]" : "[Locked by thread " + o.getName() + "]");
    }

    /**
     * Queries if this write lock is held by the current thread.
     *
     * @return {@code true} if the current thread holds this lock and
     * {@code false} otherwise
     * @since 1.6
     */
    public boolean isHeldByCurrentThread() {
      return sync.isHeldExclusively();
    }

    /**
     * Queries the number of holds on this write lock by the current
     * thread.  A thread has a hold on a lock for each lock action
     * that is not matched by an unlock action.
     *
     * @return the number of holds on this lock by the current thread,
     * or zero if this lock is not held by the current thread
     * @since 1.6
     */
    public int getHoldCount() {
      return sync.getWriteHoldCount();
    }
  }
}
