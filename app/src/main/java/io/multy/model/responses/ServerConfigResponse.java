/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.model.responses;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

import io.multy.model.entities.MultisigFactory;
import io.multy.model.entities.wallet.Erc20Token;

public class ServerConfigResponse {

    @SerializedName("android")
    private AndroidConfig androidConfig;

    @SerializedName("donate")
    private List<Donate> donates;

    @SerializedName("multisigfactory")
    private MultisigFactory multisigFactory;

    @SerializedName("erc20tokenlist")
    private ArrayList<Erc20Token> tokens;

    public MultisigFactory getMultisigFactory() {
        return multisigFactory;
    }

    public AndroidConfig getAndroidConfig() {
        return androidConfig;
    }

    public List<Donate> getDonates() {
        return donates;
    }

    public ArrayList<Erc20Token> getTokens() {
        return tokens;
    }

    public class AndroidConfig {
        @SerializedName("hard")
        private int hardVersion;

        @SerializedName("soft")
        private int softVersion;

        @SerializedName("servertime")
        private long serverTime;

        public int getHardVersion() {
            return hardVersion;
        }

        public int getSoftVersion() {
            return softVersion;
        }

        public long getServerTime() {
            return serverTime;
        }
    }

    public class Donate {
        @SerializedName("DonationAddress")
        private String donationAddress;

        @SerializedName("FeatureCode")
        private int featureCode;

        public String getDonationAddress() {
            return donationAddress;
        }

        public int getFeatureCode() {
            return featureCode;
        }
    }
}
