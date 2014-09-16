package com.example.licodeclient;

import org.json.JSONObject;

public interface StreamDescriptionInterface {

	/** stream states - mostly remote stuff, or local */
	public enum StreamState {
		UNKNOWN, OPENING, ACTIVE, CLOSING, DESTROYED, LOCAL, BLOCKED,
	}

	public abstract JSONObject toJson();

	/** access this streams unique id */
	public abstract String getId();

	/** new id */
	public abstract void setId(String newId);

	/** check if this is outgoing (local, true), or incoming (remote, false) */
	public abstract boolean isLocal();

	/** generate an offer json object - with given state value */
	public abstract JSONObject toJsonOffer(String state);

	/** detach a previously attached renderer */
	public abstract void detachRenderer();

	/** server signaled end of stream - should no longer be interesting */
	public abstract void onClosing();

	/** local controller instance removed this stream - MUST NOT be used anymore */
	public abstract void onDestroyed();

	/** disable/block a stream, can later be subscribed again */
	public abstract void onDisable();

	/** retrieve current state of this stream */
	public abstract StreamState getState();

	/** toggles audio between active and mute */
	public abstract void toggleAudio();

	/** sets the audio allowed state - audio plays, or audio is muted */
	public abstract void setAudioActive(boolean audioActive);

	/** checks if audio for this stream is currently playing, or muted */
	public abstract boolean isAudioActive();

	/** retrieve the nick associated with this stream - may return null */
	public abstract String getNick();

}