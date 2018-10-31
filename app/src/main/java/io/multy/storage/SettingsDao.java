/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.storage;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import io.multy.api.socket.CurrenciesRate;
import io.multy.model.entities.ByteSeed;
import io.multy.model.entities.Contact;
import io.multy.model.entities.ContactAddress;
import io.multy.model.entities.DeviceId;
import io.multy.model.entities.DonateFeatureEntity;
import io.multy.model.entities.Mnemonic;
import io.multy.model.entities.MultisigFactory;
import io.multy.model.entities.RootKey;
import io.multy.model.entities.Token;
import io.multy.model.entities.UserId;
import io.multy.model.entities.wallet.Erc20Token;
import io.multy.model.responses.ServerConfigResponse;
import io.multy.util.NativeDataHelper;
import io.reactivex.annotations.NonNull;
import io.reactivex.annotations.Nullable;
import io.realm.Realm;
import io.realm.RealmList;
import io.realm.RealmResults;

public class SettingsDao {

    private Realm realm;

    public SettingsDao(@NonNull Realm realm) {
        this.realm = realm;
    }

    public void saveRootKey(RootKey key) {
        realm.executeTransaction(realm -> realm.insertOrUpdate(key));
    }

    public RootKey getRootKey() {
        return realm.where(RootKey.class).findFirst();
    }

    public void saveToken(Token token) {
        realm.executeTransaction(realm -> realm.insertOrUpdate(token));
    }

    public Token getToken() {
        return realm.where(Token.class).findFirst();
    }

    public void saveMultisigFactory(MultisigFactory multisigFactory) {
        if (multisigFactory != null) {
            realm.executeTransaction(realm -> realm.insertOrUpdate(multisigFactory));
        }
    }

    public MultisigFactory getMultisigFactory() {
        return realm != null ? realm.where(MultisigFactory.class).findFirst() : null;
    }

    public void saveUserId(UserId userId) {
        realm.executeTransaction(realm -> realm.insertOrUpdate(userId));
    }

    public UserId getUserId() {
        return realm != null ? realm.where(UserId.class).findFirst() : null;
    }

    public void saveSeed(ByteSeed seed) {
        realm.executeTransaction(realm -> realm.insertOrUpdate(seed));
    }

    public ByteSeed getSeed() {
        return realm.where(ByteSeed.class).findFirst();
    }

    public void setMnemonic(Mnemonic mnemonic) {
        realm.executeTransaction(realm -> realm.insertOrUpdate(mnemonic));
    }

    public Mnemonic getMnemonic() {
        return realm.where(Mnemonic.class).findFirst();
    }

    public void setDeviceId(DeviceId deviceId) {
        realm.executeTransaction(realm -> realm.insertOrUpdate(deviceId));
    }

    public DeviceId getDeviceId() {
        return realm.where(DeviceId.class).findFirst();
    }

    public void setUserId(UserId userId) {
        realm.executeTransaction(realm -> realm.insertOrUpdate(userId));
    }

    public void setByteSeed(ByteSeed byteSeed) {
        realm.executeTransaction(realm -> realm.insertOrUpdate(byteSeed));
    }

    public ByteSeed getByteSeed() {
        return realm.where(ByteSeed.class).findFirst();
    }

    public void saveCurrenciesRate(CurrenciesRate currenciesRate, Realm.Transaction.OnSuccess onSuccess) {
        realm.executeTransactionAsync(realm -> realm.insertOrUpdate(currenciesRate), onSuccess);
    }

    @Nullable
    public CurrenciesRate getCurrenciesRate() {
        CurrenciesRate currenciesRate = realm.where(CurrenciesRate.class).findFirst();
        if (currenciesRate == null) {
            currenciesRate = new CurrenciesRate();
        }
        return currenciesRate;
    }

    @Nullable
    public double getCurrenciesRateById(int currencyId) {
        CurrenciesRate currenciesRate = realm.where(CurrenciesRate.class).findFirst();
        if (currenciesRate == null) {
            return 0;
        }
        switch (NativeDataHelper.Blockchain.valueOf(currencyId)) {
            case BTC:
                return currenciesRate.getBtcToUsd();
            case ETH:
                return currenciesRate.getEthToUsd();
            default:
                return 0;
        }
    }

    public void saveDonation(List<ServerConfigResponse.Donate> donates) {
        realm.executeTransactionAsync(realm -> {
            for (ServerConfigResponse.Donate donate : donates) {
                DonateFeatureEntity donateFeature = new DonateFeatureEntity(donate.getFeatureCode());
                donateFeature.setDonationAddress(donate.getDonationAddress());
                realm.insertOrUpdate(donateFeature);
            }
        }, Throwable::printStackTrace);
    }

    public RealmResults<DonateFeatureEntity> getDonationAddresses() {
        return realm.where(DonateFeatureEntity.class).findAll();
    }

