/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.viewmodels;

import android.app.Activity;
import android.app.PendingIntent;
import android.arch.lifecycle.MutableLiveData;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.annotation.NonNull;
import android.widget.Toast;

import com.samwolfand.oneprefs.Prefs;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

import io.multy.Multy;
import io.multy.R;
import io.multy.api.MultyApi;
import io.multy.api.socket.CurrenciesRate;
import io.multy.api.socket.SocketManager;
import io.multy.api.socket.TransactionUpdateEntity;
import io.multy.model.entities.TransactionHistory;
import io.multy.model.entities.wallet.BtcWallet;
import io.multy.model.entities.wallet.EthWallet;
import io.multy.model.entities.wallet.Wallet;
import io.multy.model.entities.wallet.WalletAddress;
import io.multy.model.requests.UpdateWalletNameRequest;
import io.multy.model.responses.ServerConfigResponse;
import io.multy.model.responses.TransactionHistoryResponse;
import io.multy.storage.RealmManager;
import io.multy.ui.fragments.asset.AssetInfoFragment;
import io.multy.util.Constants;
import io.multy.util.FirstLaunchHelper;
import io.multy.util.JniException;
import io.multy.util.NativeDataHelper;
import io.multy.util.SingleLiveEvent;
import io.multy.util.analytics.Analytics;
import io.multy.util.analytics.AnalyticsConstants;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.realm.Realm;
import io.realm.RealmList;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import static android.content.Intent.ACTION_SEND;

public class WalletViewModel extends BaseViewModel {

    public MutableLiveData<Wallet> wallet = new MutableLiveData<>();
    public MutableLiveData<String> chainCurrency = new MutableLiveData<>();
    public MutableLiveData<String> fiatCurrency = new MutableLiveData<>();
    private MutableLiveData<Boolean> isWalletUpdated = new MutableLiveData<>();
    public MutableLiveData<List<WalletAddress>> addresses = new MutableLiveData<>();
    public MutableLiveData<Boolean> isRemoved = new MutableLiveData<>();
    public MutableLiveData<CurrenciesRate> rates = new MutableLiveData<>();
    public MutableLiveData<ArrayList<TransactionHistory>> transactions = new MutableLiveData<>();
    public SingleLiveEvent<TransactionUpdateEntity> transactionUpdate = new SingleLiveEvent<>();

    private SocketManager socketManager;
    private long walletId = -1;

    public WalletViewModel() {
    }

    public void subscribeSocketsUpdate() {
        socketManager = new SocketManager();
        socketManager.connect(rates, transactionUpdate);
    }

    public void unsubscribeSocketsUpdate() {
        if (socketManager != null) {
            socketManager.disconnect();
        }
    }

    public MutableLiveData<List<WalletAddress>> getAddresses() {
        return addresses;
    }

    public Wallet getWallet(long id) {
        Wallet wallet = RealmManager.getAssetsDao().getWalletById(id);
        this.wallet.setValue(wallet);
        this.walletId = id;
        return wallet;
    }

    public MutableLiveData<Wallet> getWalletLive() {
        if (!wallet.getValue().isValid() && walletId != -1) {
            wallet.setValue(getWallet(walletId));
        }
        return wallet;
    }

    public static void saveDonateAddresses() {
        ServerConfigResponse serverConfig = EventBus.getDefault().removeStickyEvent(ServerConfigResponse.class);
        if (serverConfig != null) {
            RealmManager.getSettingsDao().saveDonation(serverConfig.getDonates());
        }
    }

