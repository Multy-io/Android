/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Base64;

import com.samwolfand.oneprefs.Prefs;
import com.tozny.crypto.android.AesCbcWithIntegrity;

import net.khirr.library.foreground.Foreground;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.annotation.Nullable;

import io.branch.referral.Branch;
import io.multy.storage.SecurePreferencesHelper;
import io.multy.util.Constants;
import io.multy.util.EntropyProvider;
import io.multy.util.MultyRealmMigration;
import io.realm.Realm;
import io.realm.RealmConfiguration;
import timber.log.Timber;

public class Multy extends Application {

    private static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        Realm.init(this);
        Branch.getAutoInstance(this);
        Timber.plant(new Timber.DebugTree());
//        Timber.plant(new FileLoggingTree(getApplicationContext()));

        new Prefs.Builder()
                .setContext(this)
                .setMode(ContextWrapper.MODE_PRIVATE)
                .setPrefsName(getPackageName())
                .setUseDefaultSharedPreference(true)
                .setDefaultIntValue(-1)
                .setDefaultBooleanValue(false)
                .setDefaultStringValue("")
                .build();

        context = getApplicationContext();

        if (!Prefs.contains(Constants.PREF_VERSION)) {
            PackageInfo pInfo = null;
            try {
                pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                int versionCode = pInfo.versionCode;
                Prefs.putInt(Constants.PREF_VERSION, versionCode);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }

        Foreground.Companion.init(this);
    }

    public static Context getContext() {
        return context;
    }

    @Nullable
    public static RealmConfiguration getRealmConfiguration() {
        return getRealmConfiguration(context);
    }

    @Nullable
    public static RealmConfiguration getRealmConfiguration(Context context) {
        String key = SecurePreferencesHelper.getString(context, Constants.PREF_KEY);

        try {
            Base64.decode(key, Base64.NO_WRAP);
            return new RealmConfiguration.Builder()
                    .encryptionKey(Base64.decode(key, Base64.NO_WRAP))
                    .schemaVersion(9)
                    .migration(new MultyRealmMigration())
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public static boolean makeInitialized() {
        String vector = Prefs.getString(Constants.PREF_IV, "");

        if (vector.equals("")) {
            try {
                byte[] iv = AesCbcWithIntegrity.generateIv();

                if (iv == null || iv.length != 16) {
                    SecureRandom secureRandom = new SecureRandom();
                    iv = new byte[16];
                    secureRandom.nextBytes(iv);
                }

                String ivString = new String(Base64.encode(iv, Base64.NO_WRAP));

                Prefs.putString(Constants.PREF_IV, ivString);
            } catch (GeneralSecurityException e) {
                e.printStackTrace();
                return false;
            }
        }

        try {
            String key = new String(Base64.encode(EntropyProvider.generateKey(512), Base64.NO_WRAP));
            SecurePreferencesHelper.putString(getContext(), Constants.PREF_KEY, key);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return false;
        }

        Prefs.putBoolean(Constants.PREF_APP_INITIALIZED, true);
        return true;
    }
}