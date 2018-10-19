/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.model.entities.wallet;


import com.google.gson.annotations.SerializedName;

import io.multy.model.entities.Output;
import io.realm.RealmList;
import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;
import io.realm.annotations.Required;

public class WalletAddress extends RealmObject {

    @PrimaryKey
    private String id;
    @SerializedName("addressindex")
    private int index;
    @SerializedName("address")
    private String address;
    @SerializedName("amount")
    private String amount = "0";
    @SerializedName("spendableoutputs")
    private RealmList<Output> outputs;
    @SerializedName("lastactiontime")
    private long date;

    public WalletAddress() {
    }

    public WalletAddress(int index, String address) {
        this.index = index;
        this.address = address;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public long getAmount() {
        return Long.valueOf(amount);
    }

    public String getAmountString() {
        return amount;
    }

    public RealmList<Output> getOutputs() {
        return outputs;
    }

    public void setOutputs(RealmList<Output> outputs) {
        this.outputs = outputs;
    }

    public long getDate() {
        return date;
    }

    public void buildId(int currencyId, int networkId) {
        id = address == null ? "" : address + String.valueOf(currencyId) + String.valueOf(networkId);
    }

    public static String getAddressId(String address, int currencyId, int networkId) {return address + String.valueOf(currencyId) + String.valueOf(networkId);
    }
}
