package com.cyanogenmod.settings.device;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.NumberKeyListener;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;

public class DeviceSettings extends PreferenceActivity
        implements OnPreferenceChangeListener, OnSharedPreferenceChangeListener {
    private static final String TAG = "DefyParts";

    private PreferenceCategory generalSettings;
    private ListPreference chargeLedModePref;
    private ListPreference touchPointsPref;
    private Preference basebandPref=null;

    private CheckBoxPreference mLtoDownloadEnabledPref;
    private ListPreference mLtoDownloadIntervalPref;
    private ListPreference mLtoDownloadFilePref;
    private CheckBoxPreference mLtoDownloadWifiOnlyPref;
    private Preference mLtoDownloadNowPref;

    private static final String PROP_CHARGE_LED_MODE = "persist.sys.charge_led";
    private static final String PROP_TOUCH_POINTS = "persist.sys.multitouch";
    private static final String FILE_TOUCH_POINTS = "/proc/multitouch/num";

    private BroadcastReceiver mLtoStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(LtoDownloadService.EXTRA_STATE, LtoDownloadService.STATE_IDLE);

            mLtoDownloadNowPref.setEnabled(state == LtoDownloadService.STATE_IDLE);
            if (state == LtoDownloadService.STATE_IDLE) {
                boolean success = intent.getBooleanExtra(LtoDownloadService.EXTRA_SUCCESS, true);
                long timestamp = intent.getLongExtra(LtoDownloadService.EXTRA_TIMESTAMP, 0);
                updateLtoDownloadDateSummary(success, timestamp == 0 ? null : new Date(timestamp));
            } else {
                int progress = intent.getIntExtra(LtoDownloadService.EXTRA_PROGRESS, 0);
                updateLtoDownloadProgressSummary(progress);
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.settings);

        generalSettings = (PreferenceCategory) getPreferenceScreen().findPreference("general");
        chargeLedModePref = (ListPreference) generalSettings.findPreference("charge_led_mode");
        chargeLedModePref.setOnPreferenceChangeListener(this);
        touchPointsPref = (ListPreference) generalSettings.findPreference("touch_points");
        touchPointsPref.setOnPreferenceChangeListener(this);

        PreferenceCategory otherSettings = (PreferenceCategory) getPreferenceScreen().findPreference("other");
        basebandPref = (Preference) otherSettings.findPreference("baseband_selection");
        basebandPref.setOnPreferenceChangeListener(this);

        mLtoDownloadEnabledPref = (CheckBoxPreference) otherSettings.findPreference("lto_download_enabled");
        mLtoDownloadEnabledPref.setOnPreferenceChangeListener(this);
        mLtoDownloadIntervalPref = (ListPreference) otherSettings.findPreference("lto_download_interval");
        mLtoDownloadIntervalPref.setOnPreferenceChangeListener(this);
        mLtoDownloadFilePref = (ListPreference) otherSettings.findPreference("lto_download_file");
        mLtoDownloadFilePref.setOnPreferenceChangeListener(this);
        mLtoDownloadWifiOnlyPref = (CheckBoxPreference) otherSettings.findPreference("lto_download_wifi_only");
        mLtoDownloadWifiOnlyPref.setOnPreferenceChangeListener(this);
        mLtoDownloadNowPref = otherSettings.findPreference("lto_download_now");
    }

    @Override
    public void onResume() {
        super.onResume();

        chargeLedModePref.setValue(SystemProperties.get(PROP_CHARGE_LED_MODE));
        touchPointsPref.setValue(SystemProperties.get(PROP_TOUCH_POINTS));
        updateLtoDownloadDateSummary(true, null);
        updateLtoIntervalSummary();

        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        registerReceiver(mLtoStateReceiver, new IntentFilter(LtoDownloadService.ACTION_STATE_CHANGE));
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(mLtoStateReceiver);
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen screen, Preference preference) {
        if (preference == mLtoDownloadNowPref) {
            invokeLtoDownloadService(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == chargeLedModePref) {
            SystemProperties.set(PROP_CHARGE_LED_MODE, (String) newValue);
            /* make NotificationManagerService update the LED, so the new setting takes effect */
            sendBroadcast(new Intent("com.android.server.NotificationManagerService.UPDATE_LED"));
        } else if (preference == touchPointsPref) {
            final String value = (String) newValue;
            final String oldValue = touchPointsPref.getValue();
            final CharSequence defaultValue = touchPointsPref.getEntryValues()[0];

            /* only show warning when moving away from the default value */
            if (TextUtils.equals(value, defaultValue) || !TextUtils.equals(oldValue, defaultValue)) {
                setTouchPointSetting(value);
            } else {
                AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.touch_point_warning_title)
                    .setMessage(R.string.touch_point_warning_message)
                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            setTouchPointSetting(value);
                            touchPointsPref.setValue(value);
                        }
                    })
                    .setNegativeButton(android.R.string.no, null)
                    .create();

                dialog.show();
                return false;
            }
        } else if (preference == basebandPref) {
            showRebootPrompt();
        } else if (preference == mLtoDownloadEnabledPref
                || preference == mLtoDownloadIntervalPref
                || preference == mLtoDownloadFilePref
                || preference == mLtoDownloadWifiOnlyPref) {
            invokeLtoDownloadService(false);
        }

        return true;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences pref, String key) {
        if (key.equals(mLtoDownloadIntervalPref.getKey())) {
            updateLtoIntervalSummary();
        }
    }

    private void updateLtoIntervalSummary() {
        CharSequence value = mLtoDownloadIntervalPref.getEntry();
        mLtoDownloadIntervalPref.setSummary(
                getResources().getString(R.string.lto_download_interval_summary, value));
    }

    private void updateLtoDownloadProgressSummary(int progress) {
        mLtoDownloadNowPref.setSummary(
                getResources().getString(R.string.lto_downloading_data, progress));
    }

    private void updateLtoDownloadDateSummary(boolean success, Date timestamp) {
        Resources res = getResources();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final String lastDownloadString;
        int resId = R.string.lto_last_download_date;

        if (timestamp == null) {
            long lastDownload = prefs.getLong(LtoDownloadService.KEY_LAST_DOWNLOAD, 0);
            if (lastDownload != 0) {
                timestamp = new Date(lastDownload);
            }
        }

        if (timestamp != null) {
            lastDownloadString = DateFormat.getDateTimeInstance().format(timestamp);
            if (!success) {
                resId = R.string.lto_last_download_date_failure;
            }
        } else {
            lastDownloadString = res.getString(R.string.never);
        }

        mLtoDownloadNowPref.setSummary(res.getString(resId, lastDownloadString));
    }

    private void invokeLtoDownloadService(boolean forceDownload) {
        Intent intent = new Intent(this, LtoDownloadService.class);
        intent.putExtra(LtoDownloadService.EXTRA_FORCE_DOWNLOAD, forceDownload);
        startService(intent);
    }

    private void showRebootPrompt() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reboot_prompt_title)
                .setMessage(R.string.reboot_prompt_message)
                .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                        pm.reboot(null);
                    }
                })
                .setNegativeButton(R.string.no, null)
                .create();

        dialog.show();
    }

    private void setTouchPointSetting(String value) {
        /* write the setting into the property to make it apply on next reboot */
        SystemProperties.set(PROP_TOUCH_POINTS, value);

        /* and also write it into the file to make it apply instantly */
        File touchPointsFile = new File(FILE_TOUCH_POINTS);
        FileWriter writer = null;
        try {
            writer = new FileWriter(touchPointsFile);
            writer.write(value);
        } catch (IOException e) {
            Log.e(TAG, "Could not apply touch point setting.", e);
            return;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    Log.w(TAG, "Closing the touch point file failed.", e);
                }
            }
        }
    }
}
