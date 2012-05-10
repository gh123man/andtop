/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * RotationSwitchObserver based off of DockObserver
 * by Brian Floersch (gh123man@gmail.com)
 */

package com.android.server;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.provider.Settings;
import android.server.BluetoothService;
import android.util.Log;
import android.util.Slog;
import android.util.DisplayMetrics;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import android.os.SystemProperties;

import android.widget.Toast;
import android.view.IWindowManager;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.os.AsyncTask;

import java.io.FileNotFoundException;
import java.io.FileReader;

/**
 * <p>RotationLockObserver monitors for rotation lock switch state
 */
class LapdockObserver extends UEventObserver {
    private static final String TAG = LapdockObserver.class.getSimpleName();
    private static final boolean LOG = true;

    private static final String DOCK_UEVENT_MATCH = "DEVPATH=/devices/virtual/switch/smartdock";
    private static final String DOCK_STATE_PATH = "/sys/class/switch/smartdock/state";

    private static final int MSG_DOCK_STATE = 0;

    private int mDockState;
    private int mPreviousDockState;

    private boolean mSystemReady;

    private final Context mContext;

    private boolean mAutoRotation;

    public LapdockObserver(Context context) {
        mContext = context;
        init();  // set initial status

        startObserving(DOCK_UEVENT_MATCH);
    }

    @Override
    public void onUEvent(UEventObserver.UEvent event) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "Switch UEVENT: " + event.toString());
        }

        synchronized (this) {
            try {
                int newState = Integer.parseInt(event.get("SWITCH_STATE"));
                if (newState != mDockState) {
                    mPreviousDockState = mDockState;
                    mDockState = newState;
                    if (mSystemReady) {
                        update();
                    }
                }
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Could not parse switch state from event " + event);
            }
        }
    }

    private final void init() {
        char[] buffer = new char[1024];

        try {
            FileReader file = new FileReader(DOCK_STATE_PATH);
            int len = file.read(buffer, 0, 1024);
            file.close();
            mPreviousDockState = mDockState = Integer.valueOf((new String(buffer, 0, len)).trim());
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "This kernel does not have lapdock support");
        } catch (Exception e) {
            Slog.e(TAG, "" , e);
        }
    }

    void systemReady() {
        synchronized (this) {
            mSystemReady = true;
        }
    }

    private final void update() {
        mHandler.sendEmptyMessage(MSG_DOCK_STATE);
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DOCK_STATE:
                    synchronized (this) {
			if(mDockState == 0){
				setAutoRotation(true);
				SystemProperties.set("ro.sf.lcd_density", "240");
				runcmd("reboot");
		    		Toast.makeText(mContext, "un-DOCKED", Toast.LENGTH_SHORT).show();
			} else {
				setAutoRotation(false);
				SystemProperties.set("ro.sf.lcd_density", "120");
				runcmd("reboot");
				Toast.makeText(mContext, "DOCKED", Toast.LENGTH_SHORT).show();
			}
                    break;
		}
            }
        }
    };

    private void setAutoRotation(final boolean autorotate) {
        mAutoRotation = autorotate;
        AsyncTask.execute(new Runnable() {
                public void run() {
                    try {
                        IWindowManager wm = IWindowManager.Stub.asInterface(
                                ServiceManager.getService(Context.WINDOW_SERVICE));
                        if (autorotate) {
                            wm.thawRotation();
                        } else {
                            wm.freezeRotation(-1);
                        }
                    } catch (RemoteException exc) {
                        Log.w(TAG, "Unable to save auto-rotate setting");
                    }
                }
            });
    }

    public void runcmd(String inpt){
	try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
	    os.writeBytes(inpt + "\n");    
            os.writeBytes("exit\n");  
            os.flush();
	}
	catch (Exception e) {
      		Log.d("ROOT", "Root access rejected [" +
            	e.getClass().getName() + "] : " + e.getMessage());
   		}
	}
}