    public String getDonationAddress(int donationCode) {
        DonateFeatureEntity donateFeature = realm.where(DonateFeatureEntity.class)
                .equalTo(DonateFeatureEntity.FEATURE_CODE, donationCode).findFirst();
        return donateFeature == null ? null : donateFeature.getDonationAddress();
    }

    public DonateFeatureEntity getDonationFeature(String address) {
        return realm.where(DonateFeatureEntity.class)
                .equalTo(DonateFeatureEntity.DONATION_ADDRESS, address).findFirst();
    }

    public void saveErc20Tokens(ArrayList<Erc20Token> tokens) {
        realm.executeTransactionAsync(realm -> {
            realm.where(Erc20Token.class).findAll().deleteAllFromRealm();
            realm.insertOrUpdate(tokens);
        });
    }

    @Nullable
    public Erc20Token getErc20TokenInfo(String contractAddress) {
        return realm.where(Erc20Token.class).equalTo("contractAddress", contractAddress).findFirst();
    }

    public void saveContact(Contact contact) {
        realm.executeTransaction(realm -> realm.insertOrUpdate(contact));
    }

    public RealmResults<Contact> getContacts() {
        return realm.where(Contact.class).findAll();
    }

    public Contact getContact(long contactId) {
        return realm.where(Contact.class).equalTo(Contact.ID, contactId).findFirst();
    }

    public boolean isAddressInContact(String address) {
        return realm.where(ContactAddress.class).equalTo(ContactAddress.ADDRESS, address).findFirst() != null;
    }

    public @Nullable
    Contact getContactOrNull(String address) {
        ContactAddress contactAddress = realm.where(ContactAddress.class).equalTo(ContactAddress.ADDRESS, address).findFirst();
        if (contactAddress == null) {
            return null;
        } else {
            return realm.where(Contact.class).equalTo(Contact.ID, contactAddress.getContactId()).findFirst();
        }
    }

    public @Nullable
    String getContactNameOrNull(String address) {
        ContactAddress contactAddress = realm.where(ContactAddress.class).equalTo(ContactAddress.ADDRESS, address).findFirst();
        if (contactAddress == null) {
            return null;
        }
        Contact contact = realm.where(Contact.class).equalTo(Contact.ID, contactAddress.getContactId()).findFirst();
        if (contact == null) {
            Log.e(getClass().getSimpleName(), "Mistake in contact DB! Address without contact!");
            return null;
        }
        return contact.getName();
    }

    public void renameContact(Contact contact, String name) {
        realm.executeTransaction(realm -> {
            contact.setName(name);
        });
    }

    public void updateContactPhoto(Contact contact, String photoUri) {
        realm.executeTransaction(realm -> {
            contact.setPhotoUri(photoUri);
        });
    }

    public void removeContact(long contactId, Realm.Transaction.OnSuccess onSuccess) {
        realm.executeTransactionAsync(realm -> {
            realm.where(ContactAddress.class).equalTo(ContactAddress.CONTACT_ID, contactId).findAll().deleteAllFromRealm();
            realm.where(Contact.class).equalTo(Contact.ID, contactId).findAll().deleteAllFromRealm();
        }, onSuccess, Throwable::printStackTrace);
    }

    public void removeAllContacts(Realm.Transaction.OnSuccess onSuccess) {
        realm.executeTransactionAsync(realm -> {
            realm.where(ContactAddress.class).findAll().deleteAllFromRealm();
            realm.where(Contact.class).findAll().deleteAllFromRealm();
        }, onSuccess);
    }

    public void saveAddressesToContact(long multyRowId, String address, int currencyId, int networkId,
                                       int currencyImgId, Realm.Transaction.OnSuccess onSuccess) {
        realm.executeTransactionAsync(realm -> {
            Contact contact = realm.where(Contact.class).equalTo(Contact.ID, multyRowId).findFirst();
            if (contact == null) {
                throw new NullPointerException("There is no contact with selected ID!");
            } else {
                RealmList<ContactAddress> addresses = contact.getAddresses();
                ContactAddress selectedAddress = new ContactAddress(address, multyRowId, currencyId, networkId, currencyImgId);
                addresses.add(realm.copyToRealmOrUpdate(selectedAddress));
                contact.setAddresses(addresses);
                realm.insertOrUpdate(contact);
            }
        }, onSuccess);
    }

    public RealmResults<ContactAddress> getContactsAddresses() {
        return realm.where(ContactAddress.class).findAll();
    }

    public void removeAddressFromContact(String address, Realm.Transaction.OnSuccess onSuccess) {
        realm.executeTransactionAsync(realm ->
                        realm.where(ContactAddress.class).equalTo(ContactAddress.ADDRESS, address).findAll().deleteAllFromRealm(),
                onSuccess, Throwable::printStackTrace);
    }
}
