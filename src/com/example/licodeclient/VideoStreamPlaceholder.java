package com.example.licodeclient;

import android.content.Context;
import android.view.View;

import com.example.licodeclient.apprtc.VideoStreamsView;

public class VideoStreamPlaceholder extends View {
	/** associated stream's id */
	private String mStreamId;
	/** the master display for all video streams */
	private VideoStreamsView mVsv;
	/** whether or not this video stream is zoomed */
	private boolean mZoomed = false;

	public VideoStreamPlaceholder(Context context, VideoStreamsView vsv,
			String streamId) {
		super(context);
		mVsv = vsv;
		mStreamId = streamId;
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right,
			int bottom) {
		super.onLayout(changed, left, top, right, bottom);

		// if (changed)
		{
			mVsv.setStreamDimensions(mStreamId, left, top, right, bottom,
					mZoomed);
		}
	}

	/** sets the zoomed state of this placeholder view */
	public void setZoomed(boolean zoomed) {
		mZoomed = zoomed;
	}

	/** retrieve the associated stream's id */
	public String getStreamId() {
		return mStreamId;
	}

}
