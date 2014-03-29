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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.IceGatheringState;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import android.util.Log;

import com.koushikdutta.async.http.socketio.Acknowledge;

public class Peer {
	private static final String TAG = "Session";

	private Session mSession;

	public String id;
	private Queue<IceCandidate> queuedCandidates;
	public PeerConnection pc;
	private final PCObserver pcObserver = new PCObserver();
	private final SDPObserver sdpObserver = new SDPObserver();
	private MediaConstraints sdpMediaConstraints;
	private SignalingParameters sp;

	public Peer(Session s, String id, PeerConnectionFactory factory,
			MediaStream lMS, SignalingParameters sp) {
		this.mSession = s;
		this.id = id;
		this.sp = sp;

		sdpMediaConstraints = new MediaConstraints();
		sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"OfferToReceiveAudio", "true"));
		sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
				"OfferToReceiveVideo", "true"));

		queuedCandidates = new ConcurrentLinkedQueue<IceCandidate>();
		this.pc = factory.createPeerConnection(sp.iceServers, sp.pcConstraints,
				pcObserver);
		if (lMS != null) {
			pc.addStream(lMS, new MediaConstraints());
		}

		if (sp.initiator) {
			Log.d(TAG, "Creating offer...");
			pc.createOffer(sdpObserver, sdpMediaConstraints);
		}
	}

	public void onCandidateEvent(JSONObject data, Acknowledge ack) {
		try {
			IceCandidate candidate = new IceCandidate((String) data.get("id"),
					data.getInt("label"), (String) data.get("candidate"));
			Log.d(TAG, "Enqueueing remote ICE candidate: " + candidate);
			if (queuedCandidates == null) { // remote SDP already set
				pc.addIceCandidate(candidate);
			} else {
				// queuedCandidates.add(candidate);
				queuedCandidates.offer(candidate);
			}

		} catch (JSONException e) {
			Log.d(TAG, "Error decoding ice candidate message!");
			e.printStackTrace();
		}
	}

	public void onSdpEvent(JSONObject data, Acknowledge ack) {
		try {
			SessionDescription remoteSdp;
			remoteSdp = new SessionDescription(
					SessionDescription.Type.fromCanonicalForm(data
							.getString("type")), data.getString("sdp"));
			Log.d(TAG, "Setting remote " + remoteSdp.type);

			pc.setRemoteDescription(sdpObserver, remoteSdp);
		} catch (JSONException e1) {
			Log.e(TAG, "Error decoding SDP JSON!");
			e1.printStackTrace();
		}
	}

	// Implementation detail: observe ICE & stream changes and react
	// accordingly.
	private class PCObserver implements PeerConnection.Observer {
		@Override
		public void onIceCandidate(final IceCandidate candidate) {
			Log.d(TAG, "PeerConnection.Observer.onIceCandidate: " + candidate);

			JSONObject json = new JSONObject();
			try {
				json.put("type", "candidate");
				json.put("label", candidate.sdpMLineIndex);
				json.put("id", candidate.sdpMid);
				json.put("candidate", candidate.sdp);

				// TODO use correct peer ID instead of broadcasting
				Log.d(TAG, "sending ice candidate: " + json);
				mSession.sendMessage("asdf", true, json);
			} catch (JSONException e) {
				Log.e(TAG, "Error creating and sending ice candidate message!");
			}
		}

		@Override
		public void onError() {
			Log.d(TAG, "PeerConnection.Observer.onError");
			throw new RuntimeException("PeerConnection error!");
		}

		@Override
		public void onSignalingChange(PeerConnection.SignalingState newState) {
			Log.d(TAG, "PeerConnection.Observer.onSignalingChange: newState = "
					+ newState);
		}

		@Override
		public void onIceConnectionChange(
				PeerConnection.IceConnectionState newState) {
			Log.d(TAG,
					"PeerConnection.Observer.onIceConnectionChange: newState = "
							+ newState);
		}

		@Override
		public void onIceGatheringChange(
				PeerConnection.IceGatheringState newState) {
			Log.d(TAG, "PeerConnection.Observer.onIceGatheringChange: "
					+ newState);
			if (newState == IceGatheringState.NEW) {
			} else if (newState == IceGatheringState.GATHERING) {
			} else if (newState == IceGatheringState.COMPLETE) {
			}
		}

		@Override
		public void onAddStream(final MediaStream stream) {
			mSession.onPeerAddedStream(stream);
		}

		@Override
		public void onRemoveStream(final MediaStream stream) {
			Log.d(TAG, "PeerConnection.Observer.onRemoveStream");
			stream.videoTracks.get(0).dispose();
		}

		@Override
		public void onDataChannel(final DataChannel dc) {
			Log.d(TAG, "PeerConnection.Observer.onDataChannel");
			throw new RuntimeException(
					"AppRTC doesn't use data channels, but got: " + dc.label()
							+ " anyway!");
		}

		@Override
		public void onRenegotiationNeeded() {
			Log.d(TAG, "PeerConnection.Observer.onRenegotiationNeeded");
			// TODO rexchange SDP O/A
		}
	}

	// Implementation detail: handle offer creation/signaling and answer
	// setting, as well as adding remote ICE candidates once answer SDP is set
	private class SDPObserver implements SdpObserver {
		@Override
		public void onCreateSuccess(final SessionDescription origSdp) {
			Log.d(TAG, "SdpObserver.onCreateSuccess()");

			// Set my offer or answer as my local description
			SessionDescription sdp = new SessionDescription(origSdp.type,
					origSdp.description);
			pc.setLocalDescription(sdpObserver, sdp);

			// Send my offer or answer to the remote end
			JSONObject offerMsg = new JSONObject();
			try {
				offerMsg.put("type", sdp.type);
				offerMsg.put("sdp", sdp.description);
				Log.d(TAG, "Sending " + origSdp.type);

				// TODO use correct peer ID instead of broadcasting
				mSession.sendMessage("asdf", true, offerMsg);
			} catch (JSONException e) {
				Log.e(TAG, "Error creating or sending SDP message!");
				e.printStackTrace();
			}
		}

		@Override
		public void onSetSuccess() {
			Log.d(TAG, "SdpObserver.onSetSuccess");

			if (sp.initiator) {
				// We sent the OFFER, and received and SET the ANSWER
				if (pc.getRemoteDescription() != null) {
					/*
					 * We already set our local offer and just set the remote
					 * answer, both sides have both SDPs, so drain ICE
					 * candidates.
					 */
					drainRemoteIceCandidates();
				}
			} else {
				// We received an OFFER, and will now create an ANSWER
				if (pc.getLocalDescription() == null) {
					// We just set the remote OFFER, now create ANSWER.
					Log.d(TAG, "Set remote OFFER, creating ANSWER");
					pc.createAnswer(SDPObserver.this, sdpMediaConstraints);
				} else {
					/*
					 * We already sent our answer and set it as local
					 * description; both sides have both SDPs, so drain ICE
					 * candidates.
					 */
					drainRemoteIceCandidates();
				}
			}
		}

		@Override
		public void onCreateFailure(final String error) {
			Log.d(TAG, "SdpObserver.onCreateFailure");
			throw new RuntimeException("createSDP error: " + error);
		}

		@Override
		public void onSetFailure(final String error) {
			Log.d(TAG, "SdpObserver.onSetFailure");
			throw new RuntimeException("setSDP error: " + error);
		}

		public void drainRemoteIceCandidates() {
			Log.d(TAG, "Draining remote ICE candidates...");
			if (queuedCandidates != null) {
				for (IceCandidate candidate : queuedCandidates) {
					pc.addIceCandidate(candidate);
				}
				queuedCandidates.clear();
				queuedCandidates = null;
			} else {
				Log.d(TAG, "queuedCandidates is NULL!");
			}
		}
	}
}