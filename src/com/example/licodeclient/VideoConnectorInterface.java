package com.example.licodeclient;

import java.util.Map;

import android.app.Activity;

import com.example.licodeclient.apprtc.VideoStreamsView;

public interface VideoConnectorInterface {

	/** provides callbacks for specific events that can occur in a room */
	public interface RoomObserver {
		/** connected to room - with the given streams already in the room */
		void onRoomConnected(Map<String, StreamDescriptionInterface> streamList);

		/** connection to room lost */
		void onRoomDisconnected();

		/**
		 * signals that a publish slot is now reserved and available for this
		 * user
		 */
		void onPublishAllowed();

		/** signals that the given stream was added to the room */
		void onStreamAdded(StreamDescriptionInterface stream);

		/**
		 * signals that the given stream now has media available to display -
		 * after subscribe request!
		 */
		void onStreamMediaAvailable(StreamDescriptionInterface stream);

		/**
		 * signals that the given stream has just been removed from the room,
		 * MUST call destroy(stream)
		 */
		void onStreamRemoved(StreamDescriptionInterface stream);

		/** some data received on given stream */
		void onStreamData(String message, StreamDescriptionInterface stream);

		/**
		 * signal that the current token is about to expire - a new one needs to
		 * be sent
		 */
		void onRequestRefreshToken();
	}

	/** various states of the connection to the server */
	public enum State {
		kUninitialized, kDisconnected, kConnecting, kConnectingWaitingForToken, kConnected, kDisconnecting
	}

	public abstract void onPause();

	public abstract void onResume();

	/** retrieve current state */
	public abstract State getState();

	/** check if currently connected, or connecting */
	public abstract boolean isConnected();

	/** initializes basic stuff */
	public abstract void init(Activity context, String nick);

	/** sets the bandwidth limits for video and audio transport */
	public abstract void setBandwidthLimits(int video, int audio);

	/** connect with the given token */
	public abstract void connect(String token);

	/** send a new token, refreshing an old one, or changing roles */
	public abstract void refreshVideoToken(String token);

	/** tells the connector to severe connection */
	public abstract void disconnect();

	/** stop receiving a particular stream */
	public abstract void unsubscribe(String streamId);

	/** new observer */
	public abstract void addObserver(RoomObserver observer);

	/** observer gone */
	public abstract void removeObserver(RoomObserver observer);

	/**
	 * requests the right to publish a video @return true if allowed, will then
	 * request publish on the callback
	 */
	public abstract boolean requestPublish();

	/** begin streaming to the server - MUST run on UiThread */
	public abstract void publish(VideoStreamsView view);

	/** stop a stream from being sent to the server */
	public abstract void unpublish();

	/** requests to receive the given stream */
	public abstract void subscribe(StreamDescriptionInterface stream);

	/** remove the last bits and pieces of a stream */
	public abstract void destroy(StreamDescriptionInterface stream);

	/**
	 * prevent a stream from being received for now - it's still available for
	 * later reconnecting
	 */
	public abstract void disable(StreamDescriptionInterface stream);

	/** (un)pause audio from streaming for the moment */
	public abstract void setAudioEnabled(boolean enabled);

	/** resets the activity - allows freeing up a previously held instance */
	public abstract void setActivity(Activity activity);

	/** access the remote stream list */
	public abstract Map<String, StreamDescriptionInterface> getRemoteStreams();

	/**
	 * check if currently a publishing stream is running, the user is
	 * broadcasting
	 */
	public abstract boolean isPublishing();

	public abstract void attachLocalStream(VideoStreamsView vsv);

	public abstract void detachLocalStream();

	/** run something on the vc thread */
	public abstract void post(Runnable r);

	/**
	 * attaches a default renderer to the stream, which associates it with the
	 * given view
	 */
	public abstract void attachRenderer(StreamDescriptionInterface stream,
			VideoStreamsView mVsv);

	/** set the current active nick */
	public abstract void setNick(String nickname);

}