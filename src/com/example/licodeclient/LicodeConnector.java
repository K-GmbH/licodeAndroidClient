package com.example.licodeclient;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.IceConnectionState;
import org.webrtc.PeerConnection.IceGatheringState;
import org.webrtc.PeerConnection.SignalingState;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SessionDescription.Type;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRenderer.I420Frame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;

import com.example.licodeclient.apprtc.VideoStreamsView;
import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.socketio.Acknowledge;
import com.koushikdutta.async.http.socketio.ConnectCallback;
import com.koushikdutta.async.http.socketio.EventCallback;
import com.koushikdutta.async.http.socketio.SocketIOClient;

/**
 * A simple class to connect to a licode server and provides callbacks for the
 * standard events associated with this.
 */
public class LicodeConnector implements VideoConnectorInterface {
	/** flag to store if basic initialization has happened */
	private static boolean sInitializedAndroidGlobals;
	/** socket.io client */
	volatile SocketIOClient mIoClient = null;
	/** lock object for socket communication */
	private Object mSocketLock = new Object();
	/** current state of the connection */
	volatile State mState = State.kUninitialized;
	/** description of the turn server, username, password, and url */
	JSONObject mTurnServer;
	/** stun server url */
	String mStunServerUrl;
	/** default video bandwidth */
	int mDefaultVideoBW;
	/** max video bandwidth */
	int mMaxVideoBW = 75;
	/** max audio bandwidth */
	int mMaxAudioBW = 25;
	/** list of the streams */
	ConcurrentHashMap<String, StreamDescriptionInterface> mRemoteStream = new ConcurrentHashMap<String, StreamDescriptionInterface>();
	/** list of the streams */
	HashMap<String, StreamDescription> mLocalStream = new HashMap<String, StreamDescription>();
	/** current room id */
	String mRoomId;
	/** list of all current observers */
	ConcurrentLinkedQueue<RoomObserver> mObservers = new ConcurrentLinkedQueue<LicodeConnector.RoomObserver>();
	/** local video stream */
	private VideoSource mVideoSource;
	/** local video capturer */
	private VideoCapturer mVideoCapturer;
	/** if local video stream was paused */
	private boolean mVideoStopped = false;
	/** factory for peer connections */
	private static PeerConnectionFactory sFactory;
	/** list of stun and turn servers available for all connections */
	volatile ArrayList<PeerConnection.IceServer> mIceServers = new ArrayList<PeerConnection.IceServer>();
	/** the handler for the special video chat thread */
	private static Handler sVcHandler = null;
	/** special lock object when accessing the vc handler instance */
	private static Object sVcLock = new Object();
	/** server confirmed rights */
	private boolean mPermissionPublish, mPermissionSubscribe;

	/** helper class - runnable that can be cancelled */
	private static interface CancelableRunnable extends Runnable {
		/** cancels the runnable */
		void cancel();
	}

	/** refresh token runnable */
	private CancelableRunnable mRefreshTokenRunnable;

	/** may or may not provide logging output - as desired */
	static void log(String s) {
		// TODO dk: logging?!
		System.out.println(s);
	}

	EventCallback mOnAddStream = new EventCallback() {
		@Override
		public void onEvent(JSONArray args, Acknowledge ack) {
			// [{"data":true,"id":331051653483882560,"screen":"","audio":true,"video":true}]
			log("mOnAddStream");

			try {
				StreamDescription stream = StreamDescription.parseJson(args
						.getJSONObject(0));

				boolean isLocal = mLocalStream.get(stream.getId()) != null;
				if (!isLocal) {
					mRemoteStream.put(stream.getId(), stream);
					triggerStreamAdded(stream);
				}
			} catch (JSONException e) {
			}
		}
	};
	EventCallback mOnSubscribeP2P = new EventCallback() {
		@Override
		public void onEvent(JSONArray args, Acknowledge ack) {
			// not yet relevant
		}
	};
	EventCallback mOnPublishP2P = new EventCallback() {
		@Override
		public void onEvent(JSONArray args, Acknowledge ack) {
			// not yet relevant
		}
	};
	EventCallback mOnDataStream = new EventCallback() {
		@Override
		public void onEvent(JSONArray args, Acknowledge ack) {
			log("mOnDataStream");

			try {
				JSONObject param = args.getJSONObject(0);
				String streamId = param.getString("id");
				String message = param.getString("msg");
				StreamDescriptionInterface stream = mRemoteStream.get(streamId);
				for (RoomObserver obs : mObservers) {
					obs.onStreamData(message, stream);
				}
			} catch (JSONException e) {
			}
		}
	};
	EventCallback mOnRemoveStream = new EventCallback() {
		@Override
		public void onEvent(JSONArray args, Acknowledge ack) {
			// [{"id":331051653483882560}]
			log("mOnRemoveStream");

			try {
				JSONObject param = args.getJSONObject(0);
				String streamId = param.getString("id");
				StreamDescription stream = (StreamDescription) mRemoteStream
						.get(streamId);

				if (stream != null) {
					removeStream(stream);
					mRemoteStream.remove(streamId);
					triggerStreamRemoved(stream);
				}
			} catch (JSONException e) {
			}
		}
	};
	EventCallback mDisconnect = new EventCallback() {
		@Override
		public void onEvent(JSONArray args, Acknowledge ack) {
			log("mDisconnect");
			disconnect();
		}
	};

	/** peer connection observer */
	private class MyPcObserver implements PeerConnection.Observer {
		/** the associated sdp observer */
		private LicodeSdpObserver mSdpObserver;
		/** stream description */
		private StreamDescriptionInterface mDesc;

		public MyPcObserver(LicodeSdpObserver observer,
				StreamDescriptionInterface desc) {
			mSdpObserver = observer;
			mDesc = desc;
		}

		public LicodeSdpObserver getSdpObserver() {
			return mSdpObserver;
		}

		@Override
		public void onSignalingChange(SignalingState arg0) {
		}

		@Override
		public void onRemoveStream(MediaStream arg0) {
			// stream gone?
		}

		@Override
		public void onIceGatheringChange(IceGatheringState iceGatherState) {
			if (iceGatherState == IceGatheringState.COMPLETE) {
				mSdpObserver.iceReady();
			}
		}

		@Override
		public void onIceConnectionChange(IceConnectionState arg0) {
		}

		@Override
		public void onIceCandidate(IceCandidate iceCandidate) {
		}

		@Override
		public void onError() {
			log("PeerConenctionObserver.onError");
		}

		@Override
		public void onDataChannel(DataChannel arg0) {
		}

		@Override
		public void onAddStream(final MediaStream media) {
			if (mSdpObserver.isLocal()) {
				return;
			}
			if (media.videoTracks.size() == 1 && mDesc != null) {
				((StreamDescription) mDesc).setMedia(media);
				triggerMediaAvailable(mDesc);
			}

		}

		@Override
		public void onRenegotiationNeeded() {
			log("PeerConnectionObserver.onRenegotiationNeeded");
		}
	};

	/** context/activity */
	private volatile Activity mActivity;
	/** local media stream */
	private MediaStream lMS;
	/** the currently active nick */
	private String mNick;

	public LicodeConnector() {
	}

	@Override
	public void onPause() {
		sVcHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mVideoSource != null) {
					mVideoSource.stop();
					mVideoStopped = true;
				}
			}
		});
	}

	@Override
	public void onResume() {
		sVcHandler.post(new Runnable() {
			@Override
			public void run() {
				if (mVideoSource != null && mVideoStopped) {
					mVideoSource.restart();
					mVideoStopped = false;
				}
			}
		});
	}

	@Override
	public State getState() {
		return mState;
	}

	@Override
	public boolean isConnected() {
		return mState == State.kConnected || mState == State.kConnecting;
	}

	@Override
	public void init(Activity context, String nick) {
		synchronized (sVcLock) {
			if (sVcHandler == null) {
				HandlerThread vcthread = new HandlerThread(
						"LicodeConnectorThread");
				vcthread.start();
				sVcHandler = new Handler(vcthread.getLooper());
			}
		}
		if (context == null) {
			throw new NullPointerException(
					"Failed to initialize LicodeConnector. Activity is required.");
		}
		mActivity = context;
		mState = State.kDisconnected;
		mNick = nick;

		Runnable init = new Runnable() {
			@Override
			public void run() {
				if (!sInitializedAndroidGlobals) {
					sInitializedAndroidGlobals = true;
					// newer libjingle versions have options for video and audio
					PeerConnectionFactory.initializeAndroidGlobals(mActivity);// ,
																				// true,
																				// true);
				}

				if (sFactory == null) {
					sFactory = new PeerConnectionFactory();
				}

			};
		};
		sVcHandler.post(init);
	}

	@Override
	public void setBandwidthLimits(int video, int audio) {
		mMaxVideoBW = video;
		mMaxAudioBW = audio;
	}

	@Override
	public void connect(final String token) {
		if (mState == State.kUninitialized) {
			return;
		}
		if (isConnected()) {
			return;
		}

		mState = State.kConnecting;
		mActivity.runOnUiThread(new Runnable() {

			@Override
			public void run() {
				createToken(token);
			}
		});
	}

	/** sends a token - when required */
	public void refreshVideoToken(String token) {
		token = LicodeConnector.decodeToken(token);
		if (token == null) {
			return;
		}

		try {
			JSONObject jsonToken = new JSONObject(token);
			handleTokenRefresh(jsonToken);

			sendMessageSocket("refreshToken", jsonToken, new Acknowledge() {
				@Override
				public void acknowledge(JSONArray arg0) {
					// read publish right from result
					log("Refresh token Acknowledge: " + arg0.toString());
					parseVideoTokenResponse(arg0);

					if (mPermissionPublish) {
						triggerPublishAllowed();
					} else {
						unpublish();
					}
				}
			});
		} catch (JSONException e) {
		}
	}

	@Override
	public void disconnect() {
		if (mState == State.kUninitialized || mState == State.kDisconnected
				|| mState == State.kDisconnecting) {
			return;
		}
		if (mState == State.kConnecting) {
			// TODO dk: figure out how to handle this!
		}

		sVcHandler.post(new Runnable() {
			@Override
			public void run() {
				doDisconnect();
			}
		});
	}

	/** handle actual disconnecting - from ui thread only */
	void doDisconnect() {
		mState = State.kDisconnecting;
		for (RoomObserver obs : mObservers) {
			obs.onRoomDisconnected();
		}
		Set<String> keyset = mRemoteStream.keySet();
		for (String key : keyset) {
			StreamDescription stream = (StreamDescription) mRemoteStream
					.get(key);
			removeStream(stream);
			triggerStreamRemoved(stream);
		}
		mRemoteStream.clear();

		if (mLocalStream.size() > 0) {
			unpublish();
		}

		synchronized (mSocketLock) {
			if (mIoClient != null) {
				mIoClient.disconnect();
				mIoClient = null;
			}
		}

		mState = State.kDisconnected;
	}

	/** handles time based refreshing of tokens - when they have a duration */
	void handleTokenRefresh(JSONObject jsonToken) {
		int duration = 0;

		try {
			duration = jsonToken.getInt("duration");
		} catch (JSONException e) {
			e.printStackTrace();
		}

		if (duration > 0) {
			if (mRefreshTokenRunnable != null) {
				mRefreshTokenRunnable.cancel();
			}
			mRefreshTokenRunnable = new CancelableRunnable() {
				/**
				 * keeps track if this is still to be run, or has been cancelled
				 */
				private volatile boolean mIsActive = true;

				@Override
				public void run() {
					if (!mIsActive) {
						return;
					}

					triggerRequestVideoToken();
				}

				@Override
				public void cancel() {
					mIsActive = false;
				}
			};
			long refreshTime = duration - 10;
			if (refreshTime < 1) {
				refreshTime = 1;
			}
			sVcHandler.postDelayed(mRefreshTokenRunnable, refreshTime * 1000L);
		}
	}

	/**
	 * decodes a video token into a string which can then be turned into a json
	 * object, returns null on errors
	 */
	private static final String decodeToken(String result) {
		try {
			String token = new String(Base64.decode(result.getBytes(),
					Base64.DEFAULT), "UTF-8");
			log("Licode token decoded: " + token);
			return token;
		} catch (UnsupportedEncodingException e) {
			log("Failed to decode token: " + e.getMessage());
		}
		return null;
	}

	/** called with the connection token */
	void createToken(String result) {
		if (result == null) {
			return;
		}
		String token = LicodeConnector.decodeToken(result);
		if (token == null) {
			return;
		}

		try {
			mRemoteStream.clear();
			final JSONObject jsonToken = new JSONObject(token);
			String host = jsonToken.getString("host");
			if (!host.startsWith("http://")) {
				host = "http://" + host;
			}
			handleTokenRefresh(jsonToken);
			SocketIOClient.connect(AsyncHttpClient.getDefaultInstance(), host,
					new ConnectCallback() {
						@Override
						public void onConnectCompleted(Exception err,
								SocketIOClient client) {
							if (err != null) {
								err.printStackTrace();
								return;
							}

							try {
								// workaround - 2nd connection event
								JSONObject jsonParam = new JSONObject();
								jsonParam.put("reconnect", false);
								jsonParam.put("secure",
										jsonToken.getBoolean("secure"));
								jsonParam.put("force new connection", true);

								JSONArray arg = new JSONArray();
								arg.put(jsonParam);
								client.emit("connection", arg);
								log("Licode: Connection established!");
							} catch (JSONException e) {
								e.printStackTrace();
							}
							synchronized (mSocketLock) {
								mIoClient = client;
								client.on("onAddStream", mOnAddStream);
								client.on("onSubscribeP2P", mOnSubscribeP2P);
								client.on("onPublishP2P", mOnPublishP2P);
								client.on("onDataStream", mOnDataStream);
								client.on("onRemoveStream", mOnRemoveStream);
								client.on("disconnect", mDisconnect);
							}

							sendMessageSocket("token", jsonToken,
									new Acknowledge() {
										@Override
										public void acknowledge(JSONArray result) {
											log("Licode: createToken -> connect");
											log(result.toString());
											try {
												// ["success",{"maxVideoBW":300,"id":"5384684c918b864466c853d6","streams":[],"defaultVideoBW":300,"turnServer":{"password":"","username":"","url":""},"stunServerUrl":"stun:stun.l.google.com:19302"}]
												// ["success",{"maxVideoBW":300,"id":"5384684c918b864466c853d6","streams":[{"data":true,"id":897203996079042600,"screen":"","audio":true,"video":true},{"data":true,"id":841680482029914900,"screen":"","audio":true,"video":true}],"defaultVideoBW":300,"turnServer":{"password":"","username":"","url":""},"stunServerUrl":"stun:stun.l.google.com:19302"}]
												if ("success"
														.equalsIgnoreCase(result
																.getString(0)) == false) {
													return;
												}

												JSONObject jsonObject = result
														.getJSONObject(1);
												parseVideoTokenResponse(result);

												if (jsonObject
														.has("turnServer")) {
													mTurnServer = jsonObject
															.getJSONObject("turnServer");
													String url = mTurnServer
															.getString("url");
													String usr = mTurnServer
															.getString("username");
													String pwd = mTurnServer
															.getString("password");
													if (!url.isEmpty()) {
														mIceServers
																.add(new PeerConnection.IceServer(
																		url,
																		usr,
																		pwd));
													}
												}
												if (jsonObject
														.has("stunServerUrl")) {
													mStunServerUrl = jsonObject
															.getString("stunServerUrl");
													if (!mStunServerUrl
															.isEmpty()) {
														mIceServers
																.add(new PeerConnection.IceServer(
																		mStunServerUrl));
													}
												}
												if (jsonObject
														.has("defaultVideoBW")) {
													mDefaultVideoBW = jsonObject
															.getInt("defaultVideoBW");
												}
												if (jsonObject
														.has("maxVideoBW")) {
													mMaxVideoBW = jsonObject
															.getInt("maxVideoBW");
												}

												mState = State.kConnected;

												// update room id
												mRoomId = jsonObject
														.getString("id");

												for (RoomObserver obs : mObservers) {
													obs.onRoomConnected(mRemoteStream);
												}

												// retrieve list of streams
												JSONArray streams = jsonObject
														.getJSONArray("streams");
												for (int index = 0, n = streams
														.length(); index < n; ++index) {
													// {"data":true,"id":897203996079042600,"screen":"","audio":true,"video":true}
													JSONObject arg = streams
															.getJSONObject(index);
													StreamDescription stream = StreamDescription
															.parseJson(arg);
													mRemoteStream.put(
															stream.getId(),
															stream);
													triggerStreamAdded(stream);
												}
											} catch (JSONException e) {
											}
										}
									});
						}
					});
		} catch (JSONException e) {
		}
	}

	/** send a json something on the specified channel via socket.io */
	void sendMessageSocket(String channel, Object param, Acknowledge ack) {
		synchronized (mSocketLock) {
			if (mIoClient == null) {
				return;
			}
			JSONArray jsonArgs = new JSONArray();
			jsonArgs.put(param);
			if (ack == null) {
				ack = new Acknowledge() {
					@Override
					public void acknowledge(JSONArray arg0) {
						log("LicodeConnector: No one interested in response: "
								+ arg0.toString());
					}
				};
			}
			mIoClient.emit(channel, jsonArgs, ack);
		}
	}

	void sendSDPSocket(String type, JSONObject param0, JSONObject param1,
			Acknowledge ack) {
		synchronized (mSocketLock) {
			if (mIoClient == null) {
				return;
			}
			JSONArray jsonArgs = new JSONArray();
			jsonArgs.put(param0);
			jsonArgs.put(param1);
			mIoClient.emit(type, jsonArgs, ack);
		}
	}

	void sendSDPSocket(String type, JSONArray params, Acknowledge ack) {
		synchronized (mSocketLock) {
			if (mIoClient == null) {
				return;
			}
			mIoClient.emit(type, params, ack);
		}
	}

	void sendDataSocket(String streamId, String message) {
		JSONObject param = new JSONObject();
		try {
			param.put("id", streamId);
			param.put("msg", message);
		} catch (JSONException e) {
			e.printStackTrace();
		}
		sendMessageSocket("sendDataStream", param, null);
	}

	void removeStream(StreamDescription stream) {
		stream.onClosing();
		triggerStreamRemoved(stream);
	}

	@Override
	public void unsubscribe(String streamId) {
		StreamDescription stream = (StreamDescription) mRemoteStream
				.get(streamId);

		if (stream != null) {
			disable(stream);
		}
	}

	@Override
	public void addObserver(final RoomObserver observer) {
		mObservers.add(observer);

		if (isConnected()) {
			mActivity.getWindow().getDecorView().post(new Runnable() {
				@Override
				public void run() {
					observer.onRoomConnected(mRemoteStream);
				}
			});
		}
	}

	@Override
	public void removeObserver(RoomObserver observer) {
		mObservers.remove(observer);
	}

	/** get access to the camera */
	private VideoCapturer getVideoCapturer() {
		String[] cameraFacing = { "front", "back" };
		int[] cameraIndex = { 0, 1 };
		int[] cameraOrientation = { 0, 90, 180, 270 };
		for (String facing : cameraFacing) {
			for (int index : cameraIndex) {
				for (int orientation : cameraOrientation) {
					String name = "Camera " + index + ", Facing " + facing
							+ ", Orientation " + orientation;
					VideoCapturer capturer = VideoCapturer.create(name);
					if (capturer != null) {
						log("Using camera: " + name);
						return capturer;
					}
				}
			}
		}
		throw new RuntimeException("Failed to open capturer");
	}

	// Implementation detail: bridge the VideoRenderer.Callbacks interface to
	// the
	// VideoStreamsView implementation.
	public static class VideoCallbacks implements VideoRenderer.Callbacks {
		private final VideoStreamsView view;
		private final String streamId;

		public VideoCallbacks(VideoStreamsView view, String streamId) {
			this.view = view;
			this.streamId = streamId;
		}

		@Override
		public void setSize(final int width, final int height) {
			view.queueEvent(new Runnable() {
				public void run() {
					view.setSize(streamId, width, height);
				}
			});
		}

		@Override
		public void renderFrame(I420Frame frame) {
			view.queueFrame(streamId, frame);
		}
	}

	private class LicodeSdpObserver implements SdpObserver {
		/** the sdp created locally */
		SessionDescription mLocalSdp = null;
		/** whether or not this is a publish attempt */
		boolean mIsPublish = false;
		/** the current signalling channel on socket.io */
		String mSignalChannel = "subscribe";
		/** the associated stream */
		final StreamDescription mStream;
		/** id of the offerers session */
		private int mOffererSessionId = 104;
		/** id of the answerers session */
		private int mAnswererSessionId = 0;
		/** tracks if ice candidates are all collected */
		boolean mIceReady = false;

		/** create an observer for given stream */
		LicodeSdpObserver(StreamDescription stream, boolean publishing) {
			mStream = stream;
			mIsPublish = publishing;
			mSignalChannel = mIsPublish ? "publish" : "subscribe";
		}

		public boolean isLocal() {
			return mStream == null ? false : mStream.isLocal();
		}

		/** waits for ice candidates to be gathered before triggering release */
		public void iceReady() {
			mIceReady = true;
			startConnecting();
		}

		private void startConnecting() {
			mStream.pc.createOffer(this, mStream.sdpConstraints());
		}

		@Override
		public void onCreateFailure(String arg0) {
			log("SdpObserver#onCreateFailure: " + arg0);
		}

		private SessionDescription modifySdpMaxBW(SessionDescription sdp) {
			StringBuffer desc = new StringBuffer();
			int audioLine = -1;
			int videoLine = -1;
			ArrayList<Integer> bLines = new ArrayList<Integer>();
			String[] lines = sdp.description.split("\r\n");
			for (int i = 0; i < lines.length; ++i) {
				if (lines[i].startsWith("m=audio")) {
					audioLine = i;
				} else if (lines[i].startsWith("m=video")) {
					videoLine = i;
				} else if (lines[i].startsWith("b=AS:")) {
					bLines.add(i);
				}
			}
			// TODO dk: this may want to check for existing B-Lines!
			boolean addVideoB = mMaxVideoBW > 0;
			boolean addAudioB = mMaxAudioBW > 0;
			for (int i = 0; i < lines.length; ++i) {
				desc.append(lines[i]);
				desc.append("\r\n");
				if (i == audioLine && addAudioB) {
					desc.append("b=AS:" + mMaxAudioBW + "\r\n");
				} else if (i == videoLine && addVideoB) {
					desc.append("b=AS:" + mMaxVideoBW + "\r\n");
				}
			}

			return new SessionDescription(sdp.type, desc.toString());
		}

		@Override
		public void onCreateSuccess(SessionDescription sdp) {
			if (mLocalSdp != null) {
				return;
			}

			if (mIceReady) {
				mLocalSdp = sdp;
			}
			final SessionDescription finalSdp = modifySdpMaxBW(sdp);
			mActivity.runOnUiThread(new Runnable() {
				@Override
				public void run() {
					mStream.pc.setLocalDescription(LicodeSdpObserver.this,
							finalSdp);
				}
			});
		}

		@Override
		public void onSetFailure(String arg0) {
			log("SdpObserver#onSetFailure: " + arg0);
		}

		@Override
		public void onSetSuccess() {
			if (mLocalSdp == null) {
				return;
			}
			mActivity.runOnUiThread(new Runnable() {
				@Override
				public void run() {
					if (mStream.pc.getRemoteDescription() == null) {
						sendLocalDescription();
					} else {
						// drain remote candidates?!
						// also confirm exchange with licode server!
						sendConfirmation();
					}
				}
			});
		}

		void sendLocalDescription() {
			JSONObject desc = null;
			if (mIsPublish) {
				desc = mStream.toJsonOffer("offer");
			} else {
				desc = mStream.toJsonOffer(null);
				try {
					desc.put("streamId", mStream.getId());
				} catch (JSONException e) {
				}
			}
			JSONObject p1 = new JSONObject();
			try {
				p1.put("messageType", "OFFER");
				p1.put("sdp", mLocalSdp.description);
				p1.put("tiebreaker",
						(int) (Math.random() * (Integer.MAX_VALUE - 2)) + 1);
				p1.put("offererSessionId", mOffererSessionId); // hardcoded in
																// Licode?
				p1.put("seq", 1); // should not be hardcoded, but works for now
			} catch (JSONException e) {
			}
			log("SdpObserver#sendLocalDescription; to: " + mSignalChannel
					+ "; msg: " + p1.toString());
			sendSDPSocket(mSignalChannel, desc, p1, new Acknowledge() {
				@Override
				public void acknowledge(JSONArray arg0) {
					log("SdpObserver#sendLocalDescription#sendSDPSocket#Acknowledge: "
							+ arg0.toString());

					String streamId = null;
					SessionDescription remoteSdp = null;
					try {
						// log(arg0.getString(0));
						// JSONObject jsonAnswer = arg0.getJSONObject(0);
						// licode server sends answer as string which is
						// basically a json string, though
						JSONObject jsonAnswer = new JSONObject(arg0
								.getString(0));
						boolean answer = "ANSWER".equals(jsonAnswer
								.getString("messageType"));
						if (!answer) {
							log("SdpObserver: expected ANSWER, got: "
									+ jsonAnswer.getString("messageType"));
						}
						remoteSdp = new SessionDescription(Type.ANSWER,
								jsonAnswer.getString("sdp"));

						if (mIsPublish) {
							streamId = arg0.getString(1);
						}

						mAnswererSessionId = jsonAnswer
								.getInt("answererSessionId");
					} catch (JSONException e1) {
					}

					if (mIsPublish) {
						mStream.setId(streamId);
						mLocalStream.put(streamId, mStream);
					}

					final SessionDescription finalRemoteSdp = remoteSdp;
					mActivity.runOnUiThread(new Runnable() {
						@Override
						public void run() {
							mStream.pc.setRemoteDescription(
									LicodeSdpObserver.this, finalRemoteSdp);
						}
					});
				}
			});
		}

		void sendConfirmation() {
			JSONObject p0 = mStream.toJsonOffer("ok");
			try {
				p0.put("streamId", mStream.getId());
				p0.put("messageType", "OK");
				p0.put("offererSessionId", mOffererSessionId);
				p0.put("answererSessionId", mAnswererSessionId);
				p0.put("seq", 1);
				// p0.put("sdp", " ");
			} catch (JSONException e) {
			}
			sendSDPSocket(mSignalChannel, p0, p0, null);
		}
	}

	public MediaConstraints makePcConstraints() {
		MediaConstraints pcConstraints = new MediaConstraints();
		pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
				"RtpDataChannels", "true"));
		pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
				"EnableDtlsSrtp", "true"));
		pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
				"DtlsSrtpKeyAgreement", "true"));
		return pcConstraints;
	}

	@Override
	public void publish(final VideoStreamsView view) {
		if (mPermissionPublish) {
			sVcHandler.post(new Runnable() {
				@Override
				public void run() {
					doPublish(view);
				}
			});
		}
	}

	/** begin streaming to server - MUST run on VcThread */
	void doPublish(VideoStreamsView view) {
		if (mVideoCapturer != null) {
			return;
		}

		MediaConstraints videoConstraints = new MediaConstraints();
		videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"maxWidth", "320"));
		videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"maxHeight", "240"));
		videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"maxFrameRate", "10"));
		MediaConstraints audioConstraints = new MediaConstraints();
		audioConstraints.optional.add(new MediaConstraints.KeyValuePair(
				"googEchoCancellation2", "true"));
		audioConstraints.optional.add(new MediaConstraints.KeyValuePair(
				"googNoiseSuppression", "true"));
		lMS = sFactory.createLocalMediaStream("ARDAMS");

		if (videoConstraints != null) {
			mVideoCapturer = getVideoCapturer();
			mVideoSource = sFactory.createVideoSource(mVideoCapturer,
					videoConstraints);
			VideoTrack videoTrack = sFactory.createVideoTrack("ARDAMSv0",
					mVideoSource);
			lMS.addTrack(videoTrack);
		}
		if (audioConstraints != null) {
			AudioTrack audioTrack = sFactory.createAudioTrack("ARDAMSa0",
					sFactory.createAudioSource(audioConstraints));
			lMS.addTrack(audioTrack);
			audioTrack.setEnabled(false);
		}

		StreamDescription stream = new StreamDescription("", false, true, true,
				false, null, mNick);
		MediaConstraints pcConstraints = makePcConstraints();
		MyPcObserver pcObs = new MyPcObserver(new LicodeSdpObserver(stream,
				true), stream);

		PeerConnection pc = sFactory.createPeerConnection(mIceServers,
				pcConstraints, pcObs);
		pc.addStream(lMS, new MediaConstraints());

		stream.setMedia(lMS);
		if (view != null) {
			stream.attachRenderer(new VideoCallbacks(view,
					VideoStreamsView.LOCAL_STREAM_ID));
		}
		stream.initLocal(pc, pcObs.getSdpObserver());
	}

	@Override
	public void unpublish() {
		sVcHandler.post(new Runnable() {
			@Override
			public void run() {
				doUnpublish();
			}
		});
	}

	/** stop all streams from being cast to the server */
	void doUnpublish() {
		for (String key : mLocalStream.keySet()) {
			final StreamDescription stream = mLocalStream.get(key);
			if (stream != null && stream.isLocal()) {
				stream.pc.removeStream(lMS);

				for (RoomObserver obs : mObservers) {
					obs.onStreamRemoved(stream);
				}

				if (mObservers.size() == 0) {
					destroy(stream);
				}
			}
		}
		mLocalStream.clear();

		if (lMS != null) {
			lMS.dispose();
		}
		if (mVideoCapturer != null) {
			mVideoCapturer.dispose();
		}

		lMS = null;
		mVideoCapturer = null;
		if (mVideoSource != null && !mVideoStopped) {
			mVideoSource.stop();
		}
		mVideoSource = null;
	}

	@Override
	public void subscribe(final StreamDescriptionInterface stream) {
		sVcHandler.post(new Runnable() {
			@Override
			public void run() {
				doSubscribe((StreamDescription) stream);
			}
		});
	}

	void doSubscribe(final StreamDescription stream) {
		if (stream.isLocal()) {
			return;
		}

		if (stream.getMedia() != null) {
			// already subscribed!
			triggerMediaAvailable(stream);
			return;
		}

		// Uncomment to get ALL WebRTC tracing and SENSITIVE libjingle logging.
		// NOTE: this _must_ happen while |factory| is alive!
		// Logging.enableTracing("logcat:",
		// EnumSet.of(Logging.TraceLevel.TRACE_ALL),
		// Logging.Severity.LS_SENSITIVE);

		MyPcObserver pcObs = new MyPcObserver(new LicodeSdpObserver(stream,
				false), stream);
		PeerConnection pc = sFactory.createPeerConnection(mIceServers,
				makePcConstraints(), pcObs);

		stream.initRemote(pc, pcObs.getSdpObserver());
	}

	/**
	 * triggers the event that a stream was added - will eventually happen with
	 * delay
	 */
	void triggerStreamAdded(StreamDescription stream) {
		for (RoomObserver obs : mObservers) {
			obs.onStreamAdded(stream);
		}
	}

	/** triggers the event that a stream was removed */
	void triggerStreamRemoved(StreamDescription stream) {
		for (RoomObserver obs : mObservers) {
			obs.onStreamRemoved(stream);
		}
		if (mObservers.size() == 0) {
			destroy(stream);
		}
	}

	/** triggers the event that publish has been allowed now */
	void triggerPublishAllowed() {
		for (RoomObserver obs : mObservers) {
			obs.onPublishAllowed();
		}
	}

	/**
	 * triggers that subscribe was successful, and media is now available to
	 * stream
	 */
	void triggerMediaAvailable(StreamDescriptionInterface stream) {
		for (RoomObserver obs : mObservers) {
			obs.onStreamMediaAvailable(stream);
		}
	}

	/**
	 * triggers that a new video token is required - very soon - or the
	 * connection will end
	 */
	void triggerRequestVideoToken() {
		for (RoomObserver obs : mObservers) {
			obs.onRequestRefreshToken();
		}
	}

	@Override
	public void destroy(final StreamDescriptionInterface param0) {
		final StreamDescription stream = (StreamDescription) param0;
		if (stream == null) {
			return;
		}
		sVcHandler.post(new Runnable() {
			@Override
			public void run() {
				if (stream.pc != null) {
					stream.pc.close();
					stream.pc.dispose();
				}

				stream.onDestroyed();

				if (stream.isLocal()) {
					sendMessageSocket("unpublish", stream.getId(), null);
				}
			}
		});
	}

	@Override
	public void disable(final StreamDescriptionInterface param0) {
		final StreamDescription stream = (StreamDescription) param0;
		if (stream.isLocal()) {
			return;
		}
		sVcHandler.post(new Runnable() {
			@Override
			public void run() {
				sendMessageSocket("unsubscribe", stream.getId(), null);
				stream.detachRenderer();

				stream.pc.close();
				stream.pc.dispose();
				stream.onDisable();
			}
		});
	}

	@Override
	public void setAudioEnabled(boolean enabled) {
		if (mState != State.kConnected || lMS == null) {
			return;
		}

		for (AudioTrack audioTrack : lMS.audioTracks) {
			audioTrack.setEnabled(enabled);
		}
	}

	@Override
	public void setActivity(Activity activity) {
		mActivity = activity;
	}

	@Override
	public Map<String, StreamDescriptionInterface> getRemoteStreams() {
		return mRemoteStream;
	}

	@Override
	public boolean isPublishing() {
		return mLocalStream.size() > 0;
	}

	@Override
	public void attachLocalStream(final VideoStreamsView vsv) {
		sVcHandler.post(new Runnable() {
			@Override
			public void run() {
				for (String key : mLocalStream.keySet()) {
					StreamDescription stream = (StreamDescription) mLocalStream
							.get(key);
					stream.attachRenderer(new VideoCallbacks(vsv,
							VideoStreamsView.LOCAL_STREAM_ID));
					break;
				}
			}
		});
	}

	@Override
	public void detachLocalStream() {
		sVcHandler.post(new Runnable() {
			@Override
			public void run() {
				for (String key : mLocalStream.keySet()) {
					StreamDescriptionInterface stream = mLocalStream.get(key);
					if (stream != null) {
						stream.detachRenderer();
					}
				}
			}
		});
	}

	@Override
	public void post(Runnable r) {
		sVcHandler.post(r);
	}

	@Override
	public void attachRenderer(StreamDescriptionInterface stream,
			VideoStreamsView mVsv) {
		((StreamDescription) stream)
				.attachRenderer(new LicodeConnector.VideoCallbacks(mVsv, stream
						.getId()));
	}

	@Override
	public void setNick(String nickname) {
		mNick = nickname;
	}

	@Override
	public boolean requestPublish() {
		if (mPermissionPublish) {
			sVcHandler.post(new Runnable() {
				@Override
				public void run() {
					triggerPublishAllowed();
				}
			});
			return true;
		}
		return false;
	}

	/**
	 * parse an acknowledge to a token sent, analyze for permissions, disconnect
	 * on error
	 */
	protected void parseVideoTokenResponse(JSONArray arg) {
		// TODO dk: parse all the other things that come with the response? TURN
		// Server, etc?
		boolean success = false;
		String message = "";
		try {
			success = "success".equalsIgnoreCase(arg.getString(0));
			if (success) {
				JSONObject obj = arg.getJSONObject(1);
				boolean subscribe = false;
				boolean publish = false;
				if (obj.has("permissions")) {
					JSONObject permissions = obj.getJSONObject("permissions");
					subscribe = permissions.has("subscribe")
							&& permissions.getBoolean("subscribe");
					publish = permissions.has("publish")
							&& permissions.getBoolean("publish");
				}
				mPermissionSubscribe = subscribe;
				mPermissionPublish = publish;
			} else {
				message = arg.get(1).toString();
			}
		} catch (JSONException e) {
			log(e.getMessage());
		}

		if (!success) {
			log("Token failed: " + message);
			disconnect();
		}
	}
}