    public Wallet createWallet(String walletName, int blockChainId, int networkId) {
        isLoading.postValue(true);
        Wallet walletRealmObject = null;
        try {
            if (!Prefs.getBoolean(Constants.PREF_APP_INITIALIZED)) {

                final boolean initialized = Multy.makeInitialized();
                if (!initialized) {
                    errorMessage.postValue(Multy.getContext().getString(R.string.error_initializing_wallet));
                    return null;
                }

                RealmManager.open();
                FirstLaunchHelper.setCredentials("");
                saveDonateAddresses();
            }

            final int topIndex = blockChainId == NativeDataHelper.Blockchain.BTC.getValue() ?
                    Prefs.getInt(Constants.PREF_WALLET_TOP_INDEX_BTC + networkId, 0) :
                    Prefs.getInt(Constants.PREF_WALLET_TOP_INDEX_ETH + networkId, 0);

//            if (!Prefs.getBoolean(Constants.PREF_APP_INITIALIZED)) {
//                FirstLaunchHelper.setCredentials("");
//            }

            String creationAddress = NativeDataHelper.makeAccountAddress(RealmManager.getSettingsDao().getSeed().getSeed(),
                    topIndex, 0, blockChainId, networkId);
            walletRealmObject = new Wallet();
            walletRealmObject.setWalletName(walletName);

            RealmList<WalletAddress> addresses = new RealmList<>();
            addresses.add(new WalletAddress(0, creationAddress));

            switch (NativeDataHelper.Blockchain.valueOf(blockChainId)) {
                case BTC:
                    walletRealmObject.setBtcWallet(new BtcWallet());
                    walletRealmObject.getBtcWallet().setAddresses(addresses);
                    break;
                case ETH:
                    walletRealmObject.setEthWallet(new EthWallet());
                    walletRealmObject.getEthWallet().setAddresses(addresses);
                    break;
            }

            walletRealmObject.setCurrencyId(blockChainId);
            walletRealmObject.setNetworkId(networkId);
            walletRealmObject.setCreationAddress(creationAddress);
            walletRealmObject.setIndex(topIndex);
        } catch (JniException e) {
            e.printStackTrace();
            isLoading.postValue(false);
            errorMessage.postValue(e.getLocalizedMessage());
            errorMessage.call();
        }
        return walletRealmObject;
    }

