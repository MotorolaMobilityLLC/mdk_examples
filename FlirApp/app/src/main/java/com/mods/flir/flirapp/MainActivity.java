/**
 * Copyright (c) 2017 Motorola Mobility, LLC.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.mods.flir.flirapp;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import com.motorola.mod.IModManager;
import com.motorola.mod.ModDevice;
import com.motorola.mod.ModInterfaceDelegation;
import com.motorola.mod.ModManager;
import com.motorola.mod.ModProtocol;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private Context mContext;
    private ModManager mModMgr;
    private ModDevice mModDevice;
    private ModInterfaceDelegation mModDel;
    private Handler mHandler = new Handler();

    private Button mButton;
    private ImageView mImage;
    private boolean mStarted = false;

    private RawDevice mRawDevice;

    private static final int REQUEST_RAW_PERMISSION = 100;
    private enum PERMCODE {PERM_OK, PERM_CHECKING, PERM_NG};

    private Bitmap mLastImage;

    private ServiceConnection mConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className,
                                       IBinder binder) {
            Logger.dbg("Connected to ModManager");
            IModManager svc = IModManager.Stub.asInterface(binder);
            mModMgr = new ModManager(mContext, svc);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    findModDevice();
                }
            });
        }

        public void onServiceDisconnected(ComponentName className) {
            Logger.dbg("Connection to ModManager disconnected");
            mModMgr = null;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    clearModDevice();
                }
            });
        }
    };

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ModManager.ACTION_MOD_ATTACH.equals(action)) {
                /** Mod device attached */
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        findModDevice();
                    }
                });
            } else if (ModManager.ACTION_MOD_DETACH.equals(action)) {
                clearModDevice();
            }
        }
    };

    private FlirImage.UpdateListener mUpdateListener = new FlirImage.UpdateListener() {
        @Override
        public void onImageUpdated(final Bitmap bitmap) {
            if (bitmap != null) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mImage != null) {
                            Logger.dbg("Image Updated");
                            mImage.setImageBitmap(bitmap);
                            if (mLastImage != null)
                                mLastImage.recycle();
                            mLastImage = bitmap;
                        }
                    }
                });
            }
        }
    };

    private FlirImage mFlirImage = new FlirImage(mUpdateListener);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mContext = this.getApplicationContext();

        mButton = (Button)this.findViewById(R.id.button);
        mImage = (ImageView)this.findViewById(R.id.imageView);

        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mStarted) {
                    stopRawReading();
                } else {
                    startRawReading();
                }
                updateUi();
            }
        });

        Intent service = new Intent(ModManager.ACTION_BIND_MANAGER);
        service.setComponent(ModManager.MOD_SERVICE_NAME);
        this.bindService(service, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter filter = new IntentFilter(ModManager.ACTION_MOD_ATTACH);
        filter.addAction(ModManager.ACTION_MOD_DETACH);

        mContext.registerReceiver(mReceiver, filter, ModManager.PERMISSION_MOD_INTERNAL, null);

        updateUi();
    }

    @Override
    public void onDestroy() {
        mContext.unregisterReceiver(mReceiver);
        mContext.unbindService(mConnection);
        super.onDestroy();
    }

    private void updateUi() {
        if (mButton == null) {
            return;
        }

        if (mStarted == true) {
            mButton.setText(R.string.stop);
        } else {
            mButton.setText(R.string.start);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mStarted) {
            mRawDevice.startReading();
        }
    }

    @Override
    public void onPause() {
        if (mStarted) {
            mRawDevice.stopReading();
        }
        super.onPause();
    }

    private void findModDevice() {
        if (mModMgr == null)
            return;

        Logger.dbg("Look for ModDevice");
        try {
            List<ModDevice> l = mModMgr.getModList(false);
            if (l == null || l.size() == 0) {
                return;
            }

            for (ModDevice d : l) {
                if (d != null) {
                    mModDevice = d;
                    Logger.dbg("A ModDevice found");
                    break;
                }
            }
        } catch (RemoteException e) {
            Logger.err("Failed to find ModDevice");
        }
    }

    private void clearModDevice() {
        Logger.dbg("A ModDevice cleared");
        this.stopRawReading();
        mModDevice = null;
        this.updateUi();
    }

    private void startRawReading() {
        PERMCODE code = checkRawProtocol();
        if (code == PERMCODE.PERM_OK) {
            startRawDevice();
        } else if (code == PERMCODE.PERM_NG) {
            Logger.err("Failed to start raw device");
        }
    }

    private void stopRawReading() {
        if (mRawDevice != null) {
            mRawDevice.stopReading();
        }
        mStarted = false;
    }

    private PERMCODE checkRawProtocol() {
        try {
            List<ModInterfaceDelegation> devices;
            devices = mModMgr.getModInterfaceDelegationsByProtocol(mModDevice,
                    ModProtocol.Protocol.RAW);
            if (devices != null && !devices.isEmpty()) {
                // Assume there is only one ModDevice
                mModDel = devices.get(0);

                if (mContext.checkSelfPermission(ModManager.PERMISSION_USE_RAW_PROTOCOL)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{ModManager.PERMISSION_USE_RAW_PROTOCOL},
                            REQUEST_RAW_PERMISSION);

                    return PERMCODE.PERM_CHECKING;
                } else {
                    return PERMCODE.PERM_OK;
                }
            }
        } catch (RemoteException e) {
            Logger.err("Failed to open RAW protocol");
        }
        return PERMCODE.PERM_NG;
    }

    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_RAW_PERMISSION
                && grantResults != null && grantResults.length > 0) {

            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRawDevice();
            } else {
                Logger.err("Raw permission not granted");
            }
            updateUi();
        }
    }

    public void startRawDevice() {
        mRawDevice = new RawDevice(mModMgr, mModDel);

        if (mRawDevice != null) {
            mRawDevice.setCallback(mFlirImage);
            mRawDevice.startReading();
            mStarted = true;
        }
    }
}
