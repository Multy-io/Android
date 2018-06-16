/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.model.entities.wallet;


import android.support.annotation.NonNull;

import com.google.gson.annotations.SerializedName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import io.multy.R;
import io.multy.api.socket.CurrenciesRate;
import io.multy.storage.RealmManager;
import io.multy.util.CryptoFormatUtils;
import io.multy.util.NativeDataHelper;
import io.multy.util.NumberFormatter;
import io.realm.RealmList;
import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;

public class Wallet extends RealmObject implements WalletBalanceInterface {

    @SerializedName("currencyid")
    private int currencyId; //chain id
    @SerializedName("networkid")
    private int networkId; //id of network testnet, mainnet etc.
    @SerializedName("walletindex")
    private int index;
    @SerializedName("walletname")
    private String walletName;
    @SerializedName("lastactiontime")
    private long lastActionTime;
    @PrimaryKey
    @SerializedName("dateofcreation")
    private long dateOfCreation;
    @SerializedName("pending")
    private boolean pending;
    @SerializedName("address")
    private String creationAddress;
    @SerializedName("issyncing")
    private boolean syncing = false;

    private int fiatId; //id of chosen fiat currency for this walelt

    private String balance = "0"; //satoshi for btc, wei for eth
    private String availableBalance = "0";

    @Nullable
    private EthWallet ethWallet;
    @Nullable
    private BtcWallet btcWallet;

    private BigInteger convertBalance(BigInteger divisor) {
        BigInteger value = new BigInteger(balance);
        if (value.longValue() == 0) {
            return value;
        } else {
            return new BigInteger(balance).divide(divisor);
        }
    }

    public WalletAddress getActiveAddress() {
        RealmList<WalletAddress> addresses = new RealmList<>();
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                addresses = getBtcWallet().getAddresses();
                break;
            case ETH:
                addresses = getEthWallet().getAddresses();
                break;
        }