    public MutableLiveData<ArrayList<TransactionHistory>> getTransactionsHistory(final int currencyId, final int networkId, final int walletIndex) {
        MultyApi.INSTANCE.getTransactionHistory(currencyId, networkId, walletIndex).enqueue(new Callback<TransactionHistoryResponse>() {
            @Override
            public void onResponse(@NonNull Call<TransactionHistoryResponse> call, @NonNull Response<TransactionHistoryResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    transactions.setValue(response.body().getHistories());
                }
            }

            @Override
            public void onFailure(Call<TransactionHistoryResponse> call, Throwable throwable) {
                throwable.printStackTrace();
            }
        });
        return transactions;
    }

    public MutableLiveData<Boolean> updateWalletSetting(String newName) {
        int index = wallet.getValue().getIndex();
        int currencyId = wallet.getValue().getCurrencyId();
        UpdateWalletNameRequest updateWalletName = new UpdateWalletNameRequest(newName, currencyId, index, wallet.getValue().getNetworkId());
        MultyApi.INSTANCE.updateWalletName(updateWalletName).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (response.isSuccessful()) {
                    RealmManager.getAssetsDao().updateWalletName(wallet.getValue().getId(), newName);
                    wallet.setValue(RealmManager.getAssetsDao().getWalletById(wallet.getValue().getId()));
                    isWalletUpdated.postValue(true);
                } else {
                    if (response.message() != null) {
                        errorMessage.setValue(response.message());
                    }
                    isWalletUpdated.setValue(false);
                }
                isLoading.setValue(false);
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                t.printStackTrace();
                isWalletUpdated.setValue(false);
                errorMessage.setValue(t.getMessage());
            }
        });
        return isWalletUpdated;
    }

    public MutableLiveData<Boolean> removeWallet() {
        isLoading.setValue(true);
        MultyApi.INSTANCE.removeWallet(wallet.getValue().getCurrencyId(), wallet.getValue().getNetworkId(),
                wallet.getValue().getIndex()).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                if (response.isSuccessful()) {
                    RealmManager.getAssetsDao().removeWallet(wallet.getValue().getId());
                    isRemoved.setValue(true);
                } else {
                    isRemoved.setValue(false);
                }
                isLoading.setValue(false);
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable throwable) {
                throwable.printStackTrace();
                isLoading.setValue(false);
                errorMessage.setValue(throwable.getMessage());
            }
        });
        return isRemoved;
    }

    public int getChainId() {
        if (getWalletLive().getValue() != null && getWalletLive().getValue().isValid()) {
            return getWalletLive().getValue().getCurrencyId();
        }
        return 0;
    }

    public void shareAddress(Activity activity) {
        share(activity, getWalletLive().getValue().getActiveAddress().getAddress());
    }

    public void share(Activity activity, String stringToShare) {
        Intent sharingIntent = new Intent(ACTION_SEND);
        sharingIntent.setType("text/plain");
        sharingIntent.putExtra(android.content.Intent.EXTRA_TEXT, stringToShare);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
            Intent intentReceiver = new Intent(activity, AssetInfoFragment.SharingBroadcastReceiver.class);
            intentReceiver.putExtra(activity.getString(R.string.chain_id), getChainId());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(activity, 0, intentReceiver, PendingIntent.FLAG_CANCEL_CURRENT);
            activity.startActivity(Intent.createChooser(sharingIntent, activity.getResources().getString(R.string.share), pendingIntent.getIntentSender()));
        } else {
            activity.startActivity(Intent.createChooser(sharingIntent, activity.getResources().getString(R.string.share)));
        }
    }

    public void copyToClipboardAddress(Activity activity) {
        copyToClipboard(activity, getWalletLive().getValue().getActiveAddress().getAddress());
    }

    public void copyToClipboard(Activity activity, String stringToShare) {
        Analytics.getInstance(activity).logWallet(AnalyticsConstants.WALLET_ADDRESS, getChainId());
        String address = stringToShare;
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(address, address);
        assert clipboard != null;
        clipboard.setPrimaryClip(clip);
        Toast.makeText(activity, R.string.address_copied, Toast.LENGTH_SHORT).show();
    }

    //todo review this
    public void createFirstWallets(Runnable onComplete) {
        Disposable disposable = Flowable.create((FlowableOnSubscribe<Boolean>) emitter -> {
            boolean result = false;
            if (!Prefs.getBoolean(Constants.PREF_APP_INITIALIZED)) {
                if (!Multy.makeInitialized()) {
                    errorMessage.postValue(Multy.getContext().getString(R.string.error_initializing_wallet));
                    emitter.onNext(false);
                    return;
                }
            }
            Realm realm = RealmManager.open();
            FirstLaunchHelper.setCredentials(null);
            for (NativeDataHelper.Blockchain blockchain : NativeDataHelper.Blockchain.values()) {
                if (blockchain.getValue() == NativeDataHelper.Blockchain.ETH.getValue()) {
                    continue; //todo remove when eth mainnet will work
                }
                Wallet createdWallet = createWallet(String.format(Multy.getContext().getString(R.string.my_first_wallet_name), blockchain.name()),
                        blockchain.getValue(), NativeDataHelper.NetworkId.MAIN_NET.getValue());
                Response<ResponseBody> response = MultyApi.INSTANCE.addWallet(Multy.getContext(), createdWallet).execute();
                if (!result && response.isSuccessful()) {
                    result = true;
                }
            }
            realm.close();
            emitter.onNext(result);
        }, BackpressureStrategy.LATEST).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(isSuccess -> {
                    if (isSuccess != null && isSuccess) {
                        RealmManager.open();
                        saveDonateAddresses();
                    }
                    onComplete.run();
                }, throwable -> {
                    throwable.printStackTrace();
                    errorMessage.postValue(Multy.getContext().getString(R.string.something_went_wrong));
                    onComplete.run();
                });
        addDisposable(disposable);
    }
}
