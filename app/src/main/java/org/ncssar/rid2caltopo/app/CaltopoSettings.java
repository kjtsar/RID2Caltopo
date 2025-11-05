package org.ncssar.rid2caltopo.app;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;

import android.content.DialogInterface;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import org.ncssar.rid2caltopo.data.CaltopoClient;
import org.ncssar.rid2caltopo.R;
import org.ncssar.rid2caltopo.data.CaltopoSessionConfig;
import org.ncssar.rid2caltopo.data.CtDroneSpec;
import org.ncssar.rid2caltopo.data.DelayedExec;

import java.util.ArrayList;
import java.util.Locale;

class DroneSpecViewHolder {
    public TextView labelText;
    public EditText mappedEditText;
    public EditText orgEditText;
    public EditText modelEditText;
    public EditText ownerEditText;
    public TextView msgCountText;
    public long lastMsgCount;
    public CaltopoClient client;

    DroneSpecViewHolder() {}
}
enum ET_Field_t {
    ET_MAPPED_ID,
    ET_ORG,
    ET_MODEL,
    ET_OWNER,
}

class MyEditTextWatcher implements TextWatcher, View.OnFocusChangeListener, TextView.OnEditorActionListener {
    private static final String TAG = "MyEditTextWatcher";
    private EditText editText;
    private String setValue;
    private String newValue;
    private ET_Field_t field;
    private CtDroneSpec droneSpec;
    private int cursorPosition;

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (v == editText && !hasFocus) textHasFinishedChanging();
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        MyEditTextWatcher textsWatcher = (MyEditTextWatcher)v.getTag();
        if (textsWatcher != this) {
            CTDebug(TAG, "onEditorAction() from a different view.");
            return false;
        }
        CTInfo(TAG, String.format(Locale.US, "onEditorAction() id:%d", actionId));
        if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT ||
                actionId == EditorInfo.IME_ACTION_PREVIOUS) {
            textHasFinishedChanging();
        }
        return false;
    }

    public void setTextValue(String stringValue) {
        setValue = stringValue;

        MyEditTextWatcher textsWatcher = (MyEditTextWatcher)editText.getTag();
        if (null != textsWatcher) {
            if (textsWatcher == this) {
                editText.removeTextChangedListener(this);
                editText.setText(setValue);
                editText.addTextChangedListener(this);
            } else {
                CTError(TAG,
                        String.format(Locale.US, "setTextValue(0x%x): Can't change text that I don't watch: 0x%x.",
                                System.identityHashCode(this), System.identityHashCode(textsWatcher)));
            }
        } else { // not set yet, so we can do this:
            editText.setText(setValue);
            editText.addTextChangedListener(this);
        }
    }
    public MyEditTextWatcher() {}
    public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
    public void onTextChanged(CharSequence s, int start, int before, int count) {}
    public void afterTextChanged(Editable e) {
        newValue = e.toString().trim();
        CTInfo(TAG, String.format(Locale.US, "afterTextChanged(0x%x) has detected change from:'%s' to:'%s'",
                System.identityHashCode(this), setValue, newValue));
        cursorPosition = editText.getSelectionStart();
        CTInfo(TAG, String.format(Locale.US, "set cursorPosition to selectionStart(%d), selectionEnd(%d)",
                cursorPosition, editText.getSelectionEnd()));
    }

    @Override
    @NonNull
    public String toString() {
        return String.format(Locale.US,
                "TextWatcher(0x%x): %s setValue:'%s' newValue:'%s'", System.identityHashCode(this),
                field.toString(), setValue, newValue);
    }

    static void SetupWatcher(@NonNull EditText editText, ET_Field_t field,
                                          @NonNull CtDroneSpec droneSpec) {
        MyEditTextWatcher textWatcher;
        if (editText.getTag() instanceof MyEditTextWatcher) {
            textWatcher = (MyEditTextWatcher)editText.getTag();
        } else {
            textWatcher = new MyEditTextWatcher();
            editText.setTag(textWatcher);
            editText.setOnEditorActionListener(textWatcher);
            editText.setOnFocusChangeListener(textWatcher);
            textWatcher.editText = editText;
        }
        // load the current value of the field from the dronespec:
        String tv;

        switch (field) {
            case ET_MAPPED_ID:
                tv = droneSpec.getMappedId();
                break;
            case ET_MODEL:
                tv = droneSpec.getModel();
                break;
            case ET_ORG:
                tv = droneSpec.getOrg();
                break;
            case ET_OWNER:
                tv = droneSpec.getOwner();
                break;
            default: tv = "unknown";
        }
        textWatcher.setTextValue(tv);
        textWatcher.field = field;
        textWatcher.droneSpec = droneSpec;
    }

    private void textHasFinishedChanging() {
        if (newValue.equals(setValue)) {
            CTInfo(TAG, "textHasFinishedChanging(): no change detected.");
            return;
        }

        String approved;
        switch (field) {
            case ET_MAPPED_ID: approved = droneSpec.setMappedId(newValue); break;
            case ET_MODEL: approved = droneSpec.setModel(newValue); break;
            case ET_ORG: approved = droneSpec.setOrg(newValue); break;
            case ET_OWNER: approved = droneSpec.setOwner(newValue); break;
            default: approved = setValue;
        }

        if (!setValue.equals(approved)) {
            CTDebug(TAG, String.format(Locale.US,
                    "textHasFinishedChanging(0x%x): %s old:'%s' new:'%s' approved:'%s'",
                    System.identityHashCode(this), field.toString(), setValue, newValue, approved));
            setTextValue(approved);
            if (cursorPosition >= 0 && cursorPosition <= approved.length()) {
                CTInfo(TAG, "Setting cursorPosition:" + cursorPosition);
                editText.setSelection(cursorPosition);
            }
        }
    }
}