        return addresses.get(addresses.size() - 1);
    }

    public List<WalletAddress> getAddresses() {
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return getBtcWallet().getAddresses();
            case ETH:
                return getEthWallet().getAddresses();
        }

        return new ArrayList<>();
    }

    /**
     * @return String "10 BTC"
     */
    @Override
    public String getBalanceLabel() {
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return NumberFormatter.getInstance().format(getBtcDoubleValue()) + " BTC";
            case ETH:
                return NumberFormatter.getEthInstance().format(getEthValue()) + " ETH";
            default:
                return "unsupported";
        }
    }

    /**
     * @return String "10"
     */
    public String getBalanceLabelTrimmed() {
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return NumberFormatter.getInstance().format(getBtcDoubleValue());
            case ETH:
                return NumberFormatter.getInstance().format(getEthValue());
            default:
                return "unsupported";
        }
    }

    public String getAvailableBalanceLabel() {
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return NumberFormatter.getInstance().format(getBtcAvailableDoubleValue()) + " BTC";
            case ETH:
                return NumberFormatter.getEthInstance().format(getEthAvailableValue()) + " ETH";
            default:
                return "unsupported";
        }
    }

    @Override
    public String getFiatBalanceLabel() {
        try {
            return getFiatBalanceLabel(RealmManager.getSettingsDao().getCurrenciesRate());
        } catch (Throwable t) {
            t.printStackTrace();
            RealmManager.open(); //this slows main screen when get updates of currency rate by sockets if call everytime
            return getFiatBalanceLabel(RealmManager.getSettingsDao().getCurrenciesRate());
        }
    }

    public String getFiatBalanceLabel(CurrenciesRate currenciesRate) {
        //TODO support different fiat currencies here
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return String.valueOf(NumberFormatter.getFiatInstance().format(getBtcDoubleValue() * currenciesRate.getBtcToUsd()) + getFiatString()); //convert from satoshi
            case ETH:
                return String.valueOf(NumberFormatter.getFiatInstance().format(getEthValue().multiply(new BigDecimal(currenciesRate.getEthToUsd()))) + getFiatString()); //convert from wei
            default:
                return "unsupported";
        }
    }

    /**
     * Convert currency (BTC, ETH...) to fiat amount
     *
     * @param amount currency amount
     * @return
     */
    public String toFiatAmountLabel(BigDecimal amount) {
        RealmManager.open();
        CurrenciesRate currenciesRate = RealmManager.getSettingsDao().getCurrenciesRate();
        //TODO support different fiat currencies here
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return String.valueOf(NumberFormatter.getFiatInstance().format(amount.doubleValue() * currenciesRate.getBtcToUsd()) + getFiatString()); //convert from satoshi
            case ETH:
                return String.valueOf(NumberFormatter.getFiatInstance().format(amount.multiply(new BigDecimal(currenciesRate.getEthToUsd()))) + getFiatString()); //convert from wei
            default:
                return "unsupported";
        }
    }

    public String toCurrencyAmountLabel(String amount) {
        //TODO support different fiat currencies here
        double fiat;
        try {
            fiat = Double.parseDouble(amount);
        } catch (NumberFormatException e) {
            e.printStackTrace();
            return "";
        }

        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return CryptoFormatUtils.usdToBtc(fiat);
            case ETH:
                return CryptoFormatUtils.usdToEth(fiat);
            default:
                return "unsupported";
        }
    }

    /**
     * Convert currency coin (wei, satoshi...) to fiat amount
     *
     * @param amount currency amount
     * @return
     */
    public String coinToFiatAmountLabel(BigInteger amount) {
        //TODO support different fiat currencies here
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return CryptoFormatUtils.satoshiToUsd(amount.longValue());
            case ETH:
                return CryptoFormatUtils.weiToUsd(amount);
            default:
                return "unsupported";
        }
    }

    /**
     * Convert currency coin (wei, satoshi) to currency (ETH, BTC)
     *
     * @param amount
     * @return
     */
    public String coinToCurrencyAmountLabel(BigInteger amount) {
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return CryptoFormatUtils.satoshiToBtcLabel(amount.longValue());
            case ETH:
                return CryptoFormatUtils.weiToEthLabel(amount.toString());
            default:
                return "unsupported";
        }
    }

    @Override
    public int getIconResourceId() {
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return networkId == NativeDataHelper.NetworkId.MAIN_NET.getValue() ? R.drawable.ic_btc : R.drawable.ic_chain_btc_test;
            case ETH:
                return networkId == NativeDataHelper.NetworkId.MAIN_NET.getValue() ? R.drawable.ic_eth_medium_icon : R.drawable.ic_chain_eth_test;
            default:
                return 0;
        }
    }

    /**
     * @return fiat string balance without fiat symbol. example 2600 (mean usd)
     */
    public String getFiatBalanceLabelTrimmed() {
        return getFiatBalanceLabel().replace(getFiatString(), "");
    }

    public String getAvailableFiatBalanceLabel() {
        CurrenciesRate currenciesRate = RealmManager.getSettingsDao().getCurrenciesRate();
        //TODO support different fiat currencies here
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return String.valueOf(NumberFormatter.getFiatInstance().format(getBtcAvailableDoubleValue() * currenciesRate.getBtcToUsd()) + getFiatString()); //convert from satoshi
            case ETH:
                return String.valueOf(NumberFormatter.getFiatInstance().format(getEthAvailableValue().multiply(new BigDecimal(currenciesRate.getEthToUsd()))) + getFiatString()); //convert from gwei
            default:
                return "unsupported";
        }
    }

    @NonNull
    public String getCurrencyName() {
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return CurrencyCode.BTC.name();
            case ETH:
                return CurrencyCode.ETH.name();
        }
        return "";
    }

    public String getFiatString() {
        switch (fiatId) {
            default:
                return "$";
        }
    }

    public boolean isPayable() {
        if (isPending()) {
            return false;
        }

        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return getAvailableBalanceNumeric().longValue() > 150;
            case ETH:
                return getAvailableBalanceNumeric().longValue() > 0;
        }
        return false;
    }

    public BigDecimal getBalanceNumeric() {
        return balance == null ? new BigDecimal("0") : new BigDecimal(balance);
    }

    public BigInteger getPendingBalance() {
        return new BigInteger(balance).subtract(new BigInteger(availableBalance));
    }

    public BigDecimal getAvailableBalanceNumeric() {
        return new BigDecimal(availableBalance);
    }

    public double getBtcDoubleValue() {
        return balance.equals("0") ? 0 : (getBalanceNumeric().divide(BtcWallet.DIVISOR, 8, BigDecimal.ROUND_FLOOR)).doubleValue();
    }

    public BigDecimal getEthValue() {
        return balance.equals("0") ? new BigDecimal(0) : (getBalanceNumeric().divide(EthWallet.DIVISOR));
    }

    public double getBtcAvailableDoubleValue() {
        return availableBalance.equals("0") ? 0 : (getAvailableBalanceNumeric().divide(BtcWallet.DIVISOR)).doubleValue();
    }

    public BigDecimal getEthAvailableValue() {
        return availableBalance.equals("0") ? new BigDecimal(0) : (getAvailableBalanceNumeric().divide(EthWallet.DIVISOR));
    }

    public int getCurrencyId() {
        return currencyId;
    }

    public void setCurrencyId(int currencyId) {
        this.currencyId = currencyId;
    }

    public int getNetworkId() {
        return networkId;
    }

    public void setNetworkId(int networkId) {
        this.networkId = networkId;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getWalletName() {
        return walletName;
    }

    public void setWalletName(String walletName) {
        this.walletName = walletName;
    }

    public long getLastActionTime() {
        return lastActionTime;
    }

    public void setLastActionTime(long lastActionTime) {
        this.lastActionTime = lastActionTime;
    }

    public long getDateOfCreation() {
        return dateOfCreation;
    }

    public void setDateOfCreation(long dateOfCreation) {
        this.dateOfCreation = dateOfCreation;
    }

    public boolean isPending() {
        return pending;
    }

    public void setPending(boolean pending) {
        this.pending = pending;
    }

    public int getFiatId() {
        return fiatId;
    }

    public void setFiatId(int fiatId) {
        this.fiatId = fiatId;
    }

    public String getBalance() {
        return balance;
    }

    public void setBalance(String balance) {
        this.balance = balance;
    }

    public String getAvailableBalance() {
        return availableBalance;
    }

    public void setAvailableBalance(String availableBalance) {
        this.availableBalance = availableBalance;
    }

    public boolean isSyncing() {
        return syncing;
    }

    public void setSyncing(boolean syncing) {
        this.syncing = syncing;
    }

    @Nullable
    public EthWallet getEthWallet() {
        return ethWallet;
    }

    public void setEthWallet(@Nullable EthWallet ethWallet) {
        this.ethWallet = ethWallet;
    }

    @Nullable
    public BtcWallet getBtcWallet() {
        return btcWallet;
    }

    public void setBtcWallet(@Nullable BtcWallet btcWallet) {
        this.btcWallet = btcWallet;
    }

    public String getCreationAddress() {
        return creationAddress;
    }

    public void setCreationAddress(String creationAddress) {
        this.creationAddress = creationAddress;
    }

    public long getId() {
        return dateOfCreation;
    }

    public static String getAddressAmount(WalletAddress address, int currencyId) {
        String result = null;
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                result = CryptoFormatUtils.satoshiToBtcLabel(address.getAmount());
                break;
            case ETH:
                result = CryptoFormatUtils.weiToEthLabel(address.getAmountString());
                break;
        }
        return result;
    }
}
