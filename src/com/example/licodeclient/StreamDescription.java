package com.example.licodeclient;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.SdpObserver;
import org.webrtc.VideoRenderer;

public class StreamDescription implements StreamDescriptionInterface {
	/** current state of the stream */
	private StreamState mState = StreamState.UNKNOWN;

	/** identifier for this stream */
	private String mId;

	/** has data? */
	private boolean mData;
	/** has video? */
	private boolean mVideo;
	/** has screen stream? */
	private boolean mScreen;
	/** has audio? */
	private boolean mAudio;

	/** the attribute information */
	private JSONObject mAttributes = new JSONObject();

	/** the nick attached to this stream - if any */
	private String mNick;

	/**
	 * flag to store if stream is outgoing (true, local) or incoming (false,
	 * remote)
	 */
	private boolean mLocal;

	/** sdp constraints for the sdp */
	private MediaConstraints mSdpConstraints;

	/** flag - stores if audio is currently allowed to play, or not */
	private boolean mAudioActive = true;

	public static StreamDescription parseJson(JSONObject arg) {
		String id = null;
		boolean data = false;
		boolean video = false;
		boolean audio = false;
		boolean screen = false;
		JSONObject attr = null;
		String nick = null;

		try {
			id = arg.getString("id");
		} catch (JSONException e) {
		}
		try {
			data = arg.getBoolean("data");
		} catch (JSONException e3) {
		}
		try {
			video = arg.getBoolean("video");
		} catch (JSONException e2) {
		}
		try {
			audio = arg.getBoolean("audio");
		} catch (JSONException e1) {
		}
		try {
			screen = arg.getBoolean("screen");
		} catch (JSONException e) {
		}
		try {
			attr = arg.getJSONObject("attributes");
			if (attr != null) {
				nick = attr.getString("nick");
			}
		} catch (JSONException e) {
		}

		return new StreamDescription(id, data, video, audio, screen, attr, nick);
	}

