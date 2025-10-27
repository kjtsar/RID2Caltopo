package org.ncssar.rid2caltopo.app;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import org.ncssar.rid2caltopo.R;
import org.ncssar.rid2caltopo.data.CaltopoClient;
import org.ncssar.rid2caltopo.data.CaltopoSessionConfig;


public class CaltopoDirectSettings extends DialogFragment implements TextWatcher, View.OnClickListener {
    private final String TAG = "CaltopoDirectSettings";
    private View settingsView;
    private Button saveChanges;
    private Button closeButton;
    private EditText teamIdText;
    private EditText credIdText;
    private EditText credSecretText;
    private EditText folderText;
    static final String UNSPEC_VAL_STR = "<unspecified>";
    static final String HIDDEN_VAL_STR = "############";

    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }
    public void afterTextChanged(Editable e) {
        saveChanges.setEnabled(true);
    }

    public void onClick(View v) {
        if (v == saveChanges) {
            String newVal;
            CaltopoSessionConfig newCfg = new CaltopoSessionConfig(CaltopoClient.GetCaltopoSessionConfig());
            boolean newCfgUpdated = false;

            saveChanges.setEnabled(false);
            // walk thru each of the values and forward any changes to caltopo:
            newVal = teamIdText.getText().toString();
            if (!newVal.isEmpty() && !newVal.equals(UNSPEC_VAL_STR) && !newVal.equals(HIDDEN_VAL_STR)) {
                newCfg.teamId = newVal;
                newCfgUpdated = true;
                Log.i(TAG, "onClick(): teamId updated:" + newVal);
            }

            newVal = credIdText.getText().toString();
            if (!newVal.isEmpty() && !newVal.equals(UNSPEC_VAL_STR) && !newVal.equals(HIDDEN_VAL_STR)) {
                newCfg.credentialId = newVal;
                newCfgUpdated = true;
                Log.i(TAG, "onClick(): credentialId updated:" + newVal);
            }

            newVal = credSecretText.getText().toString();
            if (!newVal.isEmpty() && !newVal.equals(UNSPEC_VAL_STR) && !newVal.equals(HIDDEN_VAL_STR)) {
                newCfg.credentialSecret = newVal;
                newCfgUpdated = true;
                Log.i(TAG, "onClick(): credentialSecret updated:" + newVal);
            }

            newVal = folderText.getText().toString();
            if (!newVal.isEmpty() && !newVal.equals(UNSPEC_VAL_STR)) {
                CaltopoClient.SetTrackFolderName(newVal);
                Log.i(TAG, "onClick(): trackFolder updated:" + newVal);
            }

            if (newCfgUpdated) {
                CaltopoClient.SetCaltopoSessionConfig(newCfg);
            }
        }
        dismiss();
    }

    @Override @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState){
        Log.d(TAG, "CaltopoDirectSettings.onCreateView()");
        settingsView = inflater.inflate(R.layout.fragment_caltopo_direct_settings, container, false);
        saveChanges = settingsView.findViewById(R.id.ct_directSaveButton);
        saveChanges.setOnClickListener(this);
        saveChanges.setEnabled(false);
        closeButton = settingsView.findViewById(R.id.ct_directCloseButton);
        closeButton.setOnClickListener(this);
        CaltopoSessionConfig cfg = CaltopoClient.GetCaltopoConfig();

        teamIdText = settingsView.findViewById(R.id.teamIdEditText);
        teamIdText.addTextChangedListener(this);
        if (null == cfg || null == cfg.teamId || cfg.teamId.isEmpty()) {
            teamIdText.setText(UNSPEC_VAL_STR);
        } else {
            teamIdText.setText(HIDDEN_VAL_STR);
//            teamIdText.setText(cfg.teamId);
        }

        credIdText = settingsView.findViewById(R.id.teamCredIdEditText);
        credIdText.addTextChangedListener(this);
        if (null == cfg || null == cfg.credentialId|| cfg.credentialId.isEmpty()) {
            credIdText.setText(UNSPEC_VAL_STR);
        } else {
            credIdText.setText(HIDDEN_VAL_STR);
//            credIdText.setText(cfg.credentialId);
        }

        credSecretText = settingsView.findViewById(R.id.teamCredSecretEditText);
        credSecretText.addTextChangedListener(this);
        if (null == cfg || null == cfg.credentialSecret|| cfg.credentialSecret.isEmpty()) {
            credSecretText.setText(UNSPEC_VAL_STR);
        } else {
            credSecretText.setText(HIDDEN_VAL_STR);
//            credSecretText.setText(cfg.credentialSecret);
        }

        folderText = settingsView.findViewById(R.id.ctFolderEditText);
        folderText.addTextChangedListener(this);
        String folder = CaltopoClient.GetTrackFolderName();
        if (null == folder || folder.isEmpty()) {
            folder = UNSPEC_VAL_STR;
        }
        folderText.setText(folder);
        return settingsView;
    }
}

