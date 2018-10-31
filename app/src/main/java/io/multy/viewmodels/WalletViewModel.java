/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.viewmodels;

import android.app.Activity;
import android.app.PendingIntent;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.MutableLiveData;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.widget.Toast;

import com.google.gson.Gson;
import com.samwolfand.oneprefs.Prefs;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import io.multy.Multy;
import io.multy.R;
import io.multy.api.MultyApi;
import io.multy.api.socket.CurrenciesRate;
import io.multy.api.socket.SocketManager;
import io.multy.api.socket.TransactionUpdateEntity;
import io.multy.model.entities.Estimation;
import io.multy.model.entities.TransactionHistory;
import io.multy.model.entities.wallet.BtcWallet;
import io.multy.model.entities.wallet.EthWallet;
import io.multy.model.entities.wallet.MultisigEvent;
import io.multy.model.entities.wallet.Wallet;
import io.multy.model.entities.wallet.WalletAddress;
import io.multy.model.entities.wallet.WalletPrivateKey;
import io.multy.model.requests.HdTransactionRequestEntity;
import io.multy.model.requests.UpdateWalletNameRequest;
import io.multy.model.responses.FeeRateResponse;
import io.multy.model.responses.ServerConfigResponse;
import io.multy.model.responses.TransactionHistoryResponse;
import io.multy.model.responses.WalletsResponse;
import io.multy.storage.AssetsDao;
import io.multy.storage.RealmManager;
import io.multy.ui.fragments.asset.AssetInfoFragment;
import io.multy.ui.fragments.send.SendSummaryFragment;
import io.multy.ui.fragments.send.ethereum.EthSendSummaryFragment;
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
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import io.realm.Realm;
import io.realm.RealmList;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import timber.log.Timber;

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
        try {
            socketManager = new SocketManager();
            socketManager.listenRatesAndTransactions(rates, transactionUpdate);
            if (getWalletLive().getValue().isMultisig()) {
                String eventReceive = SocketManager.getEventReceive(RealmManager.getSettingsDao().getUserId().getUserId());
                socketManager.listenEvent(eventReceive, args -> {
                    transactionUpdate.postValue(new TransactionUpdateEntity());
                });
            }
            socketManager.connect();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
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
        actualizeWallet();
        return wallet;
    }

    public static void saveDonateAddresses() {
        ServerConfigResponse serverConfig = EventBus.getDefault().removeStickyEvent(ServerConfigResponse.class);
        if (serverConfig != null && serverConfig.getMultisigFactory() != null && serverConfig.getDonates() != null) {
            RealmManager.getSettingsDao().saveDonation(serverConfig.getDonates());
            RealmManager.getSettingsDao().saveErc20Tokens(serverConfig.getTokens());
            RealmManager.getSettingsDao().saveMultisigFactory(serverConfig.getMultisigFactory());
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
        } finally {
            RealmManager.close();
        }
        return walletRealmObject;
    }

    public MutableLiveData<ArrayList<TransactionHistory>> getTransactionsHistory(final int currencyId, final int networkId, final int walletIndex) {
        isLoading.postValue(true);
        MultyApi.INSTANCE.getTransactionHistory(currencyId, networkId, walletIndex).enqueue(new Callback<TransactionHistoryResponse>() {
            @Override
            public void onResponse(@NonNull Call<TransactionHistoryResponse> call, @NonNull Response<TransactionHistoryResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    transactions.setValue(response.body().getHistories());
                }
                isLoading.postValue(false);
            }

            @Override
            public void onFailure(Call<TransactionHistoryResponse> call, Throwable throwable) {
                throwable.printStackTrace();
                isLoading.setValue(false);
            }
        });
        return transactions;
    }

    public MutableLiveData<ArrayList<TransactionHistory>> getMultisigTransactionsHistory(int currencyId, int networkId, String address, int assetType) {
        isLoading.postValue(true);
        MultyApi.INSTANCE.getMultisigTransactionHistory(currencyId, networkId, address, assetType)
                .enqueue(new Callback<TransactionHistoryResponse>() {
            @Override
            public void onResponse(@NonNull Call<TransactionHistoryResponse> call, @NonNull Response<TransactionHistoryResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    transactions.setValue(response.body().getHistories());
                }
                isLoading.setValue(false);
            }

            @Override
            public void onFailure(@NonNull Call<TransactionHistoryResponse> call, @NonNull Throwable t) {
                t.printStackTrace();
                isLoading.setValue(false);
            }
        });
        return transactions;
    }

    public void updateWallets() {
        isLoading.setValue(true);
        MultyApi.INSTANCE.getWalletsVerbose().enqueue(new Callback<WalletsResponse>() {
            @Override
            public void onResponse(@NonNull Call<WalletsResponse> call, @NonNull Response<WalletsResponse> response) {
                isLoading.setValue(false);
                WalletsResponse body = response.body();
                if (response.isSuccessful() && body != null && body.getWallets() != null) {
                    RealmManager.getAssetsDao().saveWallets(body.getWallets());
                    actualizeWallet();
                }
            }

            @Override
            public void onFailure(@NonNull Call<WalletsResponse> call, @NonNull Throwable t) {
                isLoading.setValue(false);
                t.printStackTrace();
            }
        });
    }

    public MutableLiveData<Boolean> updateWalletSetting(String newName) {
        int index = wallet.getValue().getIndex();
        int currencyId = wallet.getValue().getCurrencyId();
        UpdateWalletNameRequest updateWalletName = new UpdateWalletNameRequest(newName, currencyId, index,
                wallet.getValue().getNetworkId(), wallet.getValue().getActiveAddress().getAddress(),
                index < 0 ? Constants.ASSET_TYPE_ADDRESS_IMPORTED : Constants.ASSET_TYPE_ADDRESS_MULTY);
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

    private boolean isLinkedWallet(Wallet wallet) {
        return RealmManager.getAssetsDao().isSelfOwnerAddress(wallet.getActiveAddress().getAddress());
    }

    public MutableLiveData<Boolean> removeWallet() {
        Wallet wallet = this.wallet.getValue();
        if (wallet == null || isLinkedWallet(wallet)) {
            isRemoved.setValue(false);
        } else {
            isLoading.setValue(true);
            MultyApi.INSTANCE.removeWallet(wallet.getCurrencyId(), wallet.getNetworkId(),
                    wallet.getIndex()).enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                    if (response.isSuccessful()) {
                        RealmManager.getAssetsDao().removeWallet(wallet.getId());
                        isRemoved.setValue(true);
                    } else {
                        isRemoved.setValue(false);
                    }
                    isLoading.setValue(false);
                }

                @Override
                public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable throwable) {
                    throwable.printStackTrace();
                    isLoading.setValue(false);
                    errorMessage.setValue(throwable.getMessage());
                }
            });
        }
        return isRemoved;
    }

    public int getChainId() {
        actualizeWallet();
        if (wallet.getValue() != null) {
            return wallet.getValue().getCurrencyId();
        }
        return 0;
    }

    private void actualizeWallet() {
        if ((wallet.getValue() == null || !wallet.getValue().isValid()) && walletId != -1) {
            wallet.setValue(getWallet(walletId));
        }
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
            Realm realm = Realm.getInstance(Multy.getRealmConfiguration());
            FirstLaunchHelper.setCredentials(null);
            for (NativeDataHelper.Blockchain blockchain : NativeDataHelper.Blockchain.values()) {
                //TODO Enable EOS wallet creating
                if (!blockchain.equals(NativeDataHelper.Blockchain.EOS)) {
                    Wallet createdWallet = createWallet(String.format(Multy.getContext().getString(R.string.my_first_wallet_name), blockchain.name()),
                            blockchain.getValue(), blockchain.getValue() == NativeDataHelper.Blockchain.ETH.getValue() ?
                                    NativeDataHelper.NetworkId.ETH_MAIN_NET.getValue() : NativeDataHelper.NetworkId.MAIN_NET.getValue());
                    Response<ResponseBody> response = MultyApi.INSTANCE.addWallet(Multy.getContext(), createdWallet).execute();
                    if (response.isSuccessful() && !result) {
                        result = true;
                    }
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
                    errorMessage.setValue(Multy.getContext().getString(R.string.something_went_wrong));
                    Prefs.putBoolean(Constants.PREF_APP_INITIALIZED, false);
                    onComplete.run();
                });
        addDisposable(disposable);
    }

    public void requestEstimation(String multisigWalletAddress, Consumer<String> onNext, Consumer<Throwable> onError) {
        isLoading.setValue(true);
        Disposable disposable = Observable.create((ObservableOnSubscribe<String>) e -> {
            Response<Estimation> response = MultyApi.INSTANCE.getEstimations(multisigWalletAddress).execute();
            e.onNext(response.body().getConfirmTransaction());
            e.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(t -> isLoading.postValue(false))
                .subscribe(onNext, onError, () -> isLoading.setValue(false));
        addDisposable(disposable);
    }

    public void requestFeeRates(int currencyId, int networkId, Consumer<String> onNext, Consumer<Throwable> onError) {
        isLoading.setValue(true);
        Disposable disposable = Observable.create((ObservableOnSubscribe<String>) e -> {
            Response<FeeRateResponse> response = MultyApi.INSTANCE.getFeeRates(currencyId, networkId).execute();
            e.onNext(String.valueOf(response.body().getSpeeds().getMedium()));
            e.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(t -> isLoading.postValue(false))
                .subscribe(onNext, onError, () -> isLoading.setValue(false));
        addDisposable(disposable);
    }

    public void sendConfirmTransaction(String walletAddress, String requestId, String estimationConfirm, String mediumGasPrice,
                                       Consumer<Boolean> onNext, Consumer<Throwable> onError) {
        Wallet linkedWallet = RealmManager.getAssetsDao().getMultisigLinkedWallet(getWalletLive().getValue().getMultisigWallet().getOwners());
        String price = String.valueOf(Long.parseLong(estimationConfirm) * Long.parseLong(mediumGasPrice));
        if (new BigInteger(linkedWallet.getAvailableBalance()).compareTo(new BigInteger(price)) < 0) {
            errorMessage.setValue(Multy.getContext().getString(R.string.not_enough_linked_balance));
            return;
        }
        isLoading.setValue(true);
        final int currencyId = linkedWallet.getCurrencyId();
        final int networkId = linkedWallet.getNetworkId();
        final int walletIndex = linkedWallet.shouldUseExternalKey() ? -1 : linkedWallet.getIndex();
        final String linkedAddress = linkedWallet.getActiveAddress().getAddress();
        final String amount =linkedWallet.getActiveAddress().getAmountString();
        final String nonce = linkedWallet.getEthWallet().getNonce();
        final byte[] seed = RealmManager.getSettingsDao().getSeed().getSeed();
        Disposable disposable = Observable.create((ObservableOnSubscribe<Boolean>) e -> {
            byte[] tx;
            if (walletIndex < 0) {
                Realm realm = Realm.getInstance(Multy.getRealmConfiguration());
                WalletPrivateKey privateKey = new AssetsDao(realm).getPrivateKey(linkedAddress, currencyId, networkId);
                tx = NativeDataHelper.confirmTransactionMultisigETHFromKey(
                        privateKey.getPrivateKey(),
                        currencyId,
                        networkId,
                        amount,
                        walletAddress,
                        requestId,
                        estimationConfirm,
                        mediumGasPrice,
                        nonce);
                realm.close();
            } else {
                tx = NativeDataHelper.confirmTransactionMultisigETH(
                        seed,
                        walletIndex,
                        0,
                        currencyId,
                        networkId,
                        amount,
                        walletAddress,
                        requestId,
                        estimationConfirm,
                        mediumGasPrice,
                        nonce);
            }
            Timber.i(getClass().getSimpleName(), SendSummaryFragment.byteArrayToHex(tx));
            Response<ResponseBody> response = MultyApi.INSTANCE.sendHdTransaction(new HdTransactionRequestEntity(currencyId, networkId,
                    new HdTransactionRequestEntity.Payload("", 0, walletIndex,
                            "0x" + EthSendSummaryFragment.byteArrayToHex(tx), false))).execute();
            if (!response.isSuccessful()) {
                String errorBody = response.errorBody() == null ? null : response.errorBody().string();
                if (!TextUtils.isEmpty(errorBody)) {
                    throw new IllegalStateException(errorBody);
                }
                if (response.code() == 406) {
                    Analytics.getInstance(Multy.getContext()).logEvent(getClass().getSimpleName(), "406", errorBody);
                }
            }
            e.onNext(response.isSuccessful());
            e.onComplete();
        }).doOnError(t -> isLoading.postValue(false))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(onNext, onError, () -> isLoading.setValue(false));
        addDisposable(disposable);
    }

    public void sendDeclineTransaction(int currencyId, int networkId, int walletIndex, String txId, Lifecycle lifecycle) {
        sendTransactionStatus(Constants.EVENT_TYPE_DECLINE_MULTISIG, currencyId, networkId, walletIndex, txId, lifecycle);
    }

    public void sendViewTransaction(int currencyId, int networkId, int walletIndex, String txId, Lifecycle lifecycle) {
        sendTransactionStatus(Constants.EVENT_TYPE_VIEW_MULTISIG, currencyId, networkId, walletIndex, txId, lifecycle);
    }

    public void sendTransactionStatus(int eventType, int currencyId, int networkId, int walletIndex, String txId, Lifecycle lifecycle) {
        try {
            isLoading.setValue(true);
            SocketManager socketManager = new SocketManager();
            socketManager.connect();
            MultisigEvent event = MultisigEvent.getBuilder()
                    .setType(eventType)
                    .setDate(System.currentTimeMillis())
                    .setPayload(MultisigEvent.Payload.getBuilder()
                            .setUserId(RealmManager.getSettingsDao().getUserId().getUserId())
                            .setAddress(RealmManager.getAssetsDao().getMultisigLinkedWallet(wallet.getValue()
                                    .getMultisigWallet().getOwners()).getActiveAddress().getAddress())
                            .setInviteCode(wallet.getValue().getMultisigWallet().getInviteCode())
                            .setWalletIndex(walletIndex)
                            .setCurrencyId(currencyId)
                            .setNetworkId(networkId)
                            .setTxId(txId)
                            .build())
                    .build();
            try {
                JSONObject eventJson = new JSONObject(new Gson().toJson(event));
                socketManager.sendMultisigTransactionOwnerAction(eventJson, args -> {
                    Timber.i("EVENT_MESSAGE_SEND" + args[0]);
                    isLoading.postValue(false);
                    socketManager.disconnect();
                });
            } catch (JSONException e) {
                e.printStackTrace();
                socketManager.disconnect();
                isLoading.setValue(false);
            }
            lifecycle.addObserver(new LifecycleObserver() {
                @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
                void onPause() {
                    socketManager.disconnect();
                }
            });
        } catch (URISyntaxException e) {
            e.printStackTrace();
            isLoading.setValue(false);
        }
    }

    public void resyncWallet(Runnable successCallback) {
        Wallet wallet = getWalletLive().getValue();
        if (wallet != null && wallet.isValid()) {
            isLoading.setValue(true);
            int assetType = wallet.isMultisig() ? Constants.ASSET_TYPE_ADDRESS_MULTISIG : wallet.getIndex() < 0 ?
                    Constants.ASSET_TYPE_ADDRESS_IMPORTED : Constants.ASSET_TYPE_ADDRESS_MULTY;
            MultyApi.INSTANCE.resyncWallet(wallet.getCurrencyId(), wallet.getNetworkId(), wallet.getIndex(), assetType)
                    .enqueue(new Callback<ResponseBody>() {
                        @Override
                        public void onResponse(@NonNull Call<ResponseBody> call, @NonNull Response<ResponseBody> response) {
                            isLoading.setValue(false);
                            if (response.isSuccessful()) {
                                successCallback.run();
                            } else {
                                errorMessage.setValue(Multy.getContext().getString(R.string.something_went_wrong));
                            }
                        }

                        @Override
                        public void onFailure(@NonNull Call<ResponseBody> call, @NonNull Throwable t) {
                            errorMessage.setValue(Multy.getContext().getString(R.string.something_went_wrong));
                            isLoading.setValue(false);
                        }
                    });
        } else {
            errorMessage.setValue("Wallet is need to refresh");
        }
    }
}
