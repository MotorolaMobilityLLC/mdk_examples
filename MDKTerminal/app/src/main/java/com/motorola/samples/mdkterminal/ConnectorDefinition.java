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
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

public class ConnectorDefinition implements AdapterView.OnItemSelectedListener, CompoundButton.OnCheckedChangeListener {
    private Context context;
    private int serial;
    private boolean enabled;

    private ConnectorPort connector;

    private Spinner directionSpinner;
    private ToggleButton levelButton;
    private TextView interruptView;

    private final static String[] portArray = {"A", "C", "C", "D", "H", "C", "C", "G",
            "G", "G", "A", "C"};
    private final static int[] pinArray = {10, 8, 7, 6, 0, 3, 12, 9, 10, 12, 9, 9};

    public ConnectorDefinition(Context context, ConnectorPort connectorPort) {
        this.enabled = false;
        this.connector = connectorPort;
    }

    public static JSONCreator queryDefinition() {
        JSONCreator json = new JSONCreator();
        json.createCmd("read", JSONCreator.COMMAND);
        for (int i = 0; i < portArray.length; i++) {
            json.addConfig(portArray[i], pinArray[i],
                    0, "in");
        }
        json.generateConfig();

        return json;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (position == 0) {
            // In
            levelButton.setEnabled(false);
            interruptView.setVisibility(View.VISIBLE);
        } else if (position == 1) {
            // Out
            levelButton.setEnabled(true && enabled);
            interruptView.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {

    }

    public LinearLayout createLayout(LayoutInflater inflater) {
        LinearLayout linear = (LinearLayout) inflater.inflate(R.layout.item_pin_definition, null);
        initLayout(linear);
        return linear;
    }

    private void initLayout(LinearLayout linear) {
        CheckBox enable = (CheckBox) linear.findViewById(R.id.checkbox_enable);
        TextView name = (TextView) linear.findViewById(R.id.textview_signal_name);
        directionSpinner = (Spinner) linear.findViewById(R.id.spinner_direction);
        levelButton = (ToggleButton) linear.findViewById(R.id.togglebutton_level);
        interruptView = (TextView) linear.findViewById(R.id.textview_interrupt);

        name.setText(String.format("P%s%d", connector.port, connector.pin));

        // By default all checkbox is unchecked
        enable.setChecked(false);
        enable.setOnCheckedChangeListener(this);

        directionSpinner.setEnabled(enabled);
        directionSpinner.setSelection(connector.getDirection());
        directionSpinner.setOnItemSelectedListener(this);

        levelButton.setChecked(connector.level == 1);
        levelButton.setEnabled(connector.getDirection() == 1 && enabled);

        interruptView.setVisibility(connector.getDirection() == 1 ? View.INVISIBLE : View.VISIBLE);
    }

    public void createConfig(JSONCreator json) {
        if (enabled) {
            json.addConfig(connector.port, connector.pin,
                    levelButton.isChecked() ? 1 : 0,
                    directionSpinner.getSelectedItemPosition() == 0 ? "in" : "out");
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        enabled = isChecked;

        directionSpinner.setEnabled(enabled);
        levelButton.setEnabled(directionSpinner.getSelectedItemPosition() == 1 && enabled);
    }

}
