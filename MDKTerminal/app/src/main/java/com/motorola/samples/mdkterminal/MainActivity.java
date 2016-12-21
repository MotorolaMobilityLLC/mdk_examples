/**
 * Copyright (c) 2016 Motorola Mobility, LLC.
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

package com.motorola.samples.mdkterminal;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.motorola.mod.ModDevice;
import com.motorola.mod.ModManager;

import java.util.ArrayList;


public class MainActivity extends AppCompatActivity {
    public static final String MOD_UID = "mod_uid";

    private EditText editCommand;
    private RawPersonality personality;
    private boolean rawString = true;
    private Menu menu;

    private LinearLayout configLayout;
    private ConnectorDefinition[] connectorArray;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        editCommand = (EditText) findViewById(R.id.edit_command);
        if (editCommand != null) {
            editCommand.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(editCommand, InputMethodManager.SHOW_IMPLICIT);
        }

        ImageButton btSend = (ImageButton) findViewById(R.id.button_send);
        if (null != btSend) {
            setImageButtonEnabled(this, false, btSend, R.drawable.ic_send_24dp);
        }

        TextView tv = (TextView) findViewById(R.id.text_content);
        if (tv != null) {
            tv.setMovementMethod(new ScrollingMovementMethod());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        releasePersonality();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();

        initPersonality();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    private void initPersonality() {
        if (null == personality) {
            personality = new RawPersonality(this);
            personality.registerListener(handler);
        }
    }

    private void releasePersonality() {
        if (null != personality) {
            personality.onDestroy();
            personality = null;
        }
    }

    private Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case Personality.MSG_MOD_DEVICE:
                    ModDevice device = personality.getModDevice();
                    onModDevice(device);
                    break;
                case Personality.MSG_RAW_REQUEST_PERMISSION:
                    requestPermissions(new String[]{ModManager.PERMISSION_USE_RAW_PROTOCOL},
                            REQUEST_RAW_PERMISSIONE);
                    break;
                case Personality.MSG_RAW_DATA:
                    byte[] buff = (byte[]) msg.obj;
                    int length = msg.arg1;
                    onRawData(buff, length);
                    break;
                case Personality.MSG_RAW_IO_READY:
                    onRawInterfaceReady();
                    break;
                default:
                    Log.i(Constants.TAG, "MainActivity - Un-handle Mod events: " + msg.what);
                    break;
            }
        }
    };

    public void onRawInterfaceReady() {
        ImageButton btSend = (ImageButton) findViewById(R.id.button_send);
        if (null != btSend) {
            setImageButtonEnabled(this, true, btSend, R.drawable.ic_send_24dp);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_scrolling, menu);
        this.menu = menu;
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_about) {
            String uid = getString(R.string.na);
            if (personality != null
                    && personality.getModDevice() != null
                    && personality.getModDevice().getUniqueId() != null) {
                uid = personality.getModDevice().getUniqueId().toString();
            }

            startActivity(new Intent(this, AboutActivity.class).putExtra(MOD_UID, uid));
            return true;
        }

        if (id == R.id.action_policy) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.URL_PRIVACY_POLICY)));
        }

        if (id == R.id.action_gpio_config) {
            showGPIOConfigDialog();
        }

        if (id == R.id.action_raw_binary) {
            menu.findItem(R.id.action_raw_string).setChecked(false);
            item.setChecked(true);
            rawString = false;
            return true;
        }

        if (id == R.id.action_raw_string) {
            menu.findItem(R.id.action_raw_binary).setChecked(false);
            item.setChecked(true);
            rawString = true;
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public static void setImageButtonEnabled(Context context, boolean enabled,
                                             ImageButton item, int iconResId) {

        item.setEnabled(enabled);
        Drawable originalIcon = context.getDrawable(iconResId);
        Drawable icon = enabled ? originalIcon : convertDrawableToGrayScale(originalIcon);
        item.setImageDrawable(icon);
    }

    public static Drawable convertDrawableToGrayScale(Drawable drawable) {
        if (drawable == null)
            return null;

        Drawable res = drawable.mutate();
        res.setColorFilter(Color.GRAY, PorterDuff.Mode.SRC_IN);
        return res;
    }

    public void onButtonClick(View view) {
        switch (view.getId()) {
            case R.id.button_send:
                if (editCommand != null) {
                    sendCommand(String.format(editCommand.getText().toString() + "%n"));
                }
                break;
        }
    }

    public void onModDevice(ModDevice device) {
        TextView pidvid = (TextView) findViewById(R.id.mod_pid_vid);
        if (null != pidvid) {
            if (device == null
                    || device.getVendorId() == Constants.INVALID_ID
                    || device.getProductId() == Constants.INVALID_ID) {
                pidvid.setText(getString(R.string.na));
            } else {
                String info = String.format("0x%08x/0x%08x",
                        device.getVendorId(), device.getProductId());
                pidvid.setText(info);
            }
        }

        ImageButton btSend = (ImageButton) findViewById(R.id.button_send);
        if (null != btSend) {
            if (device == null) {
                setImageButtonEnabled(this, false, btSend, R.drawable.ic_send_24dp);
            } else {
                setImageButtonEnabled(this, true, btSend, R.drawable.ic_send_24dp);
            }
        }
    }

    private static final int REQUEST_RAW_PERMISSIONE = 2;

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_RAW_PERMISSIONE) {
            ImageButton btSend = (ImageButton) findViewById(R.id.button_send);
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setImageButtonEnabled(this, false, btSend, R.drawable.ic_send_24dp);
            } else {
                // TODO: user declined for RAW accessing permission.
                // To pop up a description dialog or other prompts to explain app cannot work
                // without permission.
                setImageButtonEnabled(this, true, btSend, R.drawable.ic_send_24dp);
            }
        }
    }

    /**
     * Send RAW command for String format
     */
    private void sendCommand(String cmd) {
        if (cmd != null && cmd.isEmpty() != true) {
            JSONCreator json = new JSONCreator();
            json.createCmd(cmd, JSONCreator.TERM);
            personality.executeRaw(json.toString());

            // Update UI
            StringBuilder sb = new StringBuilder();
            byte[] rawCmd = json.toString().getBytes();
            sb.append("Sending: " + json.toString() + " - [");
            for (int i = 0; i < rawCmd.length; i++) {
                sb.append(String.format(" 0x%02x", rawCmd[i]));
            }
            sb.append("]");

            TextView tv = (TextView) findViewById(R.id.text_content);
            if (tv != null) {
                //tv.append("\n");
                tv.append("cmd: " + cmd);
            }
        }
    }

    private void onRawData(byte[] buffer, int length) {
        if (buffer == null || buffer.length <= 0) {
            return;
        }
        /** Command data (may more tag in furture) shall not show on terminal UI */
         boolean showInTerminal = false;

        StringBuilder sb = new StringBuilder();
        sb.append("raw: ");
        if (rawString) {
            /** Get RAW data in String format */
            String json = new String(buffer, 0, length);
            Log.d(Constants.TAG, "Get RAW: " + json);
            JSONParsor parsor = new JSONParsor(json.toString());
            if (parsor.hasTag(JSONCreator.COMMAND)) {
                showInTerminal = false;

                // The command response data
                String cmd = parsor.getData(JSONCreator.COMMAND);
                if (cmd != null) {
                    if (cmd.equalsIgnoreCase(JSONCreator.STATUS)) {
                        // Got the GPIO status
                        ArrayList<ConnectorPort> ports =
                                parsor.getConnectorPortArray(JSONCreator.GPIOS);
                        if (configLayout != null && ports != null) {
                            // Hide read status progress bar
                            configLayout.findViewById(R.id.progress_reading).setVisibility(View.GONE);
                            // Init Ports UI widgets
                            connectorArray = new ConnectorDefinition[ports.size()];
                            LayoutInflater inflater = getLayoutInflater();
                            for (int i = 0; i < ports.size(); i++) {
                                connectorArray[i] = new ConnectorDefinition(this, ports.get(i));
                                LinearLayout linear = connectorArray[i].createLayout(inflater);
                                configLayout.addView(linear);
                            }
                        }

                    }
                }
            } else if (parsor.hasTag(JSONCreator.TERM)) {
                showInTerminal = true;

                // The terminal response data
                String data = parsor.getData(JSONCreator.TERM);
                sb.append(data);
            }
        } else {
            showInTerminal = true;

            /** Get RAW data in Binary format */
            for (int i = 0; i < length; i++) {
                sb.append(String.format(" 0x%02x", buffer[i]));
            }
        }

        if (showInTerminal) {
            TextView tv = (TextView) findViewById(R.id.text_content);
            if (tv != null) {
                tv.append("\n");
                tv.append(sb.toString());
            }
        }
    }

    private void showGPIOConfigDialog() {
        final View inputView = this.getCurrentFocus();
        if (inputView != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(inputView.getWindowToken(), 0);
        }

        LayoutInflater inflater = getLayoutInflater();
        configLayout = (LinearLayout) inflater.inflate(R.layout.dialog_connector_definition, null);
        queryConnectorDefinition();

        // FIXME: fake data for debug
        // fakeDefinition();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(configLayout);
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (inputView != null) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(inputView, 0);
                }
            }
        });
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (connectorArray != null) {
                    JSONCreator json = new JSONCreator();
                    json.createCmd(JSONCreator.WRITE, JSONCreator.COMMAND);
                    for (int i = 0; i < connectorArray.length; i++) {
                        connectorArray[i].createConfig(json);
                    }
                    json.generateConfig();

                    if (personality != null) {
                        personality.executeRaw(json.toString());
                        Snackbar.make(findViewById(R.id.content_view),
                                "GPIO Configuration submitted.", Snackbar.LENGTH_SHORT).show();
                    }
                }

                if (inputView != null) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(inputView, 0);
                }
            }
        });

        builder.show();
    }

    private void queryConnectorDefinition() {
        personality.executeRaw(ConnectorDefinition.queryDefinition().toString());
    }

    private void fakeDefinition() {
        String jsonString = getString(R.string.json);
        byte[] buffer = jsonString.getBytes();
        onRawData(buffer, buffer.length);
    }
}
