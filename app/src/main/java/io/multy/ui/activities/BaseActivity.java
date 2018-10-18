/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.ui.activities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.messaging.FirebaseMessaging;
import com.samwolfand.oneprefs.Prefs;

import net.khirr.library.foreground.Foreground;

import io.multy.R;
import io.multy.model.entities.UserId;
import io.multy.storage.RealmManager;
import io.multy.storage.SecurePreferencesHelper;
import io.multy.ui.adapters.PinDotsAdapter;
import io.multy.ui.adapters.PinNumbersAdapter;
import io.multy.ui.fragments.dialogs.SimpleDialogFragment;
import io.multy.util.Constants;

public class BaseActivity extends AppCompatActivity implements PinNumbersAdapter.OnFingerPrintClickListener, PinNumbersAdapter.OnNumberClickListener, PinNumbersAdapter.OnBackSpaceClickListener {

    private static final String TAG = BaseActivity.class.getSimpleName();

    private int count = 6;
    private Foreground.Listener foregroundListener;
    private RecyclerView.LayoutManager dotsLayoutManager;
    private StringBuilder stringBuilder;
    private boolean isLockVisible = false;
    private OnLockCloseListener onLockCLoseListener;

    public void hideKeyboard(Activity activity) {
        if (activity != null && activity.getWindow() != null && activity.getWindow().getDecorView() != null) {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0);
        }
    }

    public void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
        }
    }

    public void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && imm.isActive()) {
            imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (Prefs.getBoolean(Constants.PREF_APP_INITIALIZED)) {
            if (!isLockVisible) {
                if (RealmManager.open() == null) {
                    //TODO resolve issues with realm
//                    finish();
//                    Intent splashIntent = new Intent(this, SplashActivity.class);
//                    splashIntent.putExtra(SplashActivity.RESET_FLAG, true);
//                    startActivity(splashIntent);
                }
            }
            count = 6;
        }

        if (Prefs.getBoolean(Constants.PREF_LOCK)) {
            this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        }

        foregroundListener = new Foreground.Listener() {
            @Override
            public void background() {
                if (Prefs.getBoolean(Constants.PREF_APP_INITIALIZED)) {
                    RealmManager.close();
                }

                if (Prefs.getBoolean(Constants.PREF_LOCK)) {
                    showLock();
                }
            }

            @Override
            public void foreground() {
                if (Prefs.getBoolean(Constants.PREF_APP_INITIALIZED)) {
                    if (RealmManager.open() == null) {
//                        TODO resolve issue with realm
//                        finish();
//                        Intent splashIntent = new Intent(BaseActivity.this, SplashActivity.class);
//                        splashIntent.putExtra(SplashActivity.RESET_FLAG, true);
//                        startActivity(splashIntent);
                    }
                }


            }
        };

        Foreground.Companion.addListener(foregroundListener);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (isLockVisible && Prefs.getBoolean(Constants.PREF_UNLOCKED, false)) {
            hideLock();
        }
    }

    @Override
    protected void onDestroy() {
        Foreground.Companion.removeListener(foregroundListener);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();

        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (getCurrentFocus() != null && inputManager != null) {
            inputManager.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            inputManager.hideSoftInputFromInputMethod(getCurrentFocus().getWindowToken(), 0);
        }
    }

    protected void showMessage(String message) {
        showMessage(null, message);
    }

    protected void showMessage(String title, String message) {
        SimpleDialogFragment dialog = SimpleDialogFragment
                .newInstanceNegative(title == null ? getString(R.string.error) : title, message, null);
        dialog.setTitleResId(R.string.error);
        dialog.setMessageResId(R.string.error);
        dialog.show(getSupportFragmentManager(), "");
    }

    public void showLock() {
        if (!Prefs.getBoolean(Constants.PREF_LOCK)) {
            return;
        }

        View fastOperation = findViewById(R.id.fast_operations);
        if (fastOperation != null) {
            fastOperation.setVisibility(View.GONE);
        }

        ViewGroup viewGroup = findViewById(R.id.container_main);
        if (viewGroup != null) {
            ViewGroup pinHolder = viewGroup.findViewById(R.id.holder_pin);
            if (pinHolder == null) {
                pinHolder = (ViewGroup) getLayoutInflater().inflate(R.layout.holder_pin, viewGroup, false);
                viewGroup.addView(pinHolder);
            }

            View pinView = getLayoutInflater().inflate(R.layout.layout_pin, viewGroup, false);
            TextView textTitle = pinView.findViewById(R.id.text_title);
            textTitle.setText(R.string.enter_password);
            textTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

            RecyclerView recyclerViewDots = pinView.findViewById(R.id.recycler_view_dots);
            RecyclerView recyclerViewNumbers = pinView.findViewById(R.id.recycler_view_numbers);

            dotsLayoutManager = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
            recyclerViewDots.setAdapter(new PinDotsAdapter());
            recyclerViewDots.setLayoutManager(dotsLayoutManager);
            recyclerViewDots.setHasFixedSize(true);

            recyclerViewNumbers.setAdapter(new PinNumbersAdapter(this, this, this, true));
            recyclerViewNumbers.setLayoutManager(new GridLayoutManager(this, 3));
            recyclerViewNumbers.setHasFixedSize(true);

            stringBuilder = new StringBuilder();

            View previousView = pinHolder.findViewById(R.id.container_pin);
            if (previousView != null) {
                pinHolder.removeViewInLayout(previousView);
            }

            pinHolder.addView(pinView);
            if (pinHolder.getVisibility() != View.VISIBLE) {
                pinHolder.setVisibility(View.VISIBLE);
            }

//            viewGroup.setFocusable(true);
//            viewGroup.setClickable(true);

            isLockVisible = true;
            Prefs.putBoolean(Constants.PREF_UNLOCKED, false);
        }
    }

    private void hideLock() {
        if (!Prefs.getBoolean(Constants.PREF_LOCK)) {
            return;
        }

        View fastOperation = findViewById(R.id.fast_operations);
        if (fastOperation != null) {
            fastOperation.setVisibility(View.VISIBLE);
        }

        ViewGroup viewGroup = findViewById(R.id.container_main);
        if (viewGroup != null) {
            View holder = findViewById(R.id.holder_pin);
//            View locker = findViewById(R.id.container_pin);
            viewGroup.removeViewInLayout(holder);
//            viewGroup.removeViewInLayout(locker);
            isLockVisible = false;
        }

        if (RealmManager.open() == null) {
            finish();
            Intent splashIntent = new Intent(this, SplashActivity.class);
            splashIntent.putExtra(SplashActivity.RESET_FLAG, true);
            startActivity(splashIntent);
        }
        Prefs.putBoolean(Constants.PREF_UNLOCKED, true);
        if (onLockCLoseListener != null) {
            onLockCLoseListener.onLockClosed();
        }
    }

    @Override
    public void onFingerprintClick() {

    }

    @Override
    public void onNumberClick(int number) {
        stringBuilder.append(String.valueOf(number));
        if (stringBuilder.length() > 6) {
            return;
        }

        ImageView dot = (ImageView) dotsLayoutManager.getChildAt(stringBuilder.toString().length() - 1);
        dot.setBackgroundResource(R.drawable.circle_white);

        if (stringBuilder.toString().length() == 6) {
            if (!SecurePreferencesHelper.getString(this, Constants.PREF_PIN).equals(stringBuilder.toString())) {
                if (count != 1) {
                    count--;
                    showLock();
                    Toast.makeText(this, count + " number of tries remain", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, count + "You reached maximum, number of tries", Toast.LENGTH_LONG).show();
                    finish();
                }
            } else {
                new Handler().postDelayed(this::hideLock, 500);

            }
        }
    }

    @Override
    public void onBackPressed() {
        if (!Prefs.getBoolean(Constants.PREF_LOCK) || Prefs.getBoolean(Constants.PREF_UNLOCKED, true)) {
            super.onBackPressed();
        } else {
            ActivityCompat.finishAffinity(this);
        }
    }

    @Override
    public void onBackSpaceClick() {
        if (stringBuilder.length() > 0) {
            ImageView dot = (ImageView) dotsLayoutManager.getChildAt(stringBuilder.toString().length() - 1);
            dot.setBackgroundResource(R.drawable.circle_border_white);
            stringBuilder.deleteCharAt(stringBuilder.length() - 1);
        }
    }

    public boolean isLockVisible() {
        return isLockVisible;
    }

    public void setOnLockCLoseListener(OnLockCloseListener onLockCLoseListener) {
        this.onLockCLoseListener = onLockCLoseListener;
    }

    public interface OnLockCloseListener {
        void onLockClosed();
    }

    public void subscribeToPushNotifications() {
        if (Prefs.getBoolean(Constants.PREF_APP_INITIALIZED)) {
            UserId userId = RealmManager.getSettingsDao().getUserId();
            if (userId != null) {
                FirebaseMessaging.getInstance().subscribeToTopic(Constants.PUSH_TOPIC + userId.getUserId());
            }
        }
    }
}
