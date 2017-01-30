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

import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class FlirImage implements RawDevice.DataCallback {

    private static final int FLIR_WIDTH = 80;
    private static final int FLIR_HEIGHT = 60;

    private int[] mRaw = new int[FLIR_HEIGHT * FLIR_WIDTH];
    private ByteBuffer mPix = ByteBuffer.allocateDirect(FLIR_WIDTH * FLIR_HEIGHT * 4);

    private static final int MAX_STEPS = 256;

    private int mMax = 0;
    private int mMin = 0xFFFF;
    private int mNextId = 0;
    private int mRawCount = 0;

    public interface UpdateListener {
        public void onImageUpdated(Bitmap bitmap);
    }

    private UpdateListener mListener;

    public FlirImage(UpdateListener listener) {
        mListener = listener;
    }

    @Override
    public void onData(byte[] data, int len) {
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, len);
        buffer.order(ByteOrder.BIG_ENDIAN);

        boolean sof = false;
        int sofar = 0;
        while (sofar < len) {
            if (mRawCount % FLIR_WIDTH == 0) {
                sof = true;
            }

            if (sof) {
                int id = buffer.getShort();
                id &= 0x0FFF;
                int crc = buffer.getShort();
                sof = false;
                sofar += 4;
                if (id != mNextId) {
                    // Drop entire packet and wait for sync
                    Logger.warn("Unexpected line id:" + id + " expected:" + mNextId);
                    mRawCount = 0;
                    mNextId = 0;
                    break;
                } else {
                    mNextId++;
                }
            }
            int val = buffer.getShort();
            sofar += 2;

            val &= 0x3FFF;
            mRaw[mRawCount] = val;
            mRawCount++;

            if (val > mMax)
                mMax = val;

            if (val < mMin)
                mMin = val;
        }

        if (mRawCount == mRaw.length) {
            //Logger.dbg("max:" + mMax + " min:" + mMin);
            updatePixValue();
            mMax = 0;
            mMin = 0xFFFF;
            mNextId = 0;
            mRawCount = 0;
        }
    }

    public void updatePixValue() {
        int diff = mMax - mMin;
        float scale = (float)(MAX_STEPS - 1)/(float)diff;

        mPix.rewind();

        for (int idx = 0; idx < mRaw.length; idx++) {
            int offset = mRaw[idx] - mMin;
            int mapIdx = Math.round((float)offset * scale);
            mPix.put((byte)mapIdx); // R
            mPix.put((byte)0x00); // G - always 0
            mPix.put((byte)(0xFF - mapIdx)); // B
            mPix.put((byte)0xFF); // A - set to FF
        }

        mPix.rewind();

        if (mListener != null) {
            Bitmap bm = Bitmap.createBitmap(FLIR_WIDTH, FLIR_HEIGHT, Bitmap.Config.ARGB_8888);
            bm.copyPixelsFromBuffer(mPix);
            mListener.onImageUpdated(bm);
        }
    }
}
