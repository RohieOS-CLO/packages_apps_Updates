/*
 * Copyright (C) 2017 The LineageOS Project
 * Copyright (C) 2019 The PixelExperience Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aospextended.ota.misc;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import com.aospextended.ota.R;
import com.aospextended.ota.controller.UpdaterService;
import com.aospextended.ota.model.Update;
import com.aospextended.ota.model.UpdateBaseInfo;
import com.aospextended.ota.model.UpdateInfo;
import com.aospextended.ota.model.UpdateStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Utils {

    private static final String TAG = "Utils";

    private static final int BATTERY_PLUGGED_ANY = BatteryManager.BATTERY_PLUGGED_AC
            | BatteryManager.BATTERY_PLUGGED_USB
            | BatteryManager.BATTERY_PLUGGED_WIRELESS;

    private Utils() {
    }

    public static File getDownloadPath() {
        return new File(Constants.DOWNLOAD_PATH);
    }

    public static File getExportPath() {
        File dir = new File(Environment.getExternalStorageDirectory(), Constants.EXPORT_PATH);
        if (!dir.isDirectory()) {
            if (dir.exists() || !dir.mkdirs()) {
                throw new RuntimeException("Could not create directory");
            }
        }
        return dir;
    }

    public static File getCachedUpdateList(Context context) {
        return new File(context.getCacheDir(), "updates_v2.json");
    }

    // This should really return an UpdateBaseInfo object, but currently this only
    // used to initialize UpdateInfo objects
    private static UpdateInfo parseJsonUpdate(JSONObject object, Context context) throws JSONException {
        Update update = new Update();
        update.setTimestamp(object.getLong("datetime"));
        update.setName(object.getString("filename"));
        update.setDownloadId(object.getString("id"));
        update.setFileSize(object.getLong("size"));
        update.setDownloadUrl(object.getString("url"));
        update.setVersion(object.getString("version"));
        update.setHash(object.getString("filehash"));
        update.setMaintainer(object.getString("maintainer"));
        update.setMaintainerUrl(object.getString("maintainer_url"));
        update.setDonateUrl("https://github.com/RampageOS-Devices/official_devices");
        update.setForumUrl("https://github.com/RampageOS-Devices/official_devices");
        update.setWebsiteUrl("https://github.com/RampageOS");
        update.setNewsUrl("https://github.com/RampageOS-Devices/official_devices");
        return update;
    }

    public static boolean isCompatible(UpdateBaseInfo update) {
       // if (update.getVersion().compareTo(SystemProperties.get(Constants.PROP_VERSION_CODE)) < 0) {
       //     Log.d(TAG, update.getName() + " with version " + update.getVersion() + " is older than current Android version " + SystemProperties.get(Constants.PROP_BUILD_VERSION));
       //     return false;
       // }
        if (update.getTimestamp() <= SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)) {
            Log.d(TAG, update.getName() + " with timestamp " + update.getTimestamp() + " is older than/equal to the current build " + SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0));
            return false;
        }
        return true;
    }

    public static boolean canInstall(UpdateBaseInfo update) {
        return (update.getTimestamp() > SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0));
    }

    public static UpdateInfo parseJson(File file, boolean compatibleOnly, Context context)
            throws IOException, JSONException {

        StringBuilder json = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            for (String line; (line = br.readLine()) != null; ) {
                json.append(line);
            }
        }

        JSONObject obj = new JSONObject(json.toString());
        try {
            UpdateInfo update = parseJsonUpdate(obj, context);
            if (!compatibleOnly || isCompatible(update)) {
                return update;
            } else {
                Log.d(TAG, "Ignoring incompatible update " + update.getName());
            }
        } catch (JSONException e) {
            Log.e(TAG, "Could not parse update object", e);
        }

        return null;
    }

    public static String getServerURL() {
        return String.format(Constants.OTA_URL, SystemProperties.get(Constants.PROP_DEVICE), SystemProperties.get(Constants.PROP_VERSION_CODE));
    }

    public static String getDownloadWebpageUrl(String fileName) {
        return String.format(Constants.DOWNLOAD_WEBPAGE_URL, SystemProperties.get(Constants.PROP_DEVICE), SystemProperties.get(Constants.PROP_VERSION_CODE), fileName);
    }

    public static void triggerUpdate(Context context, Boolean isLocal) {
        final Intent intent = new Intent(context, UpdaterService.class);
        intent.setAction(isLocal ? UpdaterService.ACTION_INSTALL_LOCAL_UPDATE : UpdaterService.ACTION_INSTALL_UPDATE);
        context.startService(intent);
    }

    public static void rebootDevice(Context mContext) {
        PowerManager pm =
                (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        pm.reboot(null);
    }

    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        NetworkInfo info = cm.getActiveNetworkInfo();
        return !(info == null || !info.isConnected() || !info.isAvailable());
    }

    public static boolean isOnWifiOrEthernet(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        NetworkInfo info = cm.getActiveNetworkInfo();
        return (info != null && (info.getType() == ConnectivityManager.TYPE_ETHERNET
                || info.getType() == ConnectivityManager.TYPE_WIFI));
    }

    public static boolean checkForNewUpdates(File oldJson, File newJson, boolean fromBoot, Context context)
            throws IOException, JSONException {
        if (!oldJson.exists() || fromBoot) {
            return parseJson(newJson, true, context) != null;
        }
        UpdateInfo oldUpdate = parseJson(oldJson, true, context);
        UpdateInfo newUpdate = parseJson(newJson, true, context);
        if (oldUpdate == null || newUpdate == null) {
            return false;
        }
        return !oldUpdate.getDownloadId().equals(newUpdate.getDownloadId());
    }

    public static long getZipEntryOffset(ZipFile zipFile, String entryPath) {
        // Each entry has an header of (30 + n + m) bytes
        // 'n' is the length of the file name
        // 'm' is the length of the extra field
        final int FIXED_HEADER_SIZE = 30;
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        long offset = 0;
        while (zipEntries.hasMoreElements()) {
            ZipEntry entry = zipEntries.nextElement();
            int n = entry.getName().length();
            int m = entry.getExtra() == null ? 0 : entry.getExtra().length;
            int headerSize = FIXED_HEADER_SIZE + n + m;
            offset += headerSize;
            if (entry.getName().equals(entryPath)) {
                return offset;
            }
            offset += entry.getCompressedSize();
        }
        Log.e(TAG, "Entry " + entryPath + " not found");
        throw new IllegalArgumentException("The given entry was not found");
    }

    private static void removeUncryptFiles(File downloadPath) {
        File[] uncryptFiles = downloadPath.listFiles(
                (dir, name) -> name.endsWith(Constants.UNCRYPT_FILE_EXT));
        if (uncryptFiles == null) {
            return;
        }
        for (File file : uncryptFiles) {
            Log.d(TAG, "Deleting " + file.getAbsolutePath());
            try {
                file.delete();
            } catch (Exception e) {
                Log.e(TAG, "Failed to delete " + file.getAbsolutePath(), e);
            }
        }
    }

    public static void cleanupDownloadsDir(Context context) {
        File downloadPath = getDownloadPath();
        removeUncryptFiles(downloadPath);
        Log.d(TAG, "Cleaning " + downloadPath);
        if (!downloadPath.isDirectory()) {
            return;
        }
        File[] files = downloadPath.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            Log.d(TAG, "Deleting " + file.getAbsolutePath());
            try{
                file.delete();
            }catch (Exception e){
                Log.e(TAG, "Failed to delete " + file.getAbsolutePath(), e);
            }
        }
    }

    public static boolean isABDevice() {
        return SystemProperties.getBoolean(Constants.PROP_AB_DEVICE, false);
    }

    public static boolean isEncrypted(Context context, File file) {
        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) {
            return false;
        }
        return sm.isEncrypted(file);
    }

    public static long getUpdateCheckInterval() {
        return AlarmManager.INTERVAL_DAY;
    }

    public static String calculateMD5(File updateFile) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Exception while getting digest", e);
            return null;
        }

        InputStream is;
        try {
            is = new FileInputStream(updateFile);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Exception while getting FileInputStream", e);
            return null;
        }

        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] md5sum = digest.digest();
            BigInteger bigInt = new BigInteger(1, md5sum);
            String output = bigInt.toString(16);
            // Fill to 32 chars
            output = String.format("%32s", output).replace(' ', '0');
            return output;
        } catch (IOException e) {
            throw new RuntimeException("Unable to process file for MD5", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                Log.e(TAG, "Exception on closing MD5 input stream", e);
            }
        }
    }

    @SuppressLint("DefaultLocale")
    public static String readableFileSize(long size) {
        String[] units = new String[]{"B", "kB", "MB", "GB", "TB", "PB"};
        int mod = 1024;
        double power = (size > 0) ? Math.floor(Math.log(size) / Math.log(mod)) : 0;
        String unit = units[(int) power];
        double result = size / Math.pow(mod, power);
        if (unit.equals("B") || unit.equals("kB") || unit.equals("MB")) {
            result = (int) result;
            return String.format("%d %s", (int) result, unit);
        }
        return String.format("%01.2f %s", result, unit);
    }

    public static int getPersistentStatus(Context context){
        SharedPreferences preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getInt(Constants.PREF_CURRENT_PERSISTENT_STATUS, UpdateStatus.Persistent.UNKNOWN);
    }

    @SuppressLint("ApplySharedPref")
    public static void setPersistentStatus(Context context, int status){
        SharedPreferences preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().putInt(Constants.PREF_CURRENT_PERSISTENT_STATUS, status).commit();
    }

    public static boolean isBatteryLevelOk(Context mContext) {
        Intent intent = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
            return true;
        }
        int percent = Math.round(100.f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int required = (plugged & BATTERY_PLUGGED_ANY) != 0 ?
                mContext.getResources().getInteger(R.integer.battery_ok_percentage_charging) :
                mContext.getResources().getInteger(R.integer.battery_ok_percentage_discharging);
        return percent >= required;
    }
}
