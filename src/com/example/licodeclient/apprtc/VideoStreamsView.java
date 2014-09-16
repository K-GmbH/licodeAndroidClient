package com.example.licodeclient.apprtc;

/*
 * libjingle
 * Copyright 2013, Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import org.webrtc.VideoRenderer.I420Frame;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.util.Log;

/**
 * A GLSurfaceView{,.Renderer} that efficiently renders YUV frames from local &
 * remote VideoTracks using the GPU for CSC. Clients will want to call the
 * constructor, setSize() and updateFrame() as appropriate, but none of the
 * other public methods of this class are of interest to clients (only to system
 * classes).
 * 
 * Libjingle version modified to support any number of streams.
 */
@TargetApi(Build.VERSION_CODES.HONEYCOMB)
public class VideoStreamsView extends GLSurfaceView implements
		GLSurfaceView.Renderer {
	/** stream id for the local stream - only one allowed */
	public static String LOCAL_STREAM_ID = "LOCAL_STREAM";

	private static class FrameDescription {
		/** android screen coordinates, allows checking if something changed */
		int left = -1, top = -1, right = -1, bottom = -1;
		/** index of the associated float buffer */
		int bufferIndex = -1;
		/** stored frame to render next */
		I420Frame frameToRender;
	}

	private final static String TAG = "VideoStreamsView";
	/** minimum nano seconds between drawing two frames - over all video streams */
	private static final long MIN_NANOS_BETWEEN_FRAMES = 66666666L;
	/** stores system nano time of last rendering */
	private volatile long mLastRendered = 0L;
	// [0] are local Y,U,V, [1] are remote Y,U,V.
	private int[][] yuvTextures = { { -1, -1, -1 }, // 0
			{ -1, -1, -1 }, // 1
			{ -1, -1, -1 }, // 2
			{ -1, -1, -1 }, // 3
			{ -1, -1, -1 }, // 4
			{ -1, -1, -1 }, // 5
			{ -1, -1, -1 }, // 6
			{ -1, -1, -1 }, // 7
			{ -1, -1, -1 } }; // 8
	/** track for each texture triplet if a frame is available */
	private boolean[] seenFrameForTexture = new boolean[yuvTextures.length];
	/** vertices for each texture triplet */
	private FloatBuffer[] mVertices = new FloatBuffer[yuvTextures.length];
	/** focused texture - the one that's drawn above everything else */
	private int mFocusIndex = -1;
	private int posLocation = -1;
	private long lastFPSLogTime = System.nanoTime();
	private long numFramesSinceLastLog = 0;
	private FramePool framePool = new FramePool();
	// Accessed on multiple threads! Must be synchronized.
	private HashMap<String, FrameDescription> frameDescriptions = new HashMap<String, FrameDescription>();
	/** width/height dimension of this view as set by layout */
	private float mWidth = 1, mHeight = 1;
	/** render request in progress */
	private AtomicBoolean mRenderRequested = new AtomicBoolean(false);
	/** background color in rgb, [0;1] */
	private float mRed = 0, mGreen = 0, mBlue = 0;

	public VideoStreamsView(Context c) {
		super(c);
		setPreserveEGLContextOnPause(true);
		setEGLContextClientVersion(2);
		setRenderer(this);
		setRenderMode(RENDERMODE_WHEN_DIRTY);

		for (int i = 0; i < mVertices.length; ++i) {
			mVertices[i] = directNativeFloatBuffer(new float[] { -1, 1, -1,
					.9f, -.9f, 1, -.9f, .9f });
			// { -1, 1, -1, -1, 1, 1, 1, -1 });
		}
	}

	/** create a new frame description and put it in the map */
	private FrameDescription createFrameDescription(String streamId) {
		FrameDescription result = new FrameDescription();
		frameDescriptions.put(streamId, result);
		result.bufferIndex = findFreeTexture();
		return result;
	}

	/** Queue |frame| to be uploaded. */
	public void queueFrame(final String stream, I420Frame frame) {
		// Paying for the copy of the YUV data here allows CSC and painting time
		// to get spent on the render thread instead of the UI thread.
		abortUnless(FramePool.validateDimensions(frame), "Frame too large!");
		// boolean needToScheduleRender;
		synchronized (frameDescriptions) {
			// A new render needs to be scheduled (via updateFrames()) iff there
			// isn't
			// already a render scheduled, which is true iff framesToRender is
			// empty.
			// needToScheduleRender = frameDescriptions.isEmpty();
			FrameDescription desc = frameDescriptions.get(stream);
			// if (desc == null)
			// {
			// desc = createFrameDescription(stream);
			// }
			if (desc != null && desc.bufferIndex != -1) {
				I420Frame frameToDrop = desc.frameToRender;
				if (desc.bufferIndex != -1) {
					final I420Frame frameCopy = framePool.takeFrame(frame)
							.copyFrom(frame);
					desc.frameToRender = frameCopy;
				}
				if (frameToDrop != null) {
					framePool.returnFrame(frameToDrop);
				}
			}
		}
		long dt = System.nanoTime() - mLastRendered;
		if (dt > MIN_NANOS_BETWEEN_FRAMES
				&& mRenderRequested.compareAndSet(false, true)) {
			queueEvent(new Runnable() {
				public void run() {
					updateFrames();
				}
			});
		}
	}

	// Upload the planes from |framesToRender| to the textures owned by this
	// View.
	private void updateFrames() {
		synchronized (frameDescriptions) {
			for (String key : frameDescriptions.keySet()) {
				FrameDescription desc = frameDescriptions.get(key);
				if (desc.frameToRender != null) {
					if (desc.bufferIndex != -1) {
						seenFrameForTexture[desc.bufferIndex] = true;
						texImage2D(desc.frameToRender,
								yuvTextures[desc.bufferIndex]);
					}
					framePool.returnFrame(desc.frameToRender);
					desc.frameToRender = null;
				}
			}
		}
		// abortUnless(frameUpdated, "Nothing to render!");
		requestRender();
	}

	/** check for a free texture in the yuv-texture array */
	private int findFreeTexture() {
		boolean taken[] = new boolean[yuvTextures.length];
		for (String key : frameDescriptions.keySet()) {
			FrameDescription desc = frameDescriptions.get(key);
			if (desc.bufferIndex != -1) {
				taken[desc.bufferIndex] = true;
			}
		}
		for (int i = 0; i < taken.length; ++i) {
			if (taken[i] == false) {
				return i;
			}
		}
		return -1;
	}

	/** register a stream to allow it to send images */
	public boolean addStream(String streamId) {
		synchronized (frameDescriptions) {
			if (frameDescriptions.containsKey(streamId)) {
				return false;
			}

			frameDescriptions.put(streamId, createFrameDescription(streamId));
			return true;
		}
	}

	/** disables a previously active stream */
	public void removeStream(String stream) {
		synchronized (frameDescriptions) {
			FrameDescription desc = frameDescriptions.remove(stream);
			if (desc != null && desc.bufferIndex != -1) {
				seenFrameForTexture[desc.bufferIndex] = false;

				if (mFocusIndex == desc.bufferIndex) {
					mFocusIndex = -1;
				}
			}
		}

		// if (frameDescriptions.size() == 0)
		// {
		requestRender();
		// }
	}

	/** position a stream on this view */
	public void setStreamDimensions(String stream, int left, int top,
			int right, int bottom, boolean focus) {
		FrameDescription desc = null;
		synchronized (frameDescriptions) {
			desc = frameDescriptions.get(stream);
			if (desc == null) {
				desc = createFrameDescription(stream);
			}
		}

		if (desc == null || desc.bufferIndex == -1) {
			return;
		}

		if (mFocusIndex == desc.bufferIndex && focus == false) {
			mFocusIndex = -1;
		} else if (focus) {
			mFocusIndex = desc.bufferIndex;
		}

		boolean leftChanged = left != desc.left;
		boolean topChanged = top != desc.top;
		boolean rightChanged = right != desc.right;
		boolean bottomChanged = bottom != desc.bottom;

		desc.left = left;
		desc.right = right;
		desc.top = top;
		desc.bottom = bottom;

		float width = mWidth;
		float height = mHeight;

		FloatBuffer vertices = mVertices[desc.bufferIndex];
		if (leftChanged) {
			float x0 = (left - width) / width;
			vertices.put(0, x0);
			vertices.put(2, x0);
		}
		if (topChanged) {
			float y0 = (height - top) / height;
			vertices.put(1, y0);
			vertices.put(5, y0);
		}
		if (rightChanged) {
			float x1 = (right - width) / width;
			vertices.put(4, x1);
			vertices.put(6, x1);
		}
		if (bottomChanged) {
			float y1 = (height - bottom) / height;
			vertices.put(3, y1);
			vertices.put(7, y1);
		}

		if (leftChanged || topChanged || rightChanged || bottomChanged) {
			requestRender();
		}
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right,
			int bottom) {
		super.onLayout(changed, left, top, right, bottom);

		if (changed) {
			mWidth = .5f * (right - left);
			mHeight = .5f * (bottom - top);
			synchronized (frameDescriptions) {
				for (String key : frameDescriptions.keySet()) {
					FrameDescription desc = frameDescriptions.get(key);

					if (desc.bufferIndex != -1) {
						FloatBuffer vertices = mVertices[desc.bufferIndex];
						float x0 = (desc.left - mWidth) / mWidth;
						vertices.put(0, x0);
						vertices.put(2, x0);

						float y0 = (mHeight - desc.top) / mHeight;
						vertices.put(1, y0);
						vertices.put(5, y0);

						float x1 = (desc.right - mWidth) / mWidth;
						vertices.put(4, x1);
						vertices.put(6, x1);

						float y1 = (mHeight - desc.bottom) / mHeight;
						vertices.put(3, y1);
						vertices.put(7, y1);
					}
				}
			}
			requestRender();
		}
	}

	/** Inform this View of the dimensions of frames coming from |stream|. */
	public void setSize(String stream, int width, int height) {
		int bufferIndex = -1;
		// synchronize this?!
		synchronized (frameDescriptions) {
			FrameDescription desc = frameDescriptions.get(stream);
			// if (desc == null)
			// {
			// desc = createFrameDescription(stream);
			// }
			if (desc != null) {
				bufferIndex = desc.bufferIndex;
			}
		}

		if (bufferIndex == -1) {
			return;
		}

		// Generate 3 texture ids for Y/U/V and place them into |textures|,
		// allocating enough storage for |width|x|height| pixels.
		int[] textures = yuvTextures[bufferIndex];
		if (textures[0] != -1) {
			GLES20.glDeleteTextures(3, textures, 0);
		}

		GLES20.glGenTextures(3, textures, 0);
		for (int i = 0; i < 3; ++i) {
			int w = i == 0 ? width : width / 2;
			int h = i == 0 ? height : height / 2;
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
			GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
					w, h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
			GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
					GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
			GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
					GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
			GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
					GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
			GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
					GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
		}
		checkNoGLES2Error();
	}

	@Override
	public void onSurfaceChanged(GL10 unused, int width, int height) {
		GLES20.glViewport(0, 0, width, height);
		checkNoGLES2Error();
	}

	@Override
	public void onDrawFrame(GL10 unused) {
		GLES20.glClearColor(mRed, mGreen, mBlue, 1.0f);
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
		int focus = mFocusIndex;

		for (int i = 0; i < yuvTextures.length; ++i) {
			if (seenFrameForTexture[i] && focus != i) {
				drawRectangle(yuvTextures[i], mVertices[i]);
			}
		}

		if (focus != -1 && seenFrameForTexture[focus]) {
			drawRectangle(yuvTextures[focus], mVertices[focus]);
		}

		++numFramesSinceLastLog;
		long now = System.nanoTime();
		mLastRendered = now;
		mRenderRequested.set(false);
		if (lastFPSLogTime == -1 || now - lastFPSLogTime > 1e9) {
			double fps = numFramesSinceLastLog / ((now - lastFPSLogTime) / 1e9);
			Log.d(TAG, "Rendered FPS: " + fps);
			lastFPSLogTime = now;
			numFramesSinceLastLog = 1;
		}
		checkNoGLES2Error();
	}

	@Override
	public void onSurfaceCreated(GL10 unused, EGLConfig config) {
		int program = GLES20.glCreateProgram();
		addShaderTo(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_STRING, program);
		addShaderTo(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_STRING, program);

		GLES20.glLinkProgram(program);
		int[] result = new int[] { GLES20.GL_FALSE };
		result[0] = GLES20.GL_FALSE;
		GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, result, 0);
		abortUnless(result[0] == GLES20.GL_TRUE,
				GLES20.glGetProgramInfoLog(program));
		GLES20.glUseProgram(program);

		GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "y_tex"), 0);
		GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "u_tex"), 1);
		GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "v_tex"), 2);

		// Actually set in drawRectangle(), but queried only once here.
		posLocation = GLES20.glGetAttribLocation(program, "in_pos");

		int tcLocation = GLES20.glGetAttribLocation(program, "in_tc");
		GLES20.glEnableVertexAttribArray(tcLocation);
		GLES20.glVertexAttribPointer(tcLocation, 2, GLES20.GL_FLOAT, false, 0,
				textureCoords);

		GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		checkNoGLES2Error();
	}

	// Wrap a float[] in a direct FloatBuffer using native byte order.
	private static FloatBuffer directNativeFloatBuffer(float[] array) {
		FloatBuffer buffer = ByteBuffer.allocateDirect(array.length * 4)
				.order(ByteOrder.nativeOrder()).asFloatBuffer();
		buffer.put(array);
		buffer.flip();
		return buffer;
	}

	// Upload the YUV planes from |frame| to |textures|.
	private void texImage2D(I420Frame frame, int[] textures) {
		for (int i = 0; i < 3; ++i) {
			ByteBuffer plane = frame.yuvPlanes[i];
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
			int w = i == 0 ? frame.width : frame.width / 2;
			int h = i == 0 ? frame.height : frame.height / 2;
			abortUnless(w == frame.yuvStrides[i], frame.yuvStrides[i] + "!="
					+ w);
			GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
					w, h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
					plane);
		}
		checkNoGLES2Error();
	}

	// Draw |textures| using |vertices| (X,Y coordinates).
	private void drawRectangle(int[] textures, FloatBuffer vertices) {
		for (int i = 0; i < 3; ++i) {
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
		}

		GLES20.glVertexAttribPointer(posLocation, 2, GLES20.GL_FLOAT, false, 0,
				vertices);
		GLES20.glEnableVertexAttribArray(posLocation);

		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
		checkNoGLES2Error();
	}

	// Compile & attach a |type| shader specified by |source| to |program|.
	private static void addShaderTo(int type, String source, int program) {
		int[] result = new int[] { GLES20.GL_FALSE };
		int shader = GLES20.glCreateShader(type);
		GLES20.glShaderSource(shader, source);
		GLES20.glCompileShader(shader);
		GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, 0);
		abortUnless(result[0] == GLES20.GL_TRUE,
				GLES20.glGetShaderInfoLog(shader) + ", source: " + source);
		GLES20.glAttachShader(program, shader);
		GLES20.glDeleteShader(shader);
		checkNoGLES2Error();
	}

	// Poor-man's assert(): die with |msg| unless |condition| is true.
	private static void abortUnless(boolean condition, String msg) {
		if (!condition) {
			throw new RuntimeException(msg);
		}
	}

	// Assert that no OpenGL ES 2.0 error has been raised.
	private static void checkNoGLES2Error() {
		int error = GLES20.glGetError();
		abortUnless(error == GLES20.GL_NO_ERROR, "GLES20 error: " + error);
	}

	/** opengl background bit */
	public void setGlBackground(int color) {
		mRed = Color.red(color) / 255.0f;
		mGreen = Color.green(color) / 255.0f;
		mBlue = Color.blue(color) / 255.0f;
		requestRender();
	}

	// Texture Coordinates mapping the entire texture.
	private static final FloatBuffer textureCoords = directNativeFloatBuffer(new float[] {
			0, 0, 0, 1, 1, 0, 1, 1 });

	// Pass-through vertex shader.
	private static final String VERTEX_SHADER_STRING = //
	"varying vec2 interp_tc;\n" + //
			"\n" + //
			"attribute vec4 in_pos;\n" + //
			"attribute vec2 in_tc;\n" + //
			"\n" + //
			"void main() {\n" + //
			"  gl_Position = in_pos;\n" + //
			"  interp_tc = in_tc;\n" + //
			"}\n";//

	// YUV to RGB pixel shader. Loads a pixel from each plane and pass through
	// the
	// matrix.
	private static final String FRAGMENT_SHADER_STRING = //
	"precision mediump float;\n" + //
			"varying vec2 interp_tc;\n" + //
			"\n" + //
			"uniform sampler2D y_tex;\n" + //
			"uniform sampler2D u_tex;\n" + //
			"uniform sampler2D v_tex;\n" + //
			"\n" + //
			"void main() {\n" + //
			"  float y = texture2D(y_tex, interp_tc).r;\n" + //
			"  float u = texture2D(u_tex, interp_tc).r - .5;\n" + //
			"  float v = texture2D(v_tex, interp_tc).r - .5;\n" + //
			// CSC according to http://www.fourcc.org/fccyvrgb.php
			"  gl_FragColor = vec4(y + 1.403 * v, " + //
			"                      y - 0.344 * u - 0.714 * v, " + //
			"                      y + 1.77 * u, 1);\n" + //
			"}\n";//
}
