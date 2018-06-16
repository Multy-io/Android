/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.viewmodels;

import android.arch.lifecycle.MutableLiveData;
import android.os.Handler;

import io.multy.Multy;
import io.multy.R;
import io.multy.api.MultyApi;
import io.multy.api.socket.CurrenciesRate;
import io.multy.model.entities.Fee;
import io.multy.model.entities.wallet.EthWallet;
import io.multy.model.entities.wallet.Wallet;
import io.multy.model.responses.FeeRateResponse;
import io.multy.storage.RealmManager;
import io.multy.util.Constants;
import io.multy.util.CryptoFormatUtils;
import io.multy.util.JniException;
import io.multy.util.NativeDataHelper;
import io.multy.util.SingleLiveEvent;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class AssetSendViewModel extends BaseViewModel {

    public MutableLiveData<Wallet> wallet = new MutableLiveData<>();
    public MutableLiveData<FeeRateResponse.Speeds> speeds = new MutableLiveData<>();
    public MutableLiveData<Fee> fee = new MutableLiveData<>();
    private MutableLiveData<String> receiverAddress = new MutableLiveData<>();
    public MutableLiveData<String> thoseAddress = new MutableLiveData<>();
    public static MutableLiveData<Long> transactionPrice = new MutableLiveData<>();
    public SingleLiveEvent<String> transaction = new SingleLiveEvent<>();
    private double amount;
    private boolean isPayForCommission = true;
    private String donationAmount = "0";
    private boolean isAmountScanned = false;
    private CurrenciesRate currenciesRate;

    private String changeAddress;
    private String donationAddress;
    private byte[] seed;
    private String signTransactionError;
    private Handler handler = new Handler();

    public AssetSendViewModel() {
        currenciesRate = RealmManager.getSettingsDao().getCurrenciesRate();
    }

    public CurrenciesRate getCurrenciesRate() {
        if (currenciesRate == null) {
            currenciesRate = new CurrenciesRate();
            currenciesRate.setBtcToUsd(0);
        }
        return currenciesRate;
    }

    public void setWallet(Wallet wallet) {
        this.wallet.setValue(wallet);
    }

    public Wallet getWallet() {
        return this.wallet.getValue();
    }

    public void setFee(Fee fee) {
        this.fee.setValue(fee);
    }

    public Fee getFee() {
        return fee.getValue();
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public double getAmount() {
        return amount;
    }

    public MutableLiveData<String> getReceiverAddress() {
        return receiverAddress;
    }

    public void setReceiverAddress(String receiverAddress) {
        this.receiverAddress.setValue(receiverAddress);
    }

    public String getDonationAmount() {
        return donationAmount;
    }

    public String getDonationSatoshi() {
        if (getDonationAmount() != null) {
            return String.valueOf((long) (Double.valueOf(getDonationAmount()) * Math.pow(10, 8)));
        } else {
            return "0";
        }
    }

    public void setDonationAmount(String donationAmount) {
        this.donationAmount = donationAmount;
    }

    public boolean isPayForCommission() {
        return isPayForCommission;
    }

    public void setPayForCommission(boolean payForCommission) {
        isPayForCommission = payForCommission;
    }

    public boolean isAmountScanned() {
        return isAmountScanned;
    }

    public void setAmountScanned(boolean amountScanned) {
        isAmountScanned = amountScanned;
    }

    public void requestFeeRates(int currencyId, int networkId) {
        isLoading.postValue(true);
        MultyApi.INSTANCE.getFeeRates(currencyId, networkId).enqueue(new Callback<FeeRateResponse>() {
            @Override
            public void onResponse(Call<FeeRateResponse> call, Response<FeeRateResponse> response) {
                isLoading.postValue(false);
                if (response.isSuccessful()) {
                    speeds.postValue(response.body().getSpeeds());
                } else {
                    errorMessage.postValue(Multy.getContext().getString(R.string.error_loading_rates));
                }
            }

            @Override
            public void onFailure(Call<FeeRateResponse> call, Throwable t) {
                isLoading.postValue(false);
                errorMessage.postValue(t.getMessage());
                t.printStackTrace();
            }
        });
    }

    public void scheduleUpdateTransactionPrice(long amount) {
        final int walletIndex = getWallet().getIndex();
        final long feePerByte = getFee().getAmount();

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        if (seed == null) {
            seed = RealmManager.getSettingsDao().getSeed().getSeed();
        }

        if (changeAddress == null) {
            try {
                changeAddress = NativeDataHelper.makeAccountAddress(seed, walletIndex, getWallet().getBtcWallet().getAddresses().size(),
                        getWallet().getCurrencyId(), getWallet().getNetworkId());
            } catch (JniException e) {
                e.printStackTrace();
                errorMessage.setValue("Error creating change address " + e.getMessage());
            }
        }

        if (getWallet().getNetworkId() == NativeDataHelper.NetworkId.MAIN_NET.getValue()) {
            donationAddress = RealmManager.getSettingsDao().getDonationAddress(Constants.DONATE_WITH_TRANSACTION);
        } else {
            donationAddress = Constants.DONATION_ADDRESS_TESTNET;
        }

        if (donationAddress == null) {
            donationAddress = "";
        }

        handler.postDelayed(() -> {
            try {
//                Log.i("wise", getWallet().getId() + " " + getWallet().getNetworkId() + " " + amount + " " + getFee().getAmount() + " " + getDonationSatoshi() + " " + isPayForCommission);
                //important notice - native makeTransaction() method will update UI automatically with correct transaction price
                byte[] transactionHex = NativeDataHelper.makeTransaction(getWallet().getId(), getWallet().getNetworkId(),
                        seed, walletIndex, String.valueOf(amount),
                        String.valueOf(getFee().getAmount()), getDonationSatoshi(),
                        getReceiverAddress().getValue(), changeAddress, donationAddress, isPayForCommission);
            } catch (JniException e) {
                e.printStackTrace();
                signTransactionError = e.getMessage();
            }
        }, 300);
    }

    public void signTransaction() {
        try {
//            Log.i("wise", getWallet().getId() + " " + getWallet().getNetworkId() + " " + amount + " " + getFee().getAmount() + " " + getDonationSatoshi() + " " + isPayForCommission);
            byte[] transactionHex = NativeDataHelper.makeTransaction(getWallet().getId(), getWallet().getNetworkId(),
                    seed, getWallet().getIndex(), String.valueOf(CryptoFormatUtils.btcToSatoshi(String.valueOf(String.valueOf(amount)))),
                    String.valueOf(getFee().getAmount()), getDonationSatoshi(),
                    getReceiverAddress().getValue(), changeAddress, donationAddress, isPayForCommission);
            transaction.setValue(byteArrayToHex(transactionHex));
        } catch (JniException e) {
            errorMessage.setValue("Entered sum is invalid.");
            e.printStackTrace();
        }
    }

    public void signTransactionEth() {
        try {
            String signAmount = CryptoFormatUtils.ethToWei(String.valueOf(isPayForCommission ?
                    amount : (amount - EthWallet.getTransactionPrice(getFee().getAmount()))));
//            Log.i("wise", getWallet().getId() + " " + getWallet().getNetworkId() + " " + amount + " " + getFee().getAmount() + " " + getDonationSatoshi() + " " + isPayForCommission);

            byte[] tx = NativeDataHelper.makeTransactionETH(RealmManager.getSettingsDao().getSeed().getSeed(), getWallet().getIndex(), 0, wallet.getValue().getCurrencyId(), wallet.getValue().getNetworkId(),
                    getWallet().getActiveAddress().getAmountString(), signAmount/*CryptoFormatUtils.ethToWei(String.valueOf(amount))*/, getReceiverAddress().getValue(), "21000", String.valueOf(fee.getValue().getAmount()), getWallet().getEthWallet().getNonce());
            transaction.setValue(byteArrayToHex(tx));
        } catch (JniException e) {
            errorMessage.setValue("Entered sum is invalid.");
            e.printStackTrace();
        }
    }

    /**
     * Do not touch this
     * this method will be called from JNI automatically while makeTransaction is processing
     *
     * @param amount transactionPrice.
     */
    public static void setTransactionPrice(String amount) {
        long changeAmountSatoshi = Long.valueOf(amount);
        transactionPrice.setValue(changeAmountSatoshi);
    }

    private String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte b : a)
            sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public int getChainId() {
        return 1;
    }
}
