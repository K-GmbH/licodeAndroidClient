package com.example.licodeclient;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Class to hold a future result of a calculation.
 *
 * Result can be set by setResult.
 * 
 * @param <V>
 */
public class FutureResult<V> implements Future<V> {
	public interface OnResultCallback<V> {
		void OnResult(V result);
	}

	/** the result - once it has been set */
	private volatile V mResult = null;
	/** set when cancelled or done */
	private volatile boolean mDone = false;
	/** all observers who want to know about the result reached event */
	private ConcurrentLinkedQueue<OnResultCallback<V>> mObservers = new ConcurrentLinkedQueue<FutureResult.OnResultCallback<V>>();

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		mDone = true;
		return false;
	}

	@Override
	public V get() throws InterruptedException, ExecutionException {
		while (mDone == false) {
			Thread.sleep(100);
		}
		return mResult;
	}

	@Override
	public V get(long timeout, TimeUnit unit) throws InterruptedException,
			ExecutionException, TimeoutException {

		long remainingTime = TimeUnit.MILLISECONDS.convert(timeout, unit);
		while (mDone == false && remainingTime > 0) {
			long t = 100L;
			remainingTime -= t;
			Thread.sleep(t);
		}
		return mResult;
	}

	@Override
	public boolean isCancelled() {
		return false;
	}

	@Override
	public boolean isDone() {
		return mDone;
	}

	/** set the result and as such turn this future real */
	public void setResult(V result) {
		mResult = result;
		mDone = true;

		for (OnResultCallback<V> callback : mObservers) {
			callback.OnResult(result);
		}
	}

	/** attach a result observer */
	public void addResultObserver(OnResultCallback<V> observer) {
		mObservers.add(observer);
	}

	/** remove a result observer from this future */
	public void removeResultObserver(OnResultCallback<V> observer) {
		mObservers.remove(observer);
	}
}
