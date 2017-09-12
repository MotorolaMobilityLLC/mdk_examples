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

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import com.motorola.mod.ModDevice;
import com.motorola.mod.ModInterfaceDelegation;
import com.motorola.mod.ModManager;
import com.motorola.mod.ModProtocol;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ModRaw {
    protected Context context;
    private ModManagerInterface modManager;

    private static final int SEND_MSG = 1;

    private static final int POLL_TYPE_READ_DATA = 1;
    private static final int POLL_TYPE_EXIT = 2;
    private static final int EXIT_BYTE = 0xFF;

    private ModInterfaceDelegation pendingDevice;   // Raw Interface
    private ParcelFileDescriptor parcelFD;          // Raw file descriptors
    private FileDescriptor[] syncPipes;             // Multi-fd blocking
    private final Object pipeLock = new Object();   // Protect syncPipes

    private Thread receiveThread = null;            // Receive Raw from Mod
    private HandlerThread sendingThread = null;     // Send Raw to Mod
    private FileOutputStream outputStream;          // Stream for Raw send to Mod
    private Handler handler;                        // Handle send requests from UI

    List<Handler> listeners = new ArrayList<>();

    public final static int MSG_RAW_IO_EXCEPTION = 0;
    public final static int MSG_RAW_REQUEST_PERMISSION = 1;
    public final static int MSG_RAW_IO_READY = 2;
    public final static int MSG_RAW_DATA = 3;


    public ModRaw(Context c, ModManagerInterface modMgr) {
        context = c;
        modManager = modMgr;
    }

    // This should be overridden to check for your expected VID/PID
    protected boolean checkVidPid(ModDevice d) {
        return true;
    }

    // Override this to parse your custom protocol
    protected void onRawData(byte[] buffer, int length) {
        Message msg = Message.obtain();
        msg.what = MSG_RAW_DATA;
        msg.arg1 = length;
        msg.obj = buffer;

        notifyListeners(msg);
    }

    public boolean onModDevice(ModDevice d) {
        boolean ret = true;
        if (null == d) {
            closeRawDevice();
            listeners.clear();
        } else {
            ret = openRawDevice();
        }
        return ret;
    }

    private void closeRawDevice() {
        if (null != sendingThread) {
            sendingThread.quitSafely();
        }

        if (null != receiveThread) {
            signalReadThreadToExit();
        }

        // wait for the read thread to exit
        if (null != syncPipes) {
            synchronized (pipeLock) {
                try {
                    Os.close(syncPipes[0]);
                    Os.close(syncPipes[1]);
                } catch (ErrnoException e) {
                    e.printStackTrace();
                }
                syncPipes = null;
            }
        }
        sendingThread = null;
        receiveThread = null;

        if (parcelFD != null) try {
            parcelFD.close();
            parcelFD = null;
        } catch (IOException e) {
            e.printStackTrace();
        }
        Log.d(Constants.TAG, "RAW I/O closed.");
    }

    private boolean openRawDevice() {
        if (null == modManager) {
            return false;
        }
        ModDevice device = modManager.getModDevice();
        if (null == device) {
            return false;
        }
        if (false == checkVidPid(device)) {
            return false;
        }
        // Query ModManager with RAW protocol
        ModInterfaceDelegation rawDevice =
                modManager.getInterfaceDelegation(ModProtocol.Protocol.RAW);

        if (null != rawDevice) {
            // Be care to follow Android policy, you must obtain user permission
            if (context.checkSelfPermission(ModManager.PERMISSION_USE_RAW_PROTOCOL)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingDevice = rawDevice;
                onRequestRawPermission();
            } else {
                getRawPfd(rawDevice);
            }
        } else {
            Log.d(Constants.TAG, "openRawDeviceifAvailable - no raw device");
        }
        return (parcelFD != null);
    }

    public void onPermissionGranted(boolean granted) {
        if (granted) {
            getRawPfd(pendingDevice);
        } else {
            // User does not allow the permission.
            Log.e(Constants.TAG, "onPermissionGranted decline - user not allow");
        }
        pendingDevice = null;
    }

    private void getRawPfd(ModInterfaceDelegation device) {
        // Get RAW file description via ModManager, to read / write data.
        parcelFD = modManager.openRawInterface(device,
                ParcelFileDescriptor.MODE_READ_WRITE);
        if (parcelFD != null) {
            try {
                syncPipes = Os.pipe();
            } catch (ErrnoException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
            Log.d(Constants.TAG, "getRawPf PFD: " + parcelFD);

            createSendingThread();
            createReceivingThread();

            if (null != sendingThread && null != receiveThread) {
                Log.d(Constants.TAG, "RAW I/O created.");
                onRawInterfaceReady();
            }
        } else {
            Log.e(Constants.TAG, "getRawPfd PFD null ");
        }
    }

    private void createSendingThread() {
        if (sendingThread == null) {
            FileDescriptor fd = parcelFD.getFileDescriptor();
            outputStream = new FileOutputStream(fd);
            sendingThread = new HandlerThread("sendingThread");
            sendingThread.start();
            handler = new SendHandler(sendingThread.getLooper());
        }
    }

    // Handles requests to send Raw data to the Mod
    private class SendHandler extends Handler {
        public SendHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SEND_MSG:
                    try {
                        if (null != outputStream) {
                            byte[] cmd = (byte[]) msg.obj;
                            outputStream.write(cmd);
                        }
                    } catch (IOException e) {
                        Log.e(Constants.TAG, "IOException while writing to raw file" + e);
                        onIOException();
                    }
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    // Add Raw message to the SendHandler queue
    protected void sendRaw(byte[] cmd) {
        if (null != handler) {
            Message msg = Message.obtain(handler, SEND_MSG);
            msg.obj = cmd;
            handler.sendMessage(msg);
        }
    }

    // May be increased for your project
    public static int MAX_BYTES = 1024;

    private void createReceivingThread() {
        if (receiveThread != null) return;
        receiveThread = new Thread() {
            @Override
            public void run() {
                FileDescriptor fd = parcelFD.getFileDescriptor();
                FileInputStream inputStream = new FileInputStream(fd);
                int ret = 0;
                synchronized (pipeLock) {
                    while (ret >= 0) {
                        try {
                            // Poll on the exit pipe and the raw channel
                            int polltype = blockRead();
                            Log.d(Constants.TAG, "Out of Block pollType:" + polltype);
                            if (polltype == POLL_TYPE_READ_DATA) {
                                Log.d(Constants.TAG, "Going to read from RAW");
                                byte[] buffer = new byte[MAX_BYTES];
                                ret = inputStream.read(buffer, 0, MAX_BYTES);
                                if (ret > 0) {
                                    // Got raw data
                                    Log.d(Constants.TAG, "Got raw data.");
                                    onRawData(buffer, ret);
                                }
                            } else if (polltype == POLL_TYPE_EXIT) {
                                Log.d(Constants.TAG, "Exiting Read Thread.");
                                break;
                            }
                        } catch (IOException e) {
                            Log.e(Constants.TAG, "IOException while reading from  raw file" + e);
                            onIOException();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    receiveThread = null;
                }
            }
        };
        receiveThread.start();
    }

    // This causes the below blockRead method to unblock and
    private void signalReadThreadToExit() {
        FileOutputStream out = new FileOutputStream(syncPipes[1]);
        try {
            out.write(EXIT_BYTE);
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int blockRead() {
        // Poll on the pipe to see whether signal to exit, or any data on raw fd to read.
        StructPollfd[] pollfds = new StructPollfd[2];

        // readRawFd will watch whether data is available on the raw channel.
        StructPollfd readRawFd = new StructPollfd();
        pollfds[0] = readRawFd;
        readRawFd.fd = parcelFD.getFileDescriptor();
        readRawFd.events = (short) (OsConstants.POLLIN | OsConstants.POLLHUP);

        // syncFd will watch whether any exit signal.
        StructPollfd syncFd = new StructPollfd();
        pollfds[1] = syncFd;
        syncFd.fd = syncPipes[0];
        syncFd.events = (short) OsConstants.POLLIN;

        try {
            int ret = Os.poll(pollfds, -1);

            if (ret > 0) {
                if (syncFd.revents == OsConstants.POLLIN) {
                    // POLLIN on the syncFd as signal to exit.
                    byte[] buffer = new byte[1];
                    new FileInputStream(syncPipes[0]).read(buffer, 0, 1);
                    return POLL_TYPE_EXIT;
                } else if ((readRawFd.revents & OsConstants.POLLHUP) != 0) {
                    // RAW driver existing.
                    return POLL_TYPE_EXIT;
                } else if ((readRawFd.revents & OsConstants.POLLIN) != 0) {
                    // Finally data ready to read.
                    return POLL_TYPE_READ_DATA;
                } else {
                    Log.d(Constants.TAG, "unexpected events in blockRead rawEvents:" +
                            readRawFd.revents + " syncEvents:" + syncFd.revents);
                    // Unexcpected error.
                    return POLL_TYPE_EXIT;
                }
            } else {
                // Error
                Log.d(Constants.TAG, "Error in blockRead: " + ret);
            }
        } catch (ErrnoException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return POLL_TYPE_EXIT;
    }


    public void registerListener(Handler listener) {
        listeners.add(listener);
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


    private void onIOException() {
        notifyListeners(MSG_RAW_IO_EXCEPTION);
    }

    private void onRequestRawPermission() {
        notifyListeners(MSG_RAW_REQUEST_PERMISSION);
    }

    private void onRawInterfaceReady() {
        notifyListeners(MSG_RAW_IO_READY);
    }

}

