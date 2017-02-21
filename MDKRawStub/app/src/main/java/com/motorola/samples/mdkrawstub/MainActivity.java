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

package com.motorola.samples.mdkrawstub;

import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.motorola.mod.ModDevice;
import com.motorola.mod.ModManager;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private ModManagerInterface modManager; // for attach/detach and interface
    private ModRawStub modRaw;              // Raw handler

    TextView vidpid;    // Show current VID/PID
    TextView rxText;    // Received text box
    EditText txText;    // Input text to send
    Button writeButton; // Execute send


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Find and initialize views
        vidpid = (TextView) findViewById(R.id.mod_vid_pid);
        rxText = (TextView) findViewById(R.id.output_text);
        txText = (EditText) findViewById(R.id.input_text);
        writeButton = (Button)findViewById(R.id.write_button);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseModManager();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        initModManager();           // Connect ModManagerInterface service
    }

    private void initModManager() {
        if (null == modManager) {
            modManager = new ModManagerInterface(this);
            modManager.registerListener(modHandler);
        }
    }

    private void releaseModManager() {
        if (null != modRaw) {
            modRaw.onModDevice(null);
            modRaw = null;
        }
        if (null != modManager) {
            modManager.onDestroy();
            modManager = null;
        }
    }

    // Handle messages from the ModManagerInterface
    private Handler modHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ModManagerInterface.MSG_MOD_DEVICE:
                    ModDevice device = modManager.getModDevice();
                    onModDevice(device);
                    break;
            }
        }
    };

    // Called on Moto Mod attach/detach
    public void onModDevice(ModDevice device) {
        if (null == device) {
            if (null != vidpid) {
                vidpid.setText(getString(R.string.no_mod));
            }
            if (null != modRaw) {
                modRaw.onModDevice(null);   // Close Raw interfaces
                modRaw = null;
            }
            writeButton.setEnabled(false);
        } else {
            int vendorId = device.getVendorId();
            if (null != vidpid) {
                String info = String.format("0x%08x/0x%08x",
                        vendorId, device.getProductId());
                vidpid.setText(info);
            }
            // Before opening Raw, ALWAYS confirm
            // that you're talking to YOUR Moto Mod
            if (vendorId == Constants.VID_DEVELOPER) {
                // Connect the Raw interface
                if (null == modRaw) {
                    modRaw = new ModRawStub(this, modManager);
                    modRaw.registerListener(rawHandler);
                    modRaw.onModDevice(device);
                }
            }
        }
    }

    private static int RAW_REQUEST_CODE = 0x25;
    private Handler rawHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ModRawStub.MSG_RAW_REQUEST_PERMISSION:
                    requestPermissions(new String[]{ModManager.PERMISSION_USE_RAW_PROTOCOL},
                            RAW_REQUEST_CODE);
                    break;
                case ModRawStub.MSG_RAW_IO_READY:
                    writeButton.setEnabled(true);
                    break;
                case ModRawStub.MSG_RAW_DATA:
                    String text = new String((byte[]) msg.obj);
                    rxText.setText(text);
                    break;
            }
        }
    };

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == RAW_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                modRaw.onPermissionGranted(true);
            } else {
                // TODO: user declined for RAW accessing permission.
                // Pop up a description dialog or other prompts to explain app cannot work
                // without permission.
            }
        }
    }

    @Override
    public void onClick(View v) {
        String text = txText.getText().toString();
        modRaw.writeRequest(text.getBytes());
    }
}