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

import org.appspot.apprtc.UnhandledExceptionHandler;
import org.appspot.apprtc.VideoStreamsView;

import android.app.Activity;
import android.graphics.Point;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;

public class MainActivity extends Activity {
	private static final String TAG = "MainActivity";

	private Session mSession;
	private VideoStreamsView vsv;
	private Toast logToast;

	// DEBUG additional session on same device
	private Session mSession2;
	private VideoStreamsView vsv2;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Thread.setDefaultUncaughtExceptionHandler(new UnhandledExceptionHandler(
				this));

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		setContentView(R.layout.main_activity);

		
		LinearLayout layout = (LinearLayout) findViewById(R.id.mainlayout);
		LinearLayout.LayoutParams vsvLP = new LinearLayout.LayoutParams(
				LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 1);
		vsvLP.gravity = (Gravity.CENTER_HORIZONTAL | Gravity.TOP);
		
		// Set up OpenGL View for local and remote video streams
		Point displaySize = new Point();
		getWindowManager().getDefaultDisplay().getSize(displaySize);
		displaySize.set(Math.min(displaySize.x, displaySize.y), (int) Math
				.round(3.0 / 4.0 * Math.min(displaySize.x, displaySize.y)));
		vsv = new VideoStreamsView(this, displaySize);
		vsv.setLayoutParams(vsvLP);
		layout.addView(vsv);

		/*
		 * GLSurfaceView glsv = (GLSurfaceView)
		 * findViewById(R.id.glsurfaceview); if (glsv != null) { Log.d(TAG,
		 * "glsv found"); } else { Log.d(TAG, "glsv is null!"); }
		 */

		/*
		 * vsv = (VideoStreamsView) findViewById(R.id.subscriberview); if (vsv
		 * != null) { Log.d(TAG, "vsv found"); } else { Log.d(TAG,
		 * "vsv is null!"); }
		 */
		// MUST BE RIGHT HERE, NOT in the class definition, not after any
		// other calls (see GLSurfaceView.java for related notes)
		// vsv.setRenderer(vsv);
		// vsv.showRenderer(vsv); // access renderer instance for touch events,
		// etc

		// connect to WebRTC session server
		//String url = "http://435mt2.csail.mit.edu:3000/?r=12345";
		String url = "http://jgrmbp.csail.mit.edu:3000/?r=12345";
		logAndToast("Connecting to " + url);
		mSession = new Session(MainActivity.this, vsv);
		mSession.connect(url);

		if (false) {
			vsv2 = new VideoStreamsView(this, displaySize);
			vsv2.setLayoutParams(vsvLP);
			layout.addView(vsv2);

			// connect to WebRTC session server
			mSession2 = new Session(MainActivity.this, vsv2);
			mSession2.connect(url);
		}
	}

	@Override
	public void onPause() {
		Log.d(TAG, "onPause");
		if (mSession != null)
			mSession.onPause();
		super.onPause(); // this should come after mSession.onPause, right?
	}

	@Override
	public void onResume() {
		Log.d(TAG, "onResume");
		super.onResume();
		if (mSession != null)
			mSession.onResume();
	}

	@Override
	protected void onDestroy() {
		Log.d(TAG, "onDestroy");
		if (mSession != null)
			mSession.disconnect();
		super.onDestroy();
	}

	// Log |msg| and Toast about it.
	private void logAndToast(String msg) {
		Log.d(TAG, msg);
		if (logToast != null) {
			logToast.cancel();
		}
		logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
		logToast.show();
	}

}
