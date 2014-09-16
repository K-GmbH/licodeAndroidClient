package com.example.licodeclient;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * special layout class to handle the video streams
 */
public class VideoGridLayout extends ViewGroup {
	/** resolution ratio for the streams */
	private static final float VIDEO_RES_RATIO = .75f;
	/** how many video streams in one row */
	private int mColumns = 4;
	/** how many rows of video streams */
	private int mRows = 2;
	/** size of a child */
	private int mChildWidth = 320, mChildHeight = 240;
	/** current order number - for sorting the children */
	private AtomicInteger mCurrentOrder = new AtomicInteger(0x100);
	/** ordered list of elements for one layout pass */
	private ArrayList<View> mOrderedList = new ArrayList<View>();
	/** the instance of the zoomed object - may be null */
	private View mZoomedChild = null;
	/** the first column of the zoomed position */
	private int mZoomedPosition = 0;
	/** the control elements to position over a zoomed element */
	private View mControlElements = null;
	/** the video display element for all streams */
	private View mVideoDisplay = null;
	/**
	 * whether or not the grid layout actually desires a height (false), or not
	 * (true)
	 */
	private boolean mCollapsed = false;

	public VideoGridLayout(Context context) {
		super(context);
	}

	public VideoGridLayout(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public VideoGridLayout(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	/**
	 * Any layout manager that doesn't scroll will want this.
	 */
	@Override
	public boolean shouldDelayChildPressedState() {
		return false;
	}

	/**
	 * Ask all children to measure themselves and compute the measurement of
	 * this layout based on the children.
	 */
	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int maxWidth = View.MeasureSpec.getSize(widthMeasureSpec);
		mChildWidth = maxWidth / mColumns;
		mChildHeight = (int) (mChildWidth * VIDEO_RES_RATIO);
		int desiredHeight = mChildHeight * mRows;

		int childWidthSpecs = View.MeasureSpec.makeMeasureSpec(mChildWidth,
				View.MeasureSpec.EXACTLY);
		int childHeightSpecs = View.MeasureSpec.makeMeasureSpec(mChildHeight,
				View.MeasureSpec.EXACTLY);
		int zoomedWidthSpecs = View.MeasureSpec.makeMeasureSpec(
				2 * mChildWidth, View.MeasureSpec.EXACTLY);
		int zoomedHeightSpecs = View.MeasureSpec.makeMeasureSpec(
				2 * mChildHeight, View.MeasureSpec.EXACTLY);
		int count = getChildCount();
		int childState = 0;

		int slots = mColumns * mRows;
		for (int i = 0; i < count; ++i) {
			View child = getChildAt(i);
			if (child == mControlElements && child.getVisibility() != View.GONE) {
				child.measure(zoomedWidthSpecs, zoomedHeightSpecs);
				childState = combineMeasuredStates(childState,
						child.getMeasuredState());
			} else if (child == mVideoDisplay) {
				child.measure(widthMeasureSpec, heightMeasureSpec);
				childState = combineMeasuredStates(childState,
						child.getMeasuredState());
			} else if (slots > 0 && child.getVisibility() != View.GONE) {
				--slots;
				if (child == mZoomedChild) {
					child.measure(zoomedWidthSpecs, zoomedHeightSpecs);
				} else {
					child.measure(childWidthSpecs, childHeightSpecs);
				}
				childState = combineMeasuredStates(childState,
						child.getMeasuredState());
			}
		}

		if (slots >= mColumns) {
			int realRows = mRows - (slots / mColumns);
			// real rows must be at least 2, or mRows
			realRows = Math.max(Math.min(mRows, 2), realRows);
			desiredHeight = realRows * mChildHeight;
		}
		if (mCollapsed) {
			desiredHeight = 0;
		}

		int desiredHeightSpec = resolveSizeAndState(desiredHeight,
				heightMeasureSpec,
				childState << View.MEASURED_HEIGHT_STATE_SHIFT);
		setMeasuredDimension(
				resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
				desiredHeightSpec);
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right,
			int bottom) {
		int parentLeft = 0;
		int parentTop = 0;
		int parentRight = right - left;
		int parentBottom = bottom - top;

		int count = getChildCount();
		int slots = mColumns * mRows;

		mOrderedList.clear();
		for (int i = 0; i < count; ++i) {
			View child = getChildAt(i);
			if (mOrderedList.size() == slots) {
				break;
			} else if (child == mControlElements || child == mVideoDisplay) {
				continue;
			} else if (mOrderedList.size() < slots
					&& child.getVisibility() != View.GONE) {
				int curOrder = ((LayoutParams) child.getLayoutParams()).order;
				int k = 0;
				for (int n = mOrderedList.size(); k < n; ++k) {
					if (((LayoutParams) mOrderedList.get(k).getLayoutParams()).order >= curOrder) {
						break;
					}
				}
				mOrderedList.add(k, child);
			}
		}

		for (int i = 0, n = mOrderedList.size(); i < n; ++i) {
			View child = mOrderedList.get(i);
			if (child == mZoomedChild && mRows > 1) {
				continue;
			}
			((LayoutParams) child.getLayoutParams()).gridPosition = i;
			int x = i % mColumns;
			int y = i / mColumns;

			int child_left = parentLeft + x * mChildWidth;
			int child_top = parentTop + y * mChildHeight;
			int child_right = parentLeft + (x + 1) * mChildWidth;
			int child_bottom = parentTop + (y + 1) * mChildHeight;
			child.layout(child_left, child_top, child_right, child_bottom);

			if (child.getVisibility() == View.INVISIBLE) {
				child.setVisibility(View.VISIBLE);
			}
		}

		synchronized (this) {
			// can only zoom if at least 2 rows
			if (mZoomedChild != null && mRows > 1 && mZoomedPosition >= 0) {
				int x0 = mZoomedPosition % mColumns;
				int y0 = mZoomedPosition / mColumns;
				int x1 = x0 + 2;
				int y1 = y0 + 2;

				int zoomed_left = parentLeft + x0 * mChildWidth;
				int zoomed_top = parentTop + y0 * mChildHeight;
				int zoomed_right = parentLeft + x1 * mChildWidth;
				int zoomed_bottom = parentTop + y1 * mChildHeight;
				mZoomedChild.layout(zoomed_left, zoomed_top, zoomed_right,
						zoomed_bottom);
				if (mControlElements != null) {
					mControlElements.layout(zoomed_left, zoomed_top,
							zoomed_right, zoomed_bottom);
				}

				for (int y = y0; y < y1; ++y) {
					for (int x = x0; x < x1; ++x) {
						int index = x + y * mColumns;
						if (index < mOrderedList.size()) {
							View view = mOrderedList.get(index);
							if (view != mZoomedChild) {
								view.setVisibility(View.INVISIBLE);
							}
						}
					}
				}
			}
		}

		mVideoDisplay.layout(parentLeft, parentTop, parentRight, parentBottom);
	}

	/** retrieve currently measured child width and height */
	public Point getChildSize() {
		return new Point(mChildWidth, mChildHeight);
	}

	/**
	 * set the grid layout, number of columns and rows - implicitly requests a
	 * layout pass!
	 */
	public void setGridDimensions(int columns, int rows) {
		if (mColumns != columns || mRows != rows) {
			mColumns = columns;
			mRows = rows;
			requestLayout();
		}
	}

	public static class LayoutParams extends ViewGroup.LayoutParams {
		/** the information on where to sort this element */
		public int order = 0;
		/** actual position in the grid this time */
		int gridPosition = 0;

		public LayoutParams(Context c, AttributeSet attrs) {
			super(c, attrs);
		}

		public LayoutParams(int width, int height) {
			super(width, height);
		}

		public LayoutParams(ViewGroup.LayoutParams source) {
			super(source);
		}

		/** create layout params with given order */
		public LayoutParams(int order) {
			super(MATCH_PARENT, MATCH_PARENT);
			this.order = order;
		}
	}

	@Override
	public LayoutParams generateLayoutParams(AttributeSet attrs) {
		LayoutParams result = new LayoutParams(getContext(), attrs);
		result.order = mCurrentOrder.incrementAndGet();
		return result;
	}

	@Override
	protected android.view.ViewGroup.LayoutParams generateDefaultLayoutParams() {
		LayoutParams result = new LayoutParams(LayoutParams.MATCH_PARENT,
				LayoutParams.MATCH_PARENT);
		if (mChildHeight > 0) {
			result.width = mChildWidth;
			result.height = mChildHeight;
		}
		result.order = mCurrentOrder.incrementAndGet();
		return result;
	}

	@Override
	protected android.view.ViewGroup.LayoutParams generateLayoutParams(
			ViewGroup.LayoutParams p) {
		LayoutParams result = new LayoutParams(p);
		result.order = mCurrentOrder.incrementAndGet();
		return result;
	}

	@Override
	protected boolean checkLayoutParams(android.view.ViewGroup.LayoutParams p) {
		return p instanceof LayoutParams;
	}

	/**
	 * signals that the given view was just tapped. This indicates the view
	 * should switch from normal to enlarged display, or back to normal?
	 * Implicitly calls requestLayout!
	 * 
	 * @param v
	 *            The tapped view.
	 * @return true if the element is now zoomed, false if it was returned to
	 *         normal size
	 */
	public boolean onTapped(View v) {
		if (v == null || v.getParent() != this) {
			return false;
		}

		if (mZoomedChild != v) {
			setZoomedChild(v);
		} else {
			setZoomedChild(null);
		}
		requestLayout();
		return v == mZoomedChild;
	}

	/**
	 * selects a child to be displayed in a zoomed way, unsets if null is passed
	 * as parameter
	 */
	public void setZoomedChild(View v) {
		synchronized (this) {
			mZoomedChild = v;

			if (v != null) {
				v.bringToFront();
				if (mControlElements != null) {
					mControlElements.bringToFront();
				}

				int gridpos = ((LayoutParams) v.getLayoutParams()).gridPosition;
				mZoomedPosition = gridpos % mColumns;
				if (mZoomedPosition >= mColumns / 2) {
					// move left if in right half
					mZoomedPosition = mZoomedPosition - 1;
				}

				// move up one row if in lower half
				mZoomedPosition += mColumns
						* (gridpos / mColumns + (gridpos / mColumns >= mRows / 2 ? -1
								: 0));
			}
		}
	}

	/** access current zoomed view */
	public View getZoomedChild() {
		return mZoomedChild;
	}

	/**
	 * set a child to be considered the control elements which will always be
	 * drawn over the zoomed child
	 */
	public void setControlElements(View v) {
		mControlElements = v;
		if (v != null) {
			bringChildToFront(v);
		}
	}

	/** retrieve currently set control view */
	public View getControlElements() {
		return mControlElements;
	}

	/**
	 * set the full background view - typically a surface view so its drawing
	 * order is implicitly last
	 */
	public void setVideoElement(View v) {
		mVideoDisplay = v;
	}

	/** flag to collapse or open the video grid */
	public void setCollapsed(boolean flag) {
		if (mCollapsed != flag) {
			mCollapsed = flag;
			requestLayout();
		}
	}
}