/**
 */
public class CaltopoSettings extends DialogFragment implements TextWatcher, ListAdapter, View.OnClickListener {
    private static final String TAG = "CaltopoSettings";
    private static final String GROUP_ID_LABEL = "Group Id";
    private static final String MAP_ID_LABEL = "Map Id";

    public CaltopoSettings() {
        // Required empty public constructor
    }
    TextView configTitle; // used to configure adjust log settings.
    TextView configInfo; // used to report current log setting.
    private View settingsView;
    EditText groupIdText;
    EditText mapIdText;
    EditText minChangedText;
    EditText newTrackDelayInSecEditText;
    EditText maxAgeInSecEditText;
    long minChangedVal;
    TextView archivePathText;
    Button saveChanges;
    Button closeButton;
    Button archiveDirButton;
    ListView mapListView;
    LayoutInflater inflater;
    TextView groupMapLabel;
    ToggleButton directToggle;
    ArrayList<DroneSpecViewHolder>droneSpecViewHolders;
    private DelayedExec viewUpdater;
    ArrayList<CtDroneSpec>currentDroneSpecs;

    private EditText teamIdText;
    private EditText credIdText;
    private EditText credSecretText;
    private EditText folderText;
    static final String UNSPEC_VAL_STR = "<unspecified>";
    static final String HIDDEN_VAL_STR = "############";

