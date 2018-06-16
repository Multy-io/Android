/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.ui.activities;

import android.Manifest;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.view.GestureDetectorCompat;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import io.multy.R;
import io.multy.api.MultyApi;
import io.multy.api.socket.BlueSocketManager;
import io.multy.model.entities.FastReceiver;
import io.multy.model.entities.wallet.CurrencyCode;
import io.multy.model.entities.wallet.Wallet;
import io.multy.model.requests.HdTransactionRequestEntity;
import io.multy.storage.RealmManager;
import io.multy.ui.adapters.MyWalletPagerAdapter;
import io.multy.ui.adapters.ReceiversPagerAdapter;
import io.multy.ui.fragments.dialogs.CompleteDialogFragment;
import io.multy.util.Constants;
import io.multy.util.CryptoFormatUtils;
import io.multy.util.JniException;
import io.multy.util.NativeDataHelper;
import io.multy.util.analytics.Analytics;
import io.multy.util.analytics.AnalyticsConstants;
import io.realm.RealmResults;
import io.socket.client.Ack;
import io.socket.client.Socket;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import timber.log.Timber;

import static io.multy.api.socket.BlueSocketManager.EVENT_SENDER_CHECK;
import static io.multy.ui.fragments.send.SendSummaryFragment.byteArrayToHex;

public class TestOperationsActivity extends BaseActivity {

    public static final int UPDATE_PERIOD = 5000;
    public static final int REQUEST_BLUETOOTH = 1;
    public static final int REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS = 2;
    public static final int REQUEST_CODE_LOCATION = 3;

    @BindView(R.id.pager_wallets)
    ViewPager pagerWallets;
    @BindView(R.id.pager_requests)
    ViewPager pagerRequests;
    @BindView(R.id.container_send)
    View containerSend;

    @BindView(R.id.group_send)
    View groupSend;
    @BindView(R.id.text_send_amount)
    TextView textSendAmount;
    @BindView(R.id.text_send_amount_fiat)
    TextView textSendAmountFiat;
    @BindView(R.id.image_coin)
    ImageView imageCoin;
    @BindView(R.id.text_scanning)
    TextView textScanning;

