/*
 * Copyright 2018 Idealnaya rabota LLC
 * Licensed under Multy.io license.
 * See LICENSE for details
 */

package io.multy.api;


import io.multy.model.entities.AuthEntity;
import io.multy.model.entities.TransactionRequestEntity;
import io.multy.model.entities.wallet.Wallet;
import io.multy.model.requests.AddWalletAddressRequest;
import io.multy.model.requests.CreateMultisigRequest;
import io.multy.model.requests.HdTransactionRequestEntity;
import io.multy.model.requests.UpdateWalletNameRequest;
import io.multy.model.responses.AddressBalanceResponse;
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
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

public interface ApiServiceInterface {

    @POST("auth")
    Call<AuthResponse> auth(@Body AuthEntity authEntity);

    @POST("api/v1/wallet")
    Call<ResponseBody> addWallet(@Body Wallet wallet);

    @POST("api/v1/wallet")
    Call<ResponseBody> addWallet(@Body CreateMultisigRequest request);

    @POST("api/v1/gettransactioninfo/{id}")
    Call<ResponseBody> getTransactionInfo(@Path("id") String transactionId);

    @GET("api/v1/transaction/feerate/{currencyId}/{networkId}")
    Call<FeeRateResponse> getFeeRates(@Path("currencyId") int currencyId, @Path("networkId") int networkId);

    @GET("api/v1/getwalletaddresses/{walletId}")
    Observable<UserAssetsResponse> getWalletAddresses(@Path("walletId") int walletId);

    @POST("/api/v1/transaction/send/{currencyId}")
    Call<ResponseBody> sendRawTransaction(@Body TransactionRequestEntity transactionRequestEntity, @Path("currencyId") int currencyId);

    @POST("/api/v1/transaction/send")
    Call<ResponseBody> sendHdTransaction(@Body HdTransactionRequestEntity transactionRequestEntity);

    @GET("/api/v1/address/balance/{currencyId}/{address}")
    Call<AddressBalanceResponse> getBalanceByAddress(@Path("currencyId") int currencyId, @Path("address") String address);

    @POST("api/v1/address")
    Call<ResponseBody> addWalletAddress(@Body AddWalletAddressRequest addWalletAddressRequest);

    @GET("api/v1/wallet/{walletIndex}/verbose/{currencyId}/{networkId}")
    Call<SingleWalletResponse> getWalletVerboseByIndex(@Path("walletIndex") int walletIndex, @Path("currencyId") int currencyId, @Path("networkId") int networkId);

    @POST("api/v1/wallet/name")
    Call<ResponseBody> updateWalletName(@Body UpdateWalletNameRequest updateWalletName);

    @DELETE("api/v1/wallet/{currencyId}/{networkId}/{walletIndex}")
    Call<ResponseBody> removeWallet(@Path("currencyId") int currencyId, @Path("networkId") int networkId, @Path("walletIndex") int walletIndex);

    @GET("api/v1/wallets/verbose")
    Call<WalletsResponse> getWalletsVerbose();

    @GET("api/v1/wallets/transactions/{currencyid}/{networkid}/{walletIndex}")
    Call<TransactionHistoryResponse> getTransactionHistory(@Path("currencyid") int currencyId, @Path("networkid") int networkId, @Path("walletIndex") int walletIndex);

    @GET("/server/config")
    Call<ServerConfigResponse> getServerConfig();

    @GET("api/v1/wallets/verbose")
    Call<TestWalletResponse> testWalletVerbose();

    @POST("api/v1/account/price")
    Call<ResponseBody> getAccountPrice(@Body Object body);
}
