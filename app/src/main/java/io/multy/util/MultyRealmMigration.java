/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.util;

import io.multy.model.entities.wallet.EthWallet;
import io.multy.model.entities.wallet.RecentAddress;
import io.multy.model.entities.wallet.Wallet;
import io.multy.model.entities.wallet.WalletAddress;
import io.realm.DynamicRealm;
import io.realm.DynamicRealmObject;
import io.realm.RealmObjectSchema;
import io.realm.RealmSchema;

public class MultyRealmMigration implements io.realm.RealmMigration {

    @Override
    public void migrate(DynamicRealm realm, long oldVersion, long newVersion) {
        final RealmSchema schema = realm.getSchema();
        if (oldVersion == 1) {
            final RealmObjectSchema recentAddressSchema = schema.get(RecentAddress.class.getSimpleName());
            recentAddressSchema.addField(RecentAddress.RECENT_ADDRESS_ID, long.class);
            recentAddressSchema.addPrimaryKey(RecentAddress.RECENT_ADDRESS_ID);

            final RealmObjectSchema ethWalletSchema = schema.get(EthWallet.class.getSimpleName());
            ethWalletSchema.addField(EthWallet.PENDING_BALANCE, String.class);
        } else if (oldVersion == 2) {
            final RealmObjectSchema addressSchema = schema.get(WalletAddress.class.getSimpleName());
            addressSchema.removeField("amount");
            addressSchema.addField("amount", String.class);
        } else if (oldVersion == 3) {
            final RealmObjectSchema walletSchema = schema.get(Wallet.class.getSimpleName());
            walletSchema.addField("syncing", Boolean.class);
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