    public static CaltopoSettings newInstance() {
        return new CaltopoSettings();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    public void onTextChanged(CharSequence s, int start, int before, int count) {}
    public void afterTextChanged(Editable e) {saveChanges.setEnabled(true);}
    public void checkGroupId() {
        String newVal = groupIdText.getText().toString().trim();
        String oldVal = CaltopoClient.GetGroupId();

        if (!newVal.equals(oldVal)) {
            CTDebug(TAG, String.format(Locale.US, "%s changing to '%s' from '%s'",
                    GROUP_ID_LABEL,  newVal, oldVal));
            oldVal = CaltopoClient.SetGroupId(newVal);
            if (!oldVal.equals(newVal)) {
                groupIdText.setText(oldVal);
                CTDebug(TAG, String.format(Locale.US, "... but CaltopoClient went with '%s' instead.", oldVal));
            }
        }
    }


    public void checkMapId() {
        String newVal = mapIdText.getText().toString().trim();
        String oldVal = CaltopoClient.GetMapId();

        if (!newVal.equals(oldVal)) {
            CTDebug(TAG, String.format(Locale.US, "%s changing to '%s' from '%s'",
                    MAP_ID_LABEL,  newVal, oldVal));
            oldVal = CaltopoClient.SetMapId(newVal);
            if (!oldVal.equals(newVal)) {
                mapIdText.setText(oldVal);
                CTDebug(TAG, String.format(Locale.US, "... but CaltopoClient went with '%s' instead.", oldVal));
            }
        }
    }

    public void checkMinDistance() {
        String rawInput = minChangedText.getText().toString().trim();
        long inputVal;

        try {
            inputVal = Long.parseLong(rawInput);

        } catch (NumberFormatException e) {
            CTDebug(TAG, String.format(Locale.US, "checkMinDistance(%s) not a valid numeric value.", rawInput));
            minChangedText.setText(String.format(Locale.US, "%d", minChangedVal));
            inputVal = 0;
        }

        if (inputVal != minChangedVal) {
            CTDebug(TAG, String.format(Locale.US, "minDistanceInDegrees changing to '%d' from '%d'.", inputVal, minChangedVal));
            minChangedVal = CaltopoClient.setMinDistanceInFeet(inputVal);
            if (inputVal != minChangedVal) {
                minChangedText.setText(String.format(Locale.US, "%d", minChangedVal));
                CTDebug(TAG, String.format(Locale.US, "... but CaltopoClient went with '%d' instead of '%d'.",
                        minChangedVal, inputVal));
            }
        }
    }

    public void checkNewTrackDelay() {
        String rawInput = newTrackDelayInSecEditText.getText().toString().trim();
        long inputVal;
        long currentVal = CaltopoClient.GetNewTrackDelayInSeconds();

        try {
            inputVal = Long.parseLong(rawInput);
        } catch (NumberFormatException e) {
            CTDebug(TAG, String.format(Locale.US,
                    "checkNewTrackDelay(%s) not a valid numeric value.", rawInput));
            minChangedText.setText(String.format(Locale.US, "%d", currentVal));
            return;
        }

        if (inputVal != currentVal) {
            CTDebug(TAG, String.format(Locale.US,
                    "NewTrackDelayInSeconds changing to '%d' from '%d'.", inputVal, currentVal));
            currentVal = CaltopoClient.SetNewTrackDelayInSeconds(inputVal);
            if (inputVal != currentVal) {
                minChangedText.setText(String.format(Locale.US, "%d", currentVal));
                CTDebug(TAG, String.format(Locale.US,
                        "... but CaltopoClient went with '%d' instead of '%d'.",
                        currentVal, inputVal));
            }
        }
    }
    private void checkCredentials() {
        String newVal;
        CaltopoSessionConfig newCfg = new CaltopoSessionConfig(CaltopoClient.GetCaltopoSessionConfig());
        boolean newCfgUpdated = false;

        saveChanges.setEnabled(false);
        // walk thru each of the values and forward any changes to caltopo:
        newVal = teamIdText.getText().toString();
        if (!newVal.isEmpty() && !newVal.equals(UNSPEC_VAL_STR) && !newVal.equals(HIDDEN_VAL_STR)) {
            newCfg.teamId = newVal;
            newCfgUpdated = true;
            Log.i(TAG, "checkCredentials(): teamId updated:" + newVal);
        }

        newVal = credIdText.getText().toString();
        if (!newVal.isEmpty() && !newVal.equals(UNSPEC_VAL_STR) && !newVal.equals(HIDDEN_VAL_STR)) {
            newCfg.credentialId = newVal;
            newCfgUpdated = true;
            Log.i(TAG, "checkCredentials(): credentialId updated:" + newVal);
        }

        newVal = credSecretText.getText().toString();
        if (!newVal.isEmpty() && !newVal.equals(UNSPEC_VAL_STR) && !newVal.equals(HIDDEN_VAL_STR)) {
            newCfg.credentialSecret = newVal;
            newCfgUpdated = true;
            Log.i(TAG, "checkCredentials(): credentialSecret updated:" + newVal);
        }

        newVal = folderText.getText().toString();
        if (!newVal.isEmpty() && !newVal.equals(UNSPEC_VAL_STR)) {
            CaltopoClient.SetTrackFolderName(newVal);
            Log.i(TAG, "checkCredentials(): trackFolder updated:" + newVal);
        }

        if (newCfgUpdated) {
            CaltopoClient.SetCaltopoSessionConfig(newCfg);
        }
    }

    private void checkMaxDisplayAgeInSec() {
        String rawInput = maxAgeInSecEditText.getText().toString().trim();
        long inputVal;
        long currentVal = CaltopoClient.GetMaxDisplayAgeInSeconds();

        try {
            inputVal = Long.parseLong(rawInput);
        } catch (NumberFormatException e) {
            CTDebug(TAG, String.format(Locale.US,
                    "checkMaxDisplayAgeInSec(%s) not a valid numeric value.", rawInput));
            maxAgeInSecEditText.setText(String.format(Locale.US, "%d", currentVal));
            return;
        }
        if (inputVal != currentVal) {
            CTDebug(TAG, String.format(Locale.US,
                    "MaxDelayInSeconds changing to '%d' from '%d'.", inputVal, currentVal));
            CaltopoClient.SetMaxDisplayAgeInSeconds(inputVal);
        }
    }

    // User pushed a button - check 4 && save any changes:
    public void onClick(View v){
        if (v == saveChanges) {
            CTInfo(TAG, "Received onClick() for saveChanges.");
            if (v == archivePathText || v == archiveDirButton) {
                CaltopoClient.QueryUserForArchiveDir();
            }

            checkGroupId();
            checkMapId();
            checkMinDistance();
            checkNewTrackDelay();
            checkMaxDisplayAgeInSec();
            checkCredentials();
            ((R2CActivity)requireActivity()).archiveTracks();
            CTInfo(TAG, "Disabling saveChanges.");
            saveChanges.setEnabled(false);
        } else if (v == configTitle) {
            String newLevel = CaltopoClient.BumpLoggingLevel();
            configInfo.setText(newLevel);
            return;
        } else if (v != closeButton) {
            CTDebug(TAG, "Enabling saveChanges.");
            saveChanges.setEnabled(true);
        }
        dismiss();
    }

    @Override public boolean isEnabled(int pos) { return true;}

    @Override public boolean areAllItemsEnabled() {return true;}

    @Override public int getViewTypeCount() {return 1;}

    @Override public long getItemId(int pos) {
        return pos ;
    }
    @Override public int getItemViewType(int pos) {
        return 1;
    }
    @Override public void registerDataSetObserver(DataSetObserver obs) {

    }
    @Override public void unregisterDataSetObserver(DataSetObserver obs) {

    }

    @Override public View getView(int pos, @Nullable View convertView, @NonNull ViewGroup parent) {
        DroneSpecViewHolder viewHolder;
        CtDroneSpec ds = currentDroneSpecs.get(pos);

        if (null != convertView) {
            viewHolder = (DroneSpecViewHolder) convertView.getTag();
        } else {
            convertView = inflater.inflate(R.layout.listitem_ctmap, parent, false);
            viewHolder = new DroneSpecViewHolder();
            viewHolder.labelText = convertView.findViewById(R.id.ct_mapLabel);
            viewHolder.mappedEditText = convertView.findViewById(R.id.ct_mapText);
            viewHolder.orgEditText = convertView.findViewById(R.id.org);
            viewHolder.ownerEditText = convertView.findViewById(R.id.owner);
            viewHolder.modelEditText = convertView.findViewById(R.id.model);
            viewHolder.msgCountText = convertView.findViewById(R.id.msgCount);
            convertView.setTag(viewHolder);
            if (null == droneSpecViewHolders) droneSpecViewHolders = new ArrayList<>(16);
            droneSpecViewHolders.add(viewHolder);
        }
        viewHolder.client = CaltopoClient.ClientForRemoteId(ds.getRemoteId());
        long unsavedMsgCount = viewHolder.client.unsavedMsgCount();
        viewHolder.msgCountText.setText(String.format(Locale.US, "%d", unsavedMsgCount));
        viewHolder.labelText.setText(ds.getRemoteId());
        MyEditTextWatcher.SetupWatcher(viewHolder.mappedEditText, ET_Field_t.ET_MAPPED_ID, ds);
        MyEditTextWatcher.SetupWatcher(viewHolder.orgEditText, ET_Field_t.ET_ORG, ds);
        MyEditTextWatcher.SetupWatcher(viewHolder.ownerEditText, ET_Field_t.ET_OWNER, ds);
        MyEditTextWatcher.SetupWatcher(viewHolder.modelEditText, ET_Field_t.ET_MODEL, ds);
        startTextViewUpdater();
        return convertView;
    }

    private void updateViewTextCounts() {
        int changeCount = 0;
        for (int i = 0; i < droneSpecViewHolders.size(); i++) {
            DroneSpecViewHolder viewHolder = droneSpecViewHolders.get(i);
            long unsavedMsgCount = viewHolder.client.unsavedMsgCount();
            if (unsavedMsgCount != viewHolder.lastMsgCount) {
                viewHolder.msgCountText.setText(String.format(Locale.US, "%d", unsavedMsgCount));
                viewHolder.lastMsgCount = unsavedMsgCount;
                viewHolder.msgCountText.invalidate();
                changeCount++;
            }
        }
        CTInfo(TAG, String.format(Locale.US,
                "updateViewTextCounts(): made %d changes.", changeCount));
    }


    private void startTextViewUpdater() {
        if (null == viewUpdater) {
            viewUpdater = new DelayedExec();
        }
        if (!viewUpdater.isRunning()) {
            CTInfo(TAG, "getView() starting updater.");
            viewUpdater.start(this::updateViewTextCounts, 1000, 1000);
        }
    }
    private void stopTextViewUpdater() {
        if (null != viewUpdater && viewUpdater.isRunning()) {
            CTDebug(TAG, "stopTextViewUpdater(): stopping updater.");
            viewUpdater.stop();
        }
    }

    @Override public void onDismiss(@NonNull DialogInterface dialog) {
        stopTextViewUpdater();
        super.onDismiss(dialog);
    }

    @Override public boolean hasStableIds() {return true;}

    @Override public boolean isEmpty() {
        return ((null != currentDroneSpecs) && (!currentDroneSpecs.isEmpty()));
    }

    @Override public int getCount() {
        if (null != currentDroneSpecs) return currentDroneSpecs.size();
        return 0;
    }
    public void droneSpecArrayChanged() {
        long ageInSec = CaltopoClient.GetMaxDisplayAgeInSeconds();
        currentDroneSpecs = CaltopoClient.GetSortedCurrentDroneSpecArray(0);
        CTDebug(TAG, "droneSpecArrayChanged(): " + currentDroneSpecs.size());
        updateViewMaps();
    }

    @Override public Object getItem(int pos) {
        return currentDroneSpecs.get(pos);
    }

    public void updateViewMaps() {
        if (null != mapListView) mapListView.invalidateViews();
    }
/*
    public void runCaltopoDirectConfigPanel() {
        org.ncssar.rid2caltopo.app.CaltopoDirectSettings configPanel = new org.ncssar.rid2caltopo.app.CaltopoDirectSettings();
        FragmentManager mgr = requireActivity().getSupportFragmentManager();
        FragmentTransaction transaction = mgr.beginTransaction();
        CTDebug(TAG, "runCaltopoDirectConfigPanel(): starting CaltopoDirectSettings...");
        configPanel.show(transaction, "CaltopoDirectSettings");
    }
*/
    @Override @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {


        this.inflater = inflater;
        settingsView = inflater.inflate(R.layout.fragment_caltopo_settings, container, false);
        configTitle = settingsView.findViewById(R.id.CtConfigTitle);
        configTitle.setOnClickListener(this);
        saveChanges = settingsView.findViewById(R.id.ct_saveButton);
        saveChanges.setOnClickListener(this);
        saveChanges.setEnabled(false);
        closeButton = settingsView.findViewById(R.id.ct_closeButton);
        closeButton.setOnClickListener(this);


        groupIdText = settingsView.findViewById(R.id.groupIdText);
        groupIdText.setText(CaltopoClient.GetGroupId());
        groupIdText.addTextChangedListener(this);

        mapIdText = settingsView.findViewById(R.id.mapIdEditText);
        mapIdText.setText(CaltopoClient.GetMapId());
        mapIdText.addTextChangedListener(this);

        String archivePathVal = CaltopoClient.GetArchivePath();
        archivePathText = settingsView.findViewById(R.id.archiveDirVal);
        archivePathText.setText(archivePathVal == null ? "<undefined>" : archivePathVal);
        archivePathText.setOnClickListener(this);
        archiveDirButton = settingsView.findViewById(R.id.archiveDirButton);
        archiveDirButton.setOnClickListener(this);

        configInfo = settingsView.findViewById(R.id.CtInfo);

        minChangedText = settingsView.findViewById(R.id.minChangedText);
        minChangedVal = CaltopoClient.GetMinDistanceInFeet();
        minChangedText.setText(String.format(Locale.US, "%d", minChangedVal));
        minChangedText.addTextChangedListener(this);

        newTrackDelayInSecEditText = settingsView.findViewById(R.id.newTrackDelayInSecEditText);
        newTrackDelayInSecEditText.setText(String.format(Locale.US, "%d",
                CaltopoClient.GetNewTrackDelayInSeconds()));
        newTrackDelayInSecEditText.addTextChangedListener(this);

        maxAgeInSecEditText = settingsView.findViewById(R.id.maxAgeInSecEditText);
        maxAgeInSecEditText.setText(String.format(Locale.US, "%d",
                CaltopoClient.GetMaxDisplayAgeInSeconds()));
        maxAgeInSecEditText.addTextChangedListener(this);

        mapListView = settingsView.findViewById(R.id.ct_mapListview);
        mapListView.setAdapter(this);
        droneSpecArrayChanged();

        groupMapLabel = settingsView.findViewById(R.id.groupLabelText);
        directToggle = settingsView.findViewById(R.id.ct_directToggle);

        groupIdText.setText(CaltopoClient.GetGroupId());
        mapIdText.setText(CaltopoClient.GetMapId());
        boolean useDirect = CaltopoClient.GetUseDirectFlag();
        directToggle.setChecked(useDirect);
        mapIdText.setEnabled(useDirect);

        directToggle.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // This block of code will be executed when the checked state changes
            CTDebug(TAG, "directToggle is " + isChecked);
            if (isChecked) {
//                runCaltopoDirectConfigPanel();
                mapIdText.setText(CaltopoClient.GetMapId());
            }
            mapIdText.setEnabled(isChecked);
            CaltopoClient.SetUseDirect(isChecked);
            updateViewMaps();
        });

        Button loadRidConfigButton = settingsView.findViewById(R.id.ct_LoadRidConfig);
        loadRidConfigButton.setOnClickListener((view) -> {
            CaltopoClient.RequestLoadConfigFile();
            droneSpecArrayChanged();
            updateViewMaps();
        });

        Button loadCredentialsButton = settingsView.findViewById(R.id.ct_LoadCredentials);
        loadCredentialsButton.setOnClickListener((view) -> {
            CaltopoClient.RequestLoadConfigFile();
            updateViewMaps();
        });
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