	public StreamDescription(String id, boolean data, boolean video,
			boolean audio, boolean screen, JSONObject attr, String nick) {
		mId = id;
		mData = data;
		mVideo = video;
		mAudio = audio;
		mScreen = screen;
		if (attr != null) {
			mAttributes = attr;
		}
		mNick = nick;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.knuddels.android.activities.webrtc.StreamDescriptionInteface#toJson()
	 */
	@Override
	public JSONObject toJson() {
		JSONObject result = new JSONObject();
		try {
			result.put("data", mData);
			result.put("video", mVideo);
			result.put("audio", mAudio);
			result.put("screen", mScreen);
			if (mAttributes == null) {
				mAttributes = new JSONObject();
			}
			mAttributes.put("nick", mNick);
			result.put("attributes", mAttributes);
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return result;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.knuddels.android.activities.webrtc.StreamDescriptionInteface#getId()
	 */
	@Override
	public String getId() {
		return mId;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.knuddels.android.activities.webrtc.StreamDescriptionInteface#setId
	 * (java.lang.String)
	 */
	@Override
	public void setId(String newId) {
		if (mLocal) {
			mId = newId;
		} else {
			throw new UnsupportedOperationException(
					"May not change id of a non-local stream!");
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.knuddels.android.activities.webrtc.StreamDescriptionInteface#isLocal
	 * ()
	 */
	@Override
	public boolean isLocal() {
		return mLocal;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.knuddels.android.activities.webrtc.StreamDescriptionInteface#toJsonOffer
	 * (java.lang.String)
	 */
	@Override
	public JSONObject toJsonOffer(String state) {
		JSONObject result = new JSONObject();
		try {
			if (state != null) {
				result.put("state", state);
			}
			result.put("data", mData);
			result.put("audio", mAudio);
			result.put("video", mVideo);
			if (mAttributes == null) {
				mAttributes = new JSONObject();
			}
			mAttributes.put("nick", mNick);
			result.put("attributes", mAttributes);
		} catch (JSONException jex) {
			// TODO
			jex.printStackTrace();
		}

		return result;
	}

	public PeerConnection pc = null;
	/** the active media stream */
	private volatile MediaStream mMediaStream;
	/** currently set video renderer */
	private VideoRenderer mRenderer;

	/** access the sdp's constraints */
	public MediaConstraints sdpConstraints() {
		return mSdpConstraints;
	}

	public void initLocal(PeerConnection pc, SdpObserver sdpObserver) {
		mLocal = true;
		mState = StreamState.LOCAL;
		this.pc = pc;
		mSdpConstraints = new MediaConstraints();
		mSdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"OfferToReceiveAudio", "true"));
		mSdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"OfferToReceiveVideo", "true"));
		pc.createOffer(sdpObserver, mSdpConstraints);
	}

	public void initRemote(PeerConnection pc, SdpObserver sdpObserver) {
		mLocal = false;
		mState = StreamState.OPENING;
		this.pc = pc;
		mSdpConstraints = new MediaConstraints();
		mSdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"OfferToReceiveAudio", "true"));
		mSdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"OfferToReceiveVideo", "true"));
		pc.createOffer(sdpObserver, mSdpConstraints);
	}

	/** sets the associated media stream - if prepared */
	public void setMedia(MediaStream media) {
		mMediaStream = media;
	}

	/** access the media stream - may be null */
	public MediaStream getMedia() {
		return mMediaStream;
	}

	/** attach a renderer to the media */
	public synchronized void attachRenderer(
			LicodeConnector.VideoCallbacks videoCallbacks) {
		if (mRenderer != null) {
			return;
		}
		if (mMediaStream != null && mMediaStream.videoTracks.size() == 1) {
			mState = StreamState.ACTIVE;
			mRenderer = new VideoRenderer(videoCallbacks);
			mMediaStream.videoTracks.get(0).addRenderer(mRenderer);
		}
	}

	/** attaches a complete renderer */
	public synchronized void attachLocalRenderer(VideoRenderer renderer) {
		if (mRenderer != null) {
			return;
		}

		mRenderer = renderer;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.knuddels.android.activities.webrtc.StreamDescriptionInteface#
	 * detachRenderer()
	 */
	@Override
	public synchronized void detachRenderer() {
		if (mRenderer != null && mMediaStream != null
				&& mMediaStream.videoTracks.size() == 1) {
			mMediaStream.videoTracks.get(0).removeRenderer(mRenderer);
		}
		mRenderer = null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.knuddels.android.activities.webrtc.StreamDescriptionInteface#onClosing
	 * ()
	 */
	@Override
	public void onClosing() {
		mState = StreamState.CLOSING;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.knuddels.android.activities.webrtc.StreamDescriptionInteface#onDestroyed
	 * ()
	 */
	@Override
	public void onDestroyed() {
		mState = StreamState.DESTROYED;
		mMediaStream = null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.knuddels.android.activities.webrtc.StreamDescriptionInteface#onDisable
	 * ()
	 */
	@Override
	public void onDisable() {
		mState = StreamState.BLOCKED;
		mMediaStream = null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.knuddels.android.activities.webrtc.StreamDescriptionInteface#getState
	 * ()
	 */
	@Override
	public StreamState getState() {
		return mState;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.knuddels.android.activities.webrtc.StreamDescriptionInteface#toggleAudio
	 * ()
	 */
	@Override
	public void toggleAudio() {
		setAudioActive(!mAudioActive);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.knuddels.android.activities.webrtc.StreamDescriptionInteface#
	 * setAudioActive(boolean)
	 */
	@Override
	public void setAudioActive(boolean audioActive) {
		mAudioActive = audioActive;
		if (mMediaStream != null && mMediaStream.audioTracks.size() == 1) {
			mMediaStream.audioTracks.get(0).setEnabled(mAudioActive);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.knuddels.android.activities.webrtc.StreamDescriptionInteface#
	 * isAudioActive()
	 */
	@Override
	public boolean isAudioActive() {
		return mAudioActive;
	}

	@Override
	public String getNick() {
		if (mNick != null) {
			return mNick;
		}
		if (mAttributes != null) {
			try {
				return mAttributes.getString("nick");
			} catch (JSONException e) {
			}
		}
		return null;
	}

	/** check if this stream has been abandoned by the video server */
	public boolean isClosing() {
		return mState == StreamState.CLOSING;
	}

}
