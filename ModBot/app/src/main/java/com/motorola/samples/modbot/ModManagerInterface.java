/**
 * Copyright (c) 2017 Motorola Mobility, LLC.
 * All rights reserved.
 * <p/>
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
 * <p/>
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

package com.motorola.samples.modbot;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.motorola.mod.IModManager;
import com.motorola.mod.ModDevice;
import com.motorola.mod.ModInterfaceDelegation;
import com.motorola.mod.ModManager;
import com.motorola.mod.ModProtocol;


import java.util.ArrayList;
import java.util.List;

public class ModManagerInterface {
    protected ModManager modManager;
    protected ModDevice modDevice;
    protected BroadcastReceiver modReceiver;

    protected Context context;

    List<Handler> listeners = new ArrayList<>();
    public final static int MSG_MOD_DEVICE = 1;

    public ModManagerInterface(Context context) {
        this.context = context;

        // Needed to use the ModManager
        Intent service = new Intent(ModManager.ACTION_BIND_MANAGER);
        service.setComponent(ModManager.MOD_SERVICE_NAME);
        context.bindService(service, mConnection, Context.BIND_AUTO_CREATE);

        // Receive Intents (instead of callbacks) for Moto Mod state
        modReceiver = new ModBroadcastReceiver();
        IntentFilter filter = new IntentFilter(ModManager.ACTION_MOD_SERVICE_STARTED);
        filter.addAction(ModManager.ACTION_MOD_ATTACH);
        filter.addAction(ModManager.ACTION_MOD_DETACH);
        filter.addAction(ModManager.ACTION_MOD_ENUMERATION_DONE);
        /**
         * Request the broadcaster who send these intents must hold permission
         * PERMISSION_MOD_INTERNAL, to avoid the intent from fake senders. Refer to:
         * https://developer.android.com/reference/android/content/Context.html#registerReceiver
         */
        context.registerReceiver(modReceiver, filter, ModManager.PERMISSION_MOD_INTERNAL, null);
    }

    // Obtain an InterfaceDelegation to obtain direct interface (Raw, ModDisplay, etc)
    public ModInterfaceDelegation getInterfaceDelegation(ModProtocol.Protocol p) {
        ModInterfaceDelegation device = null;
        try {
            List<ModInterfaceDelegation> devices =
                    modManager.getModInterfaceDelegationsByProtocol(modDevice, p);
            if (devices != null && !devices.isEmpty()) {
                // Optional: go through the whole devices list for multi connected devices.
                // Here only operate the first device as the sample.
                device = devices.get(0);
                Log.d(Constants.TAG, "getInterfaceDelegation " + device);
            }
        }catch (RemoteException e) {
            e.printStackTrace();
        }
        return device;
    }

    public ParcelFileDescriptor openRawInterface(ModInterfaceDelegation device, int mode) {
        ParcelFileDescriptor fd = null;
        try {
            fd = modManager.openModInterface(device, mode);
        }catch (RemoteException e) {
            e.printStackTrace();
        }
        return fd;
    }

    public Object getClassManager(ModProtocol.Protocol protocol) {
        Object ret = null;
        if (protocol != null) {
            ret = modManager.getClassManager(protocol);
        }
        return ret;
    }

    public void onDestroy() {
        listeners.clear();
        context.unregisterReceiver(modReceiver);
        context.unbindService(mConnection);
    }

    public void registerListener(Handler listener) {
        listeners.add(listener);
    }

    public ModDevice getModDevice() {
        return modDevice;
    }

    public ModManager getModManager() {
        return modManager;
    }

    protected void notifyListeners(int what) {
        for (Handler handler : listeners) {
            handler.sendEmptyMessage(what);
        }
    }

    protected void notifyListeners(Message msg) {
        for (Handler handler : listeners) {
            handler.sendMessage(msg);
        }
    }

    protected void notifyListeners(int what, int arg) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg;

        for (Handler handler : listeners) {
            handler.sendMessage(msg);
        }
    }

    private ServiceConnection mConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className,
                                       IBinder binder) {
            IModManager mMgrSrvc = IModManager.Stub.asInterface(binder);
            modManager = new ModManager(context, mMgrSrvc);
            onModAttach(true);
        }

        public void onServiceDisconnected(ComponentName className) {
            modDevice = null;
            modManager = null;
            onModAttach(false);
        }
    };

    protected void onModAttach(boolean attach) {
        Log.d(Constants.TAG, "onModAttach: " + attach);
        new Thread(new Runnable() {
            public void run() {
                updateModList();
            }
        }).start();
    }

    private void updateModList() {
        if (modManager == null) {
            onModDevice(null);
            return;
        }

        try {
            List<ModDevice> l = modManager.getModList(false);
            if (l == null || l.size() == 0) {
                onModDevice(null);
                return;
            }

            for (ModDevice d : l) {
                Log.d(Constants.TAG, "Device Info: " + d);

                if (d != null) {
                    onModDevice(d);
                    break;
                }
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void onModDevice(ModDevice d) {
        modDevice = d;
        notifyListeners(MSG_MOD_DEVICE);
    }

    private class ModBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ModManager.ACTION_MOD_SERVICE_STARTED.equals(action)) {
                Log.d(Constants.TAG, "ACTION_MOD_SERVICE_STARTED");

                Intent it = new Intent(ModManager.ACTION_BIND_MANAGER);
                it.setComponent(ModManager.MOD_SERVICE_NAME);
                context.bindService(it, mConnection, Context.BIND_AUTO_CREATE);
            } else if (ModManager.ACTION_MOD_ATTACH.equals(action)) {
                Log.d(Constants.TAG, "ACTION_MOD_ATTACH");
            } else if (ModManager.ACTION_MOD_ENUMERATION_DONE.equals(action)) {
                Log.d(Constants.TAG, "ACTION_MOD_ENUMERATION_DONE");
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        onModAttach(true);
                    }
                }, 1000);
            } else if (ModManager.ACTION_MOD_DETACH.equals(action)) {
                Log.d(Constants.TAG, "ACTION_MOD_DETACH");
                onModAttach(false);
            }
        }
    }
}