    private ReceiversPagerAdapter receiversPagerAdapter;
    private MyWalletPagerAdapter walletPagerAdapter;
    private GestureDetectorCompat gestureDetector;
    private BlueSocketManager socketManager;
    private ArrayList<String> leIds = new ArrayList<>();
    private long selectedWalletId = -1;
    private Handler handler = new Handler();
    private Runnable updateReceiversAction;
    private ScanCallback bleScanCallback;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_operations);
        ButterKnife.bind(this);
        initGestureDetector();
        initUpdateAction();
        initPagers();
        initPermissions();
        startSockets();

        containerSend.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bleScanCallback != null) {
            startBLeScanner();
        }
    }

    @Override
    protected void onPause() {
        if (bleScanCallback != null) {
            BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner().stopScan(bleScanCallback);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (socketManager.getSocket() != null && socketManager.getSocket().connected()) {
            socketManager.getSocket().disconnect();
            handler.removeCallbacksAndMessages(null);
        }
        bleScanCallback = null;
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == Constants.CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            final String address = data.getStringExtra(Constants.EXTRA_QR_CONTENTS);
            Intent intent = new Intent(this, AssetSendActivity.class);
            intent.putExtra(Constants.EXTRA_ADDRESS, address);
            startActivity(intent);
            finish();
        } else if (requestCode == REQUEST_BLUETOOTH && resultCode == RESULT_OK) {
            start();
        } else if (requestCode != REQUEST_CODE_LOCATION) {
            finish();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS: {
                Map<String, Integer> perms = new HashMap<>();
                perms.put(Manifest.permission.ACCESS_FINE_LOCATION, PackageManager.PERMISSION_GRANTED);

                for (int i = 0; i < permissions.length; i++) {
                    perms.put(permissions[i], grantResults[i]);
                }

                if (perms.get(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    Log.i("wise", "all permissions granted");
                    start();
                } else {
                    finish();
                }
            }
            break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onBackPressed() {
        if (containerSend.getVisibility() == View.VISIBLE) {
            hideSendGroup();
            enableScroll();
            receiversPagerAdapter.showElements(pagerRequests.getCurrentItem());
            walletPagerAdapter.showElements(pagerWallets.getCurrentItem());
        } else {
            super.onBackPressed();
        }
    }

    private void initPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            checkPermissions();
        } else {
            start();
        }
    }

    private void startSockets() {
        socketManager = new BlueSocketManager();
        socketManager.connect();
        socketManager.getSocket().on(Socket.EVENT_CONNECT, args -> handler.postDelayed(updateReceiversAction, UPDATE_PERIOD));
    }

    private void initPagers() {
        initPagerWallets();

//        pagerRequests.setPageMargin(dpToPx(20));
        receiversPagerAdapter = new ReceiversPagerAdapter(getSupportFragmentManager());
        pagerRequests.setAdapter(receiversPagerAdapter);
        pagerRequests.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            }

            @Override
            public void onPageSelected(int position) {
                initPagerWallets();
            }

            @Override
            public void onPageScrollStateChanged(int state) {
            }
        });
    }

    private void initPagerWallets() {
        FastReceiver receiver = null;
        try {
            receiver = receiversPagerAdapter.getReceiver(pagerRequests.getCurrentItem());
        } catch (Exception e) {
            e.printStackTrace();
        }
        List<Wallet> wallets;

        if (receiver == null) {
            wallets = RealmManager.getAssetsDao().getAvailableWallets();
        } else {
            wallets = new ArrayList<>();
            RealmResults<Wallet> resultWallets = RealmManager.getAssetsDao()
                    .getAvailableWallets(receiver.getCurrencyId(), receiver.getNetworkId());
            final BigInteger receiverAmount = new BigInteger(receiver.getAmount());
            for (Wallet wallet : resultWallets) {
                BigInteger availableBalance = new BigInteger(wallet.getAvailableBalance());
                if (availableBalance.compareTo(receiverAmount) >= 0) {
                    wallets.add(wallet);
                }
            }
        }

        walletPagerAdapter = new MyWalletPagerAdapter(getSupportFragmentManager(), v -> {
            handler.removeCallbacksAndMessages(null);
            if (containerSend.getVisibility() == View.VISIBLE) {
                hideSendGroup();
                enableScroll();
                showPagerElements();
                handler.postDelayed(updateReceiversAction, UPDATE_PERIOD / 5);
            } else if (receiversPagerAdapter.getCount() > 0) {
                showSendState();
                disableScroll();
                selectedWalletId = (long) v.getTag();
                hidePagerElements();
            }
        }, wallets);
        pagerWallets.setAdapter(walletPagerAdapter);
    }

    private void showPagerElements() {
        receiversPagerAdapter.showElements(pagerRequests.getCurrentItem());
        walletPagerAdapter.showElements(pagerWallets.getCurrentItem());
        selectedWalletId = -1;
    }

    private void hidePagerElements() {
        receiversPagerAdapter.hideElements(pagerRequests.getCurrentItem());
        walletPagerAdapter.hideElements(pagerWallets.getCurrentItem());
    }

    private void enableScroll() {
        pagerWallets.setOnTouchListener(null);
        pagerRequests.setOnTouchListener(null);
    }

    private void disableScroll() {
        pagerWallets.setOnTouchListener((v, event) -> true);
        pagerRequests.setOnTouchListener((v, event) -> true);
    }

    private void showSendGroup() {
        groupSend.setVisibility(View.GONE);
        groupSend.setVisibility(View.VISIBLE);
    }

    private void hideSendGroup() {
        groupSend.setVisibility(View.GONE);
        groupSend.setVisibility(View.INVISIBLE);
    }

    private void setupSendGroup(String sendAmount, String sendAmountFiat) {
        textSendAmount.setText(sendAmount);
        textSendAmountFiat.setText(sendAmountFiat);
    }

    /**
     * Chain of animations. Image coin showing first. containerSend showing after
     */
    private void showSendState() {
        final float y = imageCoin.getHeight();
        imageCoin.animate().translationYBy(y).setDuration(0).alpha(0).withEndAction(() -> {
            imageCoin.setVisibility(View.VISIBLE);
            imageCoin.animate().translationYBy(-y).setDuration(100).alpha(1).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                final float sendY = containerSend.getHeight() / 2;
                containerSend.animate().translationYBy(sendY).setDuration(0).alpha(0).withEndAction(() -> {
                    containerSend.setVisibility(View.VISIBLE);
                    containerSend.animate().translationYBy(-sendY).setDuration(100).alpha(1).setInterpolator(new DecelerateInterpolator()).start();
                }).start();
            });
        }).start();
        String amount = "";
        String amountFiat = "";
        FastReceiver receiver = receiversPagerAdapter.getReceiver(pagerRequests.getCurrentItem());
        switch (NativeDataHelper.Blockchain.valueOf(receiver.getCurrencyId())) {
            case BTC:
                final long amountBtc = (long) Double.parseDouble(receiver.getAmount());
                amount = CryptoFormatUtils.satoshiToBtcLabel(amountBtc);
                amountFiat = CryptoFormatUtils.satoshiToUsd(amountBtc) + CurrencyCode.USD.name();
                break;
            case ETH:
                amount = CryptoFormatUtils.weiToEthLabel(receiver.getAmount());
                amountFiat = CryptoFormatUtils.weiToUsd(new BigInteger(receiver.getAmount())) + " " + CurrencyCode.USD.name();
                break;
        }
        setupSendGroup(amount, amountFiat);
    }

    private void hideSendState() {
        final float sendY = containerSend.getHeight() / 2;
        containerSend.animate().translationYBy(sendY).setDuration(100).alpha(0).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
            containerSend.setVisibility(View.INVISIBLE);
            containerSend.animate().translationYBy(-sendY).setDuration(0).alpha(0).start();
        }).start();
        hideCoin();
    }

    private void hideCoin() {
        final float y = imageCoin.getHeight();
        imageCoin.animate().translationYBy(y).setDuration(100).alpha(0).withEndAction(() -> {
            imageCoin.setVisibility(View.INVISIBLE);
            imageCoin.animate().translationYBy(-y).setDuration(0).alpha(1).start();
        }).start();
    }

    private boolean send() throws JniException, JSONException {
        final Wallet wallet = RealmManager.getAssetsDao().getWalletById(selectedWalletId);
        final byte[] seed = RealmManager.getSettingsDao().getSeed().getSeed();
        FastReceiver receiver = receiversPagerAdapter.getReceiver(pagerRequests.getCurrentItem());
        String changeAddress = "";
        if (wallet.getCurrencyId() == NativeDataHelper.Blockchain.BTC.getValue()) {
            changeAddress = NativeDataHelper.makeAccountAddress(seed, wallet.getIndex(), wallet.getBtcWallet().getAddresses().size(),
                    wallet.getCurrencyId(), wallet.getNetworkId());
        }

        final byte[] transaction;
        final boolean isHd;
        switch (NativeDataHelper.Blockchain.valueOf(receiver.getCurrencyId())) {
            case BTC:
                transaction = NativeDataHelper.makeTransaction(wallet.getId(), wallet.getNetworkId(), seed, wallet.getIndex(),
                        receiver.getAmount(), "10", "0", receiver.getAddress(), changeAddress, "", true);
                isHd = true;
                break;
            case ETH:
                transaction = NativeDataHelper.makeTransactionETH(seed, wallet.getIndex(), 0, wallet.getCurrencyId(),
                        wallet.getNetworkId(), String.valueOf(wallet.getActiveAddress().getAmount()), receiver.getAmount(),
                        receiver.getAddress(), "21000", "1000000000", wallet.getEthWallet().getNonce());
                isHd = false;
                break;
            default:
                throw new IllegalStateException("No one currency id is not Blockchain value!");
        }

        String hex = byteArrayToHex(transaction);
        switch (NativeDataHelper.Blockchain.valueOf(wallet.getCurrencyId())) {
            case ETH:
                hex = "0x" + hex;
        }
//        final String jwt = Prefs.getString(Constants.PREF_AUTH, "");
        final HdTransactionRequestEntity entity = new HdTransactionRequestEntity(wallet.getCurrencyId(), wallet.getNetworkId(),
                new HdTransactionRequestEntity.Payload(changeAddress, wallet.getAddresses().size(),
                        wallet.getIndex(), hex, isHd));

//        socketManager.getSocket().emit(EVENT_SEND_RAW, new JSONObject(new Gson().toJson(entity)), new Ack() {
//            @Override
//            public void call(Object... args) {
//                TestOperationsActivity.this.runOnUiThread(() -> {
//                    final String result = args[0].toString();
//                    if (result.contains("success")) {
//                        showSuccess();
//                    } else {
//                        showError();
//                    }
//                });
//            }
//        });
        Timber.i("hex=%s", hex);
        Timber.i("change address=%s", changeAddress);
        MultyApi.INSTANCE.sendHdTransaction(entity).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                if (response.isSuccessful()) {
                    showSuccess();
                } else {
                    showError();
                }
            }

            @Override
            public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                t.printStackTrace();
                showError();
            }
        });

        return false;
    }

    private void showSuccess() {
        if (socketManager.getSocket() != null && socketManager.getSocket().connected()) {
            socketManager.disconnect();
        }
        new CompleteDialogFragment().show(getSupportFragmentManager(), "");
    }

    private void showError() {
        Analytics.getInstance(TestOperationsActivity.this).logError(AnalyticsConstants.ERROR_TRANSACTION_API);
        AlertDialog dialog = new AlertDialog.Builder(TestOperationsActivity.this)
                .setTitle(getString(R.string.error_sending_tx))
                .setPositiveButton(R.string.ok, (dialog1, which) -> dialog1.dismiss())
                .setCancelable(false)
                .create();
        dialog.show();
    }

    private void bringViewsBack() {
        final float yTo = textScanning.getY();
        hideSendGroup();
        containerSend.animate().translationYBy(yTo).alpha(1).setDuration(0).start();
    }

    private void animateSend() {
        final float yTo = textScanning.getY();
        hideCoin();
        containerSend.animate().translationY(-yTo)
                .withEndAction(() -> {
                    bringViewsBack();
                    receiversPagerAdapter.showElements(pagerRequests.getCurrentItem());
                    walletPagerAdapter.showElements(pagerWallets.getCurrentItem());
                    enableScroll();

                    try {
                        send();
                    } catch (Throwable t) {
                        t.printStackTrace();
                        showError();
                        return;
                    }

                    receiversPagerAdapter.showSuccess(pagerRequests.getCurrentItem());
                }).setDuration(500).alpha(0).setInterpolator(new AnticipateOvershootInterpolator()).start();
    }

    private void initGestureDetector() {
        gestureDetector = new GestureDetectorCompat(this, new GestureDetector.SimpleOnGestureListener() {

            private static final int SWIPE_MIN_DISTANCE = 300;
            private static final int SWIPE_THRESHOLD_VELOCITY = 100;

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                //&& Math.abs(velocityY) > SWIPE_THRESHOLD_VELOCITY
                if (e1.getY() - e2.getY() > SWIPE_MIN_DISTANCE) {
                    animateSend();
                }
                return true;
            }
        });
        gestureDetector.setIsLongpressEnabled(false);
    }

    private void start() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBT = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBT, REQUEST_BLUETOOTH);
            return;
        }

        final LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Intent enableLocationIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivityForResult(enableLocationIntent, REQUEST_CODE_LOCATION);
        }

        if (bleScanCallback == null) {
            bleScanCallback = createScanCallback();
        }
        startBLeScanner();
    }

    private void startBLeScanner() {
        BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner().startScan(bleScanCallback);
    }

    private ScanCallback createScanCallback() {
        return new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
//                ParcelUuid uuids[] = result.getDevice().getUuids();
                List<ParcelUuid> ids = result.getScanRecord().getServiceUuids();

                if (ids != null && ids.size() > 0) {
                    for (ParcelUuid uuid : ids) {
                        String sendUUID = uuid.getUuid().toString();
                        sendUUID = sendUUID.substring(sendUUID.length() - 8);
                        sendUUID = sendUUID.toUpperCase();
                        Timber.i("onScanResult " + sendUUID);

                        if (sendUUID.length() > 0 && !leIds.contains(sendUUID)) {
                            leIds.add(sendUUID);
                        }
                    }
                }
            }
        };
    }

    private JSONObject getIdsJson() {
        try {
            JSONObject jsonObject = new JSONObject();
            JSONArray jsonArray = new JSONArray();
            for (String id : leIds) {
                jsonArray.put(id);
            }
            jsonObject.put("ids", jsonArray);
            return jsonObject;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void checkPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        final List<String> permissionsList = new ArrayList<>();
        if (!addPermission(permissionsList, Manifest.permission.ACCESS_FINE_LOCATION))
            permissionsNeeded.add("Show Location");

        if (permissionsList.size() > 0) {
            if (permissionsNeeded.size() > 0) {
                String message = getString(R.string.permissions_for_access_nearby_devices);
                showDialog(message,
                        (dialog, which) -> requestPermissions(permissionsList.toArray(new String[permissionsList.size()]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS));
                return;
            }
            requestPermissions(permissionsList.toArray(new String[permissionsList.size()]), REQUEST_CODE_ASK_MULTIPLE_PERMISSIONS);
            return;
        }

        Log.d("wise", "No new permission required");
        start();
    }

    private void showDialog(String message, DialogInterface.OnClickListener okListener) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(getString(R.string.yes), okListener)
                .setNegativeButton(getString(R.string.cancel), (dialog, whitch) -> onBackPressed())
                .create()
                .show();
    }

    @TargetApi(Build.VERSION_CODES.M)
    private boolean addPermission(List<String> permissionsList, String permission) {

        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(permission);
            // Check for Rationale Option
            if (!shouldShowRequestPermissionRationale(permission))
                return false;
        }
        return true;
    }


    private void emitUpdateReceivers() {
        try {
            socketManager.getSocket().emit(EVENT_SENDER_CHECK, getIdsJson(), (Ack) args -> TestOperationsActivity.this.runOnUiThread(() -> {
                int prevNetworkId = -1;
                int prevCurrencyId = -1;

                if (receiversPagerAdapter.getCount() != 0) {
                    FastReceiver fastReceiver = receiversPagerAdapter.getReceiver(pagerRequests.getCurrentItem());
                    if (fastReceiver != null) {
                        prevCurrencyId = fastReceiver.getCurrencyId();
                        prevNetworkId = fastReceiver.getNetworkId();
                    }
                }

                String json = args[0].toString();
                FastReceiver[] receivers = new Gson().fromJson(json, FastReceiver[].class);
                ArrayList<FastReceiver> list = new ArrayList(Arrays.asList(receivers));

                if (receivers != null && receiversPagerAdapter != null) {
                    receiversPagerAdapter.setData(list);
                }

                if (receiversPagerAdapter.getCount() != 0) {
                    FastReceiver fastReceiver = receiversPagerAdapter.getReceiver(pagerRequests.getCurrentItem());
                    if (fastReceiver != null) {
                        if (prevCurrencyId != fastReceiver.getCurrencyId() || prevNetworkId != fastReceiver.getNetworkId()) {
                            initPagerWallets();
                        }
                    }
                }

                if (selectedWalletId != -1) {
                    enableScroll();
                    showPagerElements();
                }

            }));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initUpdateAction() {
        updateReceiversAction = () -> {
            emitUpdateReceivers();
            handler.postDelayed(updateReceiversAction, UPDATE_PERIOD);
        };
    }

    public int dpToPx(int dps) {
        int px = (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dps, getResources().getDisplayMetrics()));
        return px;
    }

    @OnClick(R.id.button_qr)
    public void onClickQr() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, Constants.CAMERA_REQUEST_CODE);
        } else {
            startActivityForResult(new Intent(this, ScanActivity.class), Constants.CAMERA_REQUEST_CODE);
        }
    }

    @OnClick(R.id.button_cancel)
    public void onClickCancel() {
        onBackPressed();
    }
}
