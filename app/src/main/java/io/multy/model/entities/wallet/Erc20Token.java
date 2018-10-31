/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.model.entities.wallet;

import com.google.gson.annotations.SerializedName;

import io.realm.RealmObject;

public class Erc20Token extends RealmObject {
    @SerializedName("ContractAddress")
    private String contractAddress;
    @SerializedName("Ticker")
    private String ticker;
    @SerializedName("Name")
    private String name;

    public String getContractAddress() {
        return contractAddress;
    }

    public void setContractAddress(String contractAddress) {
        this.contractAddress = contractAddress;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
