/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.api;


import android.content.Context;

import io.multy.model.entities.wallet.Wallet;
import io.multy.model.requests.AddWalletAddressRequest;
import io.multy.model.requests.CreateMultisigRequest;
import io.multy.model.requests.HdTransactionRequestEntity;
import io.multy.model.requests.UpdateWalletNameRequest;
import io.multy.model.responses.AuthResponse;
import io.multy.model.responses.FeeRateResponse;
import io.multy.model.responses.ServerConfigResponse;
import io.multy.model.responses.SingleWalletResponse;
import io.multy.model.responses.TestWalletResponse;
import io.multy.model.responses.TransactionHistoryResponse;
import io.multy.model.responses.UserAssetsResponse;
import io.multy.model.responses.WalletsResponse;
import io.reactivex.Observable;
import okhttp3.ResponseBody;
import retrofit2.Call;

public interface MultyApiInterface {

    Call<AuthResponse> auth(String userId);

    Call<ResponseBody> addWallet(Context context, Wallet wallet);

    Call<ResponseBody> addWallet(Context context, CreateMultisigRequest request);

    void getTransactionInfo(String transactionId);

    Observable<UserAssetsResponse> getWalletAddresses(int walletId);

    Call<ResponseBody> sendRawTransaction(String transactionHex, int currencyId);

    Call<ResponseBody> addWalletAddress(AddWalletAddressRequest addWalletAddressRequest);

    Call<SingleWalletResponse> getWalletVerbose(int walletIndex, int currencyId, int networkId);

    Call<WalletsResponse> getWalletsVerbose();

    Call<ResponseBody> updateWalletName(UpdateWalletNameRequest updateWalletName);

    Call<ResponseBody> removeWallet(int currencyId, int networkId, int walletIndex);

    Call<TransactionHistoryResponse> getTransactionHistory(int currencyId, int networkId, int walletIndex);

    Call<ServerConfigResponse> getServerConfig();

    Call<FeeRateResponse> getFeeRates(int currencyId, int networkId);

    Call<ResponseBody> sendHdTransaction(HdTransactionRequestEntity transactionRequestEntity);

    Call<TestWalletResponse> testWalletVerbose();

    Call<ResponseBody> getAccountPrice(Object body);
}
