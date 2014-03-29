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

package org.jgao.socketrtc;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.appspot.apprtc.VideoStreamsView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.IceServer;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.StatsObserver;
import org.webrtc.StatsReport;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRenderer.I420Frame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.koushikdutta.async.http.AsyncHttpClient;
import com.koushikdutta.async.http.socketio.Acknowledge;
import com.koushikdutta.async.http.socketio.ConnectCallback;
import com.koushikdutta.async.http.socketio.EventCallback;
import com.koushikdutta.async.http.socketio.SocketIOClient;

public class Session {
	private static final String TAG = "Session";

	private final Context context;
	private final VideoStreamsView vsv; // TODO extend this to more than one

	private SignalingParameters sp;
	private SocketIOClient mSocket;
	private SocketIOEventHandler socketIOEventHandler = new SocketIOEventHandler();

	// WebRTC stack objects that should exist for all PeerConnections for the
	// duration of this session
	private HashMap<String, Peer> peers = new HashMap<String, Peer>();
	private PeerConnectionFactory pcFactory;
	private VideoSource videoSource;
	private boolean videoSourceStopped;
	private MediaStream localMediaStream;

	private MediaConstraints sdpMediaConstraints;

	// Synchronize on quit[0] to avoid teardown-related crashes.
	private final Boolean[] quit = new Boolean[] { false };

	/**
	 * play around with ways to execute things on the UI thread
	 * 
	 * http://stackoverflow.com/questions/13974661/runonuithread-vs-looper-
	 * getmainlooper-post-in-android
	 * 
	 * http://stackoverflow.com/questions/1845678/android-ui-thread
	 * 
	 * 
	 * @param r
	 */
	private void runOnUiThread(Runnable r) {
		// Method 1 - easiest
		// activity.runOnUiThread(r);

		// Method 2 - post to main Looper
		// if we are on UI thread, execute directly in-line from caller
		// (same behavior as Activity.runOnUiThread()
		if (Looper.myLooper() == Looper.getMainLooper()) {
			r.run();
		} else {
			new Handler(Looper.getMainLooper()).post(r);
		}
	}

	private class SocketIOEventHandler implements EventCallback {
		private Handler mHandler = new Handler(Looper.getMainLooper());

		@Override
		public void onEvent(final JSONArray jsonArray,
				final Acknowledge acknowledge) {
			mHandler.post(new Runnable() {
				@Override
				public void run() {
					Log.d(TAG, "SocketIOEventHandler: " + jsonArray);
					JSONObject data;
					String type;
					try {
						data = jsonArray.getJSONObject(0);
						type = data.getString("type");
					} catch (JSONException e) {
						Log.e(TAG,
								"Error geting JSONObject 'data' or field 'data.type' from jsonArray: "
										+ jsonArray);
						return;
					}

					// dispatch appropriately TODO not all to the same peer
					if ("hello".equals(type)) {
						onHelloEvent(data, acknowledge);
					} else if ("candidate".equals(type)) {
						// onCandidateEvent(data, acknowledge);
						peers.get("asdf").onCandidateEvent(data, acknowledge);
					} else if ("offer".equalsIgnoreCase(type)) {
						// onSdpEvent(data, acknowledge);
						peers.get("asdf").onSdpEvent(data, acknowledge);
					} else if ("answer".equalsIgnoreCase(type)) {
						// onSdpEvent(data, acknowledge);
						peers.get("asdf").onSdpEvent(data, acknowledge);
					}

				}
			});
		}
	}

