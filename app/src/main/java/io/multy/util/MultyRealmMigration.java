/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.util;

import com.samwolfand.oneprefs.Prefs;

import io.multy.api.socket.CurrenciesRate;
import io.multy.model.entities.wallet.EthWallet;
import io.multy.model.entities.wallet.RecentAddress;
import io.multy.model.entities.wallet.Wallet;
import io.multy.model.entities.wallet.WalletAddress;
import io.multy.storage.RealmManager;
import io.realm.DynamicRealm;
import io.realm.DynamicRealmObject;
import io.realm.FieldAttribute;
import io.realm.RealmObjectSchema;
import io.realm.RealmSchema;

public class MultyRealmMigration implements io.realm.RealmMigration {

    @Override
    public void migrate(DynamicRealm realm, long oldVersion, long newVersion) {
        final RealmSchema schema = realm.getSchema();

        switch ((int) oldVersion) {
            case 1: {
                final RealmObjectSchema recentAddressSchema = schema.get(RecentAddress.class.getSimpleName());
                recentAddressSchema.addField(RecentAddress.RECENT_ADDRESS_ID, long.class);
                recentAddressSchema.addPrimaryKey(RecentAddress.RECENT_ADDRESS_ID);

                final RealmObjectSchema ethWalletSchema = schema.get(EthWallet.class.getSimpleName());
                ethWalletSchema.addField(EthWallet.PENDING_BALANCE, String.class);
            }
            case 2: {
                final RealmObjectSchema addressSchema = schema.get(WalletAddress.class.getSimpleName());
                addressSchema.removeField("amount");
                addressSchema.addField("amount", String.class);
            }
            case 3: {
                final RealmObjectSchema walletSchema = schema.get(Wallet.class.getSimpleName());
                walletSchema.addField("syncing", boolean.class);
            }
            case 4:
                RealmObjectSchema contactAddressSchema = schema.create("ContactAddress");
                contactAddressSchema.addField("address", String.class, FieldAttribute.INDEXED);
                contactAddressSchema.addPrimaryKey("address");
                contactAddressSchema.addField("contactId", long.class);
                contactAddressSchema.addField("currencyId", int.class);
                contactAddressSchema.addField("networkId", int.class);
                contactAddressSchema.addField("addressCurrencyImgId", int.class);

                RealmObjectSchema contactSchema = schema.create("Contact");
                contactSchema.addField("id", long.class, FieldAttribute.INDEXED);
                contactSchema.addPrimaryKey("id");
                contactSchema.addField("name", String.class);
                contactSchema.addField("parentId", long.class);
                contactSchema.addField("photoUri", String.class);
                contactSchema.addRealmListField("addresses", contactAddressSchema);
            case 5:
                RealmObjectSchema pendingWalletSchema = schema.get(Wallet.class.getSimpleName());
                pendingWalletSchema.addField("in", int.class);
                pendingWalletSchema.addField("out", int.class);
            case 6: {
                RealmObjectSchema currenciesRateSchema = schema.get(CurrenciesRate.class.getSimpleName());
                currenciesRateSchema.addField("eosToUsd", double.class);
                currenciesRateSchema.addField("eosToEur", double.class);
                RealmObjectSchema addressesSchema = schema.get(WalletAddress.class.getSimpleName());
                RealmObjectSchema eosWalletSchema = schema.create("EosWallet");
                eosWalletSchema.addField("pendingBalance", String.class);
                eosWalletSchema.addField("balance", String.class);
                eosWalletSchema.addField("publicKey", String.class);
                eosWalletSchema.addField("privateKey", String.class);
                eosWalletSchema.addRealmListField("addresses", addressesSchema);
                RealmObjectSchema multisigFactorySchema = schema.create("MultisigFactory");
                multisigFactorySchema.addField("id", int.class);
                multisigFactorySchema.addField("ethMainNet", String.class);
                multisigFactorySchema.addField("ethTestNet", String.class);
                multisigFactorySchema.addPrimaryKey("id");
                RealmObjectSchema ownerSchema = schema.create("Owner");
                ownerSchema.addField("userId", String.class);
                ownerSchema.addField("address", String.class);
                ownerSchema.addField("associated", boolean.class);
                ownerSchema.addField("creator", boolean.class);
                ownerSchema.addField("walletIndex", int.class);
                ownerSchema.addField("addressIndex", int.class);
                RealmObjectSchema multisigWalletSchema = schema.create("MultisigWallet");
                multisigWalletSchema.addRealmListField("owners", ownerSchema);
                multisigWalletSchema.addField("confirmations", int.class);
                multisigWalletSchema.addField("inviteCode", String.class);
                multisigWalletSchema.addField("ownersCount", int.class);
                multisigWalletSchema.addField("deployStatus", int.class);
                multisigWalletSchema.addField("nonce", String.class);
                multisigWalletSchema.addField("balance", String.class);
                multisigWalletSchema.addField("pendingBalance", String.class);
                multisigWalletSchema.addField("havePaymentRequests", boolean.class);
                RealmObjectSchema walletSchema = schema.get(Wallet.class.getSimpleName());
                walletSchema.addRealmObjectField("eosWallet", eosWalletSchema);
                walletSchema.addRealmObjectField("multisigWallet", multisigWalletSchema);
            }
            case 7: {
                RealmObjectSchema walletPrivateKeySchema = schema.create("WalletPrivateKey");
                walletPrivateKeySchema.addField("id", String.class);
                walletPrivateKeySchema.addPrimaryKey("id");
                walletPrivateKeySchema.addField("walletAddress", String.class);
                walletPrivateKeySchema.addField("privateKey", String.class);
                walletPrivateKeySchema.addField("currencyId", int.class);
                walletPrivateKeySchema.addField("networkId", int.class);
                realm.where(WalletAddress.class.getSimpleName()).findAll().deleteAllFromRealm();
                RealmObjectSchema addressSchema = schema.get("WalletAddress");
                addressSchema.addField("id", String.class);
                addressSchema.addPrimaryKey("id");
                RealmObjectSchema walletSchema = schema.get(Wallet.class.getSimpleName());
                walletSchema.addField("visible", boolean.class);
                walletSchema.addField("brokenStatus", int.class);
                Prefs.putBoolean(Constants.PREF_DETECT_BROKEN, true);
            }
        }
    }

    @Override
    public int hashCode() {
        return 37;
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof MultyRealmMigration);
    }

}
