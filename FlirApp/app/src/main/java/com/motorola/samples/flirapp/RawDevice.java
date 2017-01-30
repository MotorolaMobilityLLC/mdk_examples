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

package com.motorola.samples.flirapp;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import com.motorola.mod.ModInterfaceDelegation;
import com.motorola.mod.ModManager;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class RawDevice extends Thread {
    private ModManager mModMgr;
    private ModInterfaceDelegation mModDel;

    private ParcelFileDescriptor mParcelFD;
    private FileDescriptor[] mPipes;

    private OutputStream mOut;

    private static int MAX_BYTES = 1024;

    public RawDevice(ModManager mgr, ModInterfaceDelegation del) {
        mModMgr = mgr;
        mModDel = del;
    }

    public interface DataCallback {
        public void onData(byte[] data, int len);
    }

    private DataCallback mCallback;

    public void setCallback(DataCallback callback) {
        mCallback = callback;
    }
    @Override
    public void run() {
        Logger.dbg("Starting raw reading thread");
        if (!openDevice())
            return;

        mOut = new FileOutputStream(mParcelFD.getFileDescriptor());

        sendOnCommand();

        blockRead();

        try {
            mOut.close();
        } catch (IOException e) {
        }

        closeDevice();
        Logger.dbg("Raw reading thread done");
    }

    private boolean openDevice() {
        try {
            mParcelFD = mModMgr.openModInterface(mModDel,
                    ParcelFileDescriptor.MODE_READ_WRITE);

            if (mParcelFD == null) {
                Logger.err("Cannot get percel FD");
                return false;
            } else {
                return true;
            }
        } catch (RemoteException e) {
            Logger.err("Cannot open ModInterface");
        }
        return true;
    }

    private void blockRead() {
        byte[] buffer = new byte[MAX_BYTES];
        FileDescriptor fd = mParcelFD.getFileDescriptor();
        FileInputStream inputStream = new FileInputStream(fd);
        int ret = 0;
        synchronized (mPipes) {
            while (ret >= 0) {
                try {
                    /** Poll on the exit pipe and the raw channel */
                    if (readDevice()) {
                        ret = inputStream.read(buffer, 0, MAX_BYTES);
                        if (ret > 0) {
                            if (mCallback != null) {
                                mCallback.onData(buffer, ret);
                            }
                        }
                    } else {
                        Logger.dbg("quitting...");
                        sendOffCommand();
                        break;
                    }
                } catch (IOException e) {
                    Logger.err("IOException while reading from raw file" + e);
                    break;
                }
            }
        }
    }

    private boolean readDevice() {
        StructPollfd rawFd = new StructPollfd();
        rawFd.fd = mParcelFD.getFileDescriptor();
        rawFd.events = (short) (OsConstants.POLLIN | OsConstants.POLLHUP);

        StructPollfd syncFd = new StructPollfd();
        syncFd.fd = mPipes[0];
        syncFd.events = (short) OsConstants.POLLIN;

        StructPollfd[] pollfds = new StructPollfd[2];
        pollfds[0] = rawFd;
        pollfds[1] = syncFd;

        try {
            int ret = Os.poll(pollfds, -1);
            if (ret > 0) {
                if (syncFd.revents == OsConstants.POLLIN) {
                    /** POLLIN on the syncFd as signal to exit */
                    byte[] buffer = new byte[1];
                    new FileInputStream(mPipes[0]).read(buffer, 0, 1);
                } else if ((rawFd.revents & OsConstants.POLLHUP) != 0) {
                    return false;
                } else if ((rawFd.revents & OsConstants.POLLIN) != 0) {
                    return true;
                } else {
                    /** Unexcpected error */
                    Logger.err("unexpected events in blockRead rawEvents:"
                            + rawFd.revents + " syncEvents:" + syncFd.revents);
                }
            } else {
                Logger.err("Error in blockRead: " + ret);
            }
        } catch (ErrnoException e) {
            Logger.err("ErrnoException in blockRead: " + e);
        } catch (IOException e) {
            Logger.err("IOException in blockRead: " + e);
        }
        return false;
    }

    private void closeDevice() {
        /** Close the file descriptor pipes */

        if (mParcelFD != null) try {
            mParcelFD.close();
            mParcelFD = null;
        } catch (IOException e) {
            Logger.err("Failed to close parcel FD");
        }
    }

    public synchronized void startReading() {
        Logger.dbg("Start Raw reading");
        try {
            mPipes = Os.pipe();
        } catch (ErrnoException e) {
            Logger.err("Cannot create pipe");
            return;
        }
        start();
    }

    public synchronized void stopReading() {
        if (mPipes == null)
            return;

        FileOutputStream out = new FileOutputStream(mPipes[1]);
        try {
            out.write(0);
            out.close();
        } catch (IOException e) {
            Logger.err("Failed to write exit command");
        }

        try {
            join();
        } catch (InterruptedException e) {
        }

        try {
            Os.close(mPipes[0]);
            Os.close(mPipes[1]);
        } catch (ErrnoException e) {
            Logger.err(("Failed to close pipe"));
        }
        mPipes = null;
        Logger.dbg("Raw reading done");
    }


    private void sendOnCommand() {
        try {
            if (mOut != null) {
                byte[] mine = "on\0".getBytes();
                mOut.write(mine);
            }
        } catch (IOException e) {
            Logger.err("Failed to send \"on\" command");
        }
    }

    private void sendOffCommand() {
        try {
            if (mOut != null) {
                byte[] mine = "off\0".getBytes();
                mOut.write(mine);
            }
        } catch (IOException e) {
            Logger.err("Failed to send \"off\" command");
        }
    }
}