	public void onHelloEvent(JSONObject data, Acknowledge ack) {
		sdpMediaConstraints = new MediaConstraints();
		sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"OfferToReceiveAudio", "true"));
		sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"OfferToReceiveVideo", "true"));

		// Parse signaling parameters from server
		try {
			boolean initiator = data.has("makeOffer")
					&& data.getBoolean("makeOffer");

			// TODO use ICE servers from server
			final LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<PeerConnection.IceServer>();
			iceServers.add(new PeerConnection.IceServer(
					"stun:stun.l.google.com:19302"));

			addTurnIfNecessary(iceServers);

			MediaConstraints pcConstraints = new MediaConstraints();
			pcConstraints.optional.add(new MediaConstraints.KeyValuePair(
					"RtpDataChannels", "true"));
			pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
					"OfferToReceiveVideo", "true"));
			pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
					"OfferToReceiveAudio", "true"));

			MediaConstraints videoConstraints = new MediaConstraints();
			MediaConstraints audioConstraints = new MediaConstraints();

			sp = new SignalingParameters(iceServers, initiator, pcConstraints,
					videoConstraints, audioConstraints);

			// Start publishing both video and audio
			startWebRTCStack();
		} catch (JSONException e) {
			Log.e(TAG, "Error downloading signaling parameters!");
			e.printStackTrace();
		}
	}

	/**
	 * This is where the WebRTC stuff kicks off!
	 * 
	 * Create a local media stream from video track and audio track, create a
	 * new Peer for each other peer I'm connected to, (each of these Peers has a
	 * PeerConnection object)
	 * 
	 * */
	private void startWebRTCStack() {
		// TODO only restart WebRTC stack if not already connected?
		// (maybe this was a RECONNECT to the socketio server?)

		// createDataChannelToRegressionTestBug2302(pc);
		startPublishing(true, true);

		// Add a Peer, which kicks off PeerConnection
		addPeer("the-only-peer", 0);
	}

	public void startPublishing(boolean publishVideo, boolean publishAudio) {
		// Uncomment to get ALL WebRTC tracing and SENSITIVE libjingle logging.
		// NOTE: this _must_ happen while |pcFactory| is alive!
		// enableWebRtcLogging();

		// enableVideoStatsLogger();

		// Add local video and audio TRACKS to local media STREAM
		if (publishVideo || publishAudio) {
			Log.d(TAG, "Creating local video and audio source...");
			localMediaStream = pcFactory.createLocalMediaStream("ARDAMS");

			// Add video, subject to constraints
			if (publishVideo && sp.videoConstraints != null) {
				VideoCapturer capturer = getVideoCapturer();
				videoSource = pcFactory.createVideoSource(capturer,
						sp.videoConstraints);
				VideoTrack videoTrack = pcFactory.createVideoTrack("ARDAMSv0",
						videoSource);

				// render my video stream locally on my screen
				videoTrack.addRenderer(new VideoRenderer(new VideoCallbacks(
						vsv, VideoStreamsView.Endpoint.LOCAL)));

				localMediaStream.addTrack(videoTrack);
			}

			// Add audio, subject to constraints
			if (publishAudio && sp.audioConstraints != null) {
				localMediaStream.addTrack(pcFactory.createAudioTrack(
						"ARDAMSa0",
						pcFactory.createAudioSource(sp.audioConstraints)));
			}
		}
	}

	private EventCallback onDisconnectEvent = new EventCallback() {
		@Override
		public void onEvent(final JSONArray arg, Acknowledge ack) {
			Log.d(TAG, "socket received message: " + arg);
			runOnUiThread(new Runnable() {
				public void run() {
					// does this get called again even when we initialize the
					// disconnect?
					Log.d(TAG, "onDisconnectEvent");
					disconnect();
				}
			});
		}
	};

	private void addTurnIfNecessary(LinkedList<IceServer> iceServers) {
		// Check if there is a TURN server included in the ICE servers list
		boolean isTurnPresent = false;
		for (PeerConnection.IceServer server : iceServers) {
			if (server.uri.startsWith("turn:")) {
				isTurnPresent = true;
				break;
			}
		}

		// If no TURN server, get one // TODO
		if (!isTurnPresent) {
			// iceServers.add(new
			// PeerConnection.IceServer("turn:numb.viagenie.ca","username",
			// "password-here"));
		}
	}

	public Session(Context context, VideoStreamsView vsv) {
		this.context = context;
		this.vsv = vsv;

		// initializeAndroidGlobals takes a Context, but is untyped to allow
		// building on different platforms
		abortUnless(PeerConnectionFactory.initializeAndroidGlobals(context),
				"Failed to initializeAndroidGlobals");
		pcFactory = new PeerConnectionFactory();
		Log.d(TAG, "created peer connection factory.");

		AudioManager audioManager = ((AudioManager) context
				.getSystemService(Context.AUDIO_SERVICE));
		// TODO(fischman): figure out how to do this Right(tm) and remove the
		// suppression.
		@SuppressWarnings("deprecation")
		boolean isWiredHeadsetOn = audioManager.isWiredHeadsetOn();
		audioManager.setMode(isWiredHeadsetOn ? AudioManager.MODE_IN_CALL
				: AudioManager.MODE_IN_COMMUNICATION);
		audioManager.setSpeakerphoneOn(!isWiredHeadsetOn);
	}

	public void connect(final String url) {
		SocketIOClient.connect(AsyncHttpClient.getDefaultInstance(), url,
				new ConnectCallback() {
					@Override
					public void onConnectCompleted(Exception ex,
							SocketIOClient socket) {
						if (ex != null) {
							Log.e(TAG, "Exception on SocketIOClient connect.");
							ex.printStackTrace();
							return;
						}
						Log.d(TAG, "SocketIOClient connected.");
						mSocket = socket;

						// Register event handlers
						mSocket.on("disconnect", onDisconnectEvent);
						mSocket.on("message", socketIOEventHandler);
					}
				});
	}

	/**
	 * Disconnect from the signaling channel
	 */
	public void disconnect() {
		// Disconnect from remote resources and dispose of local resources
		synchronized (quit[0]) {
			if (quit[0]) {
				return;
			}
			quit[0] = true;

			// dispose of Peers
			// this iteration may not be thread safe, btw
			for (String id : peers.keySet()) {
				removePeer(id);
			}
			if (mSocket != null) {
				mSocket.disconnect();
				mSocket = null;
			}
			if (videoSource != null) {
				// TODO crashes here on exit if no other connection? does it?
				videoSource.dispose();
				videoSource = null;
			}
			if (pcFactory != null) {
				pcFactory.dispose();
				pcFactory = null;
			}
			// activity.finish();
		}
	}

	/**
	 * Cycle through likely device names for the camera and return the first
	 * capturer that works, or crash if none do.
	 * 
	 * @return the first VideoCapturer that works
	 */
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
						Log.d(TAG, "Using camera: " + name);
						return capturer;
					}
				}
			}
		}
		throw new RuntimeException("Failed to open capturer");
	}

	// Implementation detail: bridge the VideoRenderer.Callbacks interface to
	// the VideoStreamsView implementation.
	private class VideoCallbacks implements VideoRenderer.Callbacks {
		private final VideoStreamsView view;
		private final VideoStreamsView.Endpoint stream;

		public VideoCallbacks(VideoStreamsView view,
				VideoStreamsView.Endpoint stream) {
			this.view = view;
			this.stream = stream;
		}

		@Override
		public void setSize(final int width, final int height) {
			view.queueEvent(new Runnable() {
				public void run() {
					view.setSize(stream, width, height);
				}
			});
		}

		@Override
		public void renderFrame(I420Frame frame) {
			view.queueFrame(stream, frame);
		}
	}

	// Poor-man's assert(): die with |msg| unless |condition| is true.
	private static void abortUnless(boolean condition, String msg) {
		if (!condition) {
			throw new RuntimeException(msg);
		}
	}

	// Mangle SDP to prefer ISAC/16000 over any other audio codec.
	private String preferISAC(String sdpDescription) {
		String[] lines = sdpDescription.split("\n");
		int mLineIndex = -1;
		String isac16kRtpMap = null;
		Pattern isac16kPattern = Pattern
				.compile("^a=rtpmap:(\\d+) ISAC/16000[\r]?$");
		for (int i = 0; (i < lines.length)
				&& (mLineIndex == -1 || isac16kRtpMap == null); ++i) {
			if (lines[i].startsWith("m=audio ")) {
				mLineIndex = i;
				continue;
			}
			Matcher isac16kMatcher = isac16kPattern.matcher(lines[i]);
			if (isac16kMatcher.matches()) {
				isac16kRtpMap = isac16kMatcher.group(1);
				continue;
			}
		}
		if (mLineIndex == -1) {
			Log.d(TAG, "No m=audio line, so can't prefer iSAC");
			return sdpDescription;
		}
		if (isac16kRtpMap == null) {
			Log.d(TAG, "No ISAC/16000 line, so can't prefer iSAC");
			return sdpDescription;
		}
		String[] origMLineParts = lines[mLineIndex].split(" ");
		StringBuilder newMLine = new StringBuilder();
		int origPartIndex = 0;
		// Format is: m=<media> <port> <proto> <fmt> ...
		newMLine.append(origMLineParts[origPartIndex++]).append(" ");
		newMLine.append(origMLineParts[origPartIndex++]).append(" ");
		newMLine.append(origMLineParts[origPartIndex++]).append(" ");
		newMLine.append(isac16kRtpMap).append(" ");
		for (; origPartIndex < origMLineParts.length; ++origPartIndex) {
			if (!origMLineParts[origPartIndex].equals(isac16kRtpMap)) {
				newMLine.append(origMLineParts[origPartIndex]).append(" ");
			}
		}
		lines[mLineIndex] = newMLine.toString();
		StringBuilder newSdpDescription = new StringBuilder();
		for (String line : lines) {
			newSdpDescription.append(line).append("\n");
		}
		return newSdpDescription.toString();
	}

	// get ALL WebRTC tracing and SENSITIVE libjingle logging.
	// NOTE: this _must_ happen while |pcFactory| is alive!
	private void enableWebRtcLogging() {
		Log.d(TAG,
				"Enabling ALL WebRTC tracing and SENSITIVE libjingle logging...");
		Logging.enableTracing("logcat:",
				EnumSet.of(Logging.TraceLevel.TRACE_ALL),
				Logging.Severity.LS_SENSITIVE);
	}

	// This is a recurring runnable on the VideoStreamView thread.
	private void enableVideoStatsLogger(String id) {
		Log.d(TAG, "Enabling stats logger...");
		{
			final PeerConnection finalPC = peers.get(id).pc;
			final Runnable repeatedStatsLogger = new Runnable() {
				public void run() {
					synchronized (quit[0]) {
						if (quit[0]) {
							return;
						}
						final Runnable runnableThis = this;
						boolean success = finalPC.getStats(new StatsObserver() {
							public void onComplete(StatsReport[] reports) {
								for (StatsReport report : reports) {
									Log.d(TAG, "Stats: " + report.toString());
								}
								vsv.postDelayed(runnableThis, 10000);
							}
						}, null);
						if (!success) {
							throw new RuntimeException(
									"getStats() return false!");
						}
					}
				}
			};
			vsv.postDelayed(repeatedStatsLogger, 10000);
		}
	}

	// Just for fun (and to regression-test bug 2302) make sure that
	// DataChannels can be created, queried, and disposed.
	private static void createDataChannelToRegressionTestBug2302(
			PeerConnection pc) {
		DataChannel dc = pc
				.createDataChannel("dcLabel", new DataChannel.Init());
		abortUnless("dcLabel".equals(dc.label()), "WTF?");
		dc.close();
		dc.dispose();
	}

	public void onPause() {
		vsv.onPause();
		if (videoSource != null) {
			videoSource.stop();
			videoSourceStopped = true;
		}
	}

	public void onResume() {
		vsv.onResume();
		if (videoSource != null && videoSourceStopped) {
			videoSource.restart();
		}
	}

	public void onPeerAddedStream(final MediaStream stream) {
		Log.d(TAG, "PeerConnection.Observer.onAddStream");
		abortUnless(stream.audioTracks.size() <= 1
				&& stream.videoTracks.size() <= 1, "Weird-looking stream: "
				+ stream);

		if (stream.videoTracks.size() == 1) {
			stream.videoTracks.get(0).addRenderer(
					new VideoRenderer(new VideoCallbacks(vsv,
							VideoStreamsView.Endpoint.REMOTE)));
		}
	}
	
	public void sendMessage(String to, boolean broadcast, JSONObject data)
			throws JSONException {
		// TODO copy data instead of modifying it?
		data.put("to", to);
		data.put("broadcast", broadcast);
		mSocket.emit("message", new JSONArray().put(data));
	}

	/**
	 * From https://github.com/pchab/AndroidRTC
	 */
	private void addPeer(String id, int endPoint) {
		Peer peer = new Peer(this, id, pcFactory, localMediaStream, sp);
		peers.put(id, peer);
		// endPoints[endPoint] = true;
	}

	/**
	 * From https://github.com/pchab/AndroidRTC
	 */
	private void removePeer(String id) {
		Peer peer = peers.get(id);
		peer.pc.close();
		peer.pc.dispose();
		peers.remove(peer.id);
		// endPoints[peer.endPoint] = false;
	}
}
