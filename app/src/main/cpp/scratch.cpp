#include <jni.h>
#include <android/log.h>
#include <sys/types.h>
#include <cstdio>
#include <unistd.h>
#include <array>
#include <pthread.h>
#include <memory>
#include <multy_test/run_tests.h>
#include <multy_core/common.h>
#include <multy_core/error.h>
#include <multy_core/mnemonic.h>
#include <vector>
#include <multy_core/account.h>
#include <cstring>
#include <functional>
#include <multy_core/src/utility.h>
#include "multy_core/key.h"
#include <multy_core/transaction.h>
#include <multy_core/src/api/properties_impl.h>
#include <multy_core/src/api/big_int_impl.h>
#include <multy_core/big_int.h>
#include <multy_core/src/ethereum/ethereum_transaction.h>
#include "multy_core/sha3.h"
#include "multy_core/src/transaction_base.h"
#include "multy_core/properties.h"
#include "multy_core/src/api/account_impl.h"
#include "multy_core/ethereum.h"
#include "multy_core/blockchain.h"


JavaVM *gJvm = nullptr;
static jobject gClassLoader;
static jmethodID gFindClassMethod;

JNIEnv *getEnv() {
    JNIEnv *env;
    int status = gJvm->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (status < 0) {
        status = gJvm->AttachCurrentThread(&env, NULL);
        if (status < 0) {
            return nullptr;
        }
    }
    return env;
}

#ifdef __cplusplus
extern "C" {
#endif

static int pfd[2];
static pthread_t thr;
static const char *tag = "myapp";

static void *thread_func(void *);

int start_logger(const char *app_name) {
    tag = app_name;

    /* make stdout line-buffered and stderr unbuffered */
    setvbuf(stdout, 0, _IOLBF, 0);
    setvbuf(stderr, 0, _IONBF, 0);

    /* create the pipe and redirect stdout and stderr */
    pipe(pfd);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    /* spawn the logging thread */
    if (pthread_create(&thr, 0, thread_func, 0) == -1)
        return -1;
    pthread_detach(thr);
    return 0;
}

static void *thread_func(void *) {
    ssize_t rdsz;
    char buf[128];
    while ((rdsz = read(pfd[0], buf, sizeof buf - 1)) > 0) {
        if (buf[rdsz - 1] == '\n') --rdsz;
        buf[rdsz] = 0;  /* add null-terminator */
        __android_log_write(ANDROID_LOG_DEBUG, tag, buf);
    }
    return 0;
}

void throw_java_exception_str(JNIEnv *env, const char *message) {
    jclass c = env->FindClass("io/multy/util/JniException");
//    __android_log_print(ANDROID_LOG_INFO, "log message", "!!! Throwing an Error: %s", message);
    env->ThrowNew(c, message);
}

void throw_java_exception(JNIEnv *env, const Error &error) {
    throw_java_exception_str(env, error.message);
}

#define HANDLE_ERROR(statement)                                                                     \
    do {                                                                                            \
        ErrorPtr error(statement);                                                                  \
        if (error)                                                                                  \
        {                                                                                           \
            __android_log_print(ANDROID_LOG_INFO, "Multy-core error", "In file %s, \n in line: %d, jni : %d ", error->location.file , error->location.line, __LINE__ );    \
            throw_java_exception(env, *error);                                                      \
            return (0);                                                                         \
        }                                                                                           \
    } while(false)
//#define HANDLE_ERROR(statement) ERSOR(statement, 0)

JNIEXPORT jint JNICALL
Java_io_multy_util_NativeDataHelper_runTest(JNIEnv *jenv, jclass jcls) {
    jint jresult = 0;

    start_logger("Tests");
    __android_log_print(ANDROID_LOG_INFO, "Multy", "Running tests");
    char *foo = (char *) "foo";
    jresult = run_tests(1, &foo);
    return jresult;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *pjvm, void *reserved) {

    gJvm = pjvm;  // cache the JavaVM pointer
    auto env = getEnv();
    auto randomClass = env->FindClass("io/multy/util/EntropyProvider");
    jclass classClass = env->GetObjectClass(randomClass);
    auto classLoaderClass = env->FindClass("java/lang/ClassLoader");
    auto getClassLoaderMethod = env->GetMethodID(classClass, "getClassLoader",
                                                 "()Ljava/lang/ClassLoader;");
    gClassLoader = env->CallObjectMethod(randomClass, getClassLoaderMethod);
    gFindClassMethod = env->GetMethodID(classLoaderClass, "findClass",
                                        "(Ljava/lang/String;)Ljava/lang/Class;");

    return JNI_VERSION_1_6;
}

jclass findClass(const char *name) {
    return static_cast<jclass>(getEnv()->CallObjectMethod(gClassLoader, gFindClassMethod,
                                                          getEnv()->NewStringUTF(name)));
}

JNIEXPORT jbyteArray JNICALL
Java_io_multy_util_NativeDataHelper_makeSeed(JNIEnv *env, jobject obj, jstring string) {
    using namespace multy_core::internal;
    const char *mnemonic = env->GetStringUTFChars(string, JNI_FALSE);
    const jbyteArray defaultResult{};

    BinaryDataPtr data;
    ErrorPtr error;

    HANDLE_ERROR(make_seed(mnemonic, "", reset_sp(data)));
    env->ReleaseStringUTFChars(string, mnemonic);

    jbyteArray array = env->NewByteArray(data.get()->len);
    env->SetByteArrayRegion(array, 0, data.get()->len, reinterpret_cast<const jbyte *>(data->data));
    return array;
}

JNIEXPORT jstring JNICALL
Java_io_multy_util_NativeDataHelper_makeMnemonic(JNIEnv *jniEnv, jobject obj) {

    using namespace multy_core::internal;
    const jstring defaultResult{};

    auto env = getEnv();
    auto randomClass = env->FindClass("io/multy/util/EntropyProvider");

    jmethodID mid = env->GetStaticMethodID(randomClass, "generateKey", "(I)[B");
    //160 is for 15 mnemonic words
    jbyteArray result = (jbyteArray) env->CallStaticObjectMethod(randomClass, mid, 160);

    typedef std::vector<unsigned char> bytes;
    int len = env->GetArrayLength(result);
    bytes buf(len, 0);
    env->GetByteArrayRegion(result, 0, len, reinterpret_cast<jbyte *>(buf.data()));

    auto fill_entropy = [](void *data, ::size_t size, void *dest) -> ::size_t {
        const bytes *entropy = (const bytes *) (data);
        const size_t result_size = std::min(size, entropy->size());
        memcpy(dest, entropy->data(), result_size);
        return result_size;
    };
    auto entropy_source = EntropySource{(void *) &buf, fill_entropy};

    ConstCharPtr mnemonic_str;

    HANDLE_ERROR(make_mnemonic(entropy_source, reset_sp(mnemonic_str)));
    return jniEnv->NewStringUTF(mnemonic_str.get());
}

JNIEXPORT jstring JNICALL
Java_io_multy_util_NativeDataHelper_makeAccountId(JNIEnv *env, jobject obj, jbyteArray array) {

    using namespace multy_core::internal;
    const jstring defaultResult{};

    size_t len = (size_t) env->GetArrayLength(array);
    unsigned char *buf = new unsigned char[len];
    env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte *>(buf));

    ExtendedKeyPtr rootKey;

    BinaryData seed{buf, len};
    HANDLE_ERROR(make_master_key(&seed, reset_sp(rootKey)));

    const char *id = nullptr;
    HANDLE_ERROR(make_user_id_from_master_key(rootKey.get(), &id));

    return env->NewStringUTF(id);
}

JNIEXPORT jstring JNICALL
Java_io_multy_util_NativeDataHelper_makeAccountAddress(JNIEnv *env, jobject obj, jbyteArray array,
                                                       jint walletIndex, jint addressIndex,
                                                       jint blockchain, jint type) {

    using namespace multy_core::internal;
    const jstring defaultResult{};

    size_t len = (size_t) env->GetArrayLength(array);
    unsigned char *buf = new unsigned char[len];
    env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte *>(buf));

    ExtendedKeyPtr rootKey;

    BinaryData seed{buf, len};
    HANDLE_ERROR(make_master_key(&seed, reset_sp(rootKey)));

    HDAccountPtr hdAccount;
    HANDLE_ERROR(make_hd_account(rootKey.get(),
                                 BlockchainType{(Blockchain) blockchain, (size_t) type},
                                 0,
                                 walletIndex,
                                 reset_sp(hdAccount)));

    AccountPtr account;
    HANDLE_ERROR(make_hd_leaf_account(hdAccount.get(), ADDRESS_EXTERNAL, addressIndex,
                                      reset_sp(account)));

    ConstCharPtr address;
    HANDLE_ERROR(account_get_address_string(account.get(), reset_sp(address)));

    return env->NewStringUTF(address.get());
}

//extern "C"
JNIEXPORT jstring JNICALL
Java_io_multy_util_NativeDataHelper_getMyPrivateKey(JNIEnv *env, jclass type_, jbyteArray array,
                                                    jint walletIndex, jint addressIndex,
                                                    jint blockchain, jint netType) {

    using namespace multy_core::internal;
    const jstring defaultResult{};

    size_t len = (size_t) env->GetArrayLength(array);
    unsigned char *buf = new unsigned char[len];
    env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte *>(buf));

    ExtendedKeyPtr rootKey;

    BinaryData seed{buf, len};
    HANDLE_ERROR(make_master_key(&seed, reset_sp(rootKey)));

    HDAccountPtr hdAccount;
    HANDLE_ERROR(make_hd_account(rootKey.get(), BlockchainType{(Blockchain) blockchain, (size_t) netType},
                                 0,
                                 walletIndex,
                                 reset_sp(hdAccount)));

    AccountPtr account;
    HANDLE_ERROR(make_hd_leaf_account(hdAccount.get(), ADDRESS_EXTERNAL, addressIndex,
                                      reset_sp(account)));

    KeyPtr keyPtr;
    HANDLE_ERROR(account_get_key(account.get(), KEY_TYPE_PRIVATE, reset_sp(keyPtr)));

    ConstCharPtr privKeyStr;
    HANDLE_ERROR(key_to_string(keyPtr.get(), reset_sp(privKeyStr)));

    return env->NewStringUTF(privKeyStr.get());
}

JNIEXPORT jbyteArray JNICALL
Java_io_multy_util_NativeDataHelper_makeTransaction(JNIEnv *jniEnv, jobject obj, jlong jWalletId,
                                                    jint jNetworkId, jbyteArray jSeed,
                                                    jint jWalletIndex, jstring amountToSpend,
                                                    jstring jFeePerByte, jstring jDonation,
                                                    jstring jDestinationAddress,
                                                    jstring jChangeAddress,
                                                    jstring jDonationAddress, jboolean payFee) {
    const jbyteArray defaultResult{};
    bool fladPayFee = (bool) (payFee == JNI_TRUE);
    using namespace multy_core::internal;

    JNIEnv *env = getEnv();
    size_t len = (size_t) env->GetArrayLength(jSeed);
    unsigned char *seedBuf = new unsigned char[len];
    env->GetByteArrayRegion(jSeed, 0, len, reinterpret_cast<jbyte *>(seedBuf));

    jclass jTransaction = env->FindClass("io/multy/util/SendTransactionModel");
    jmethodID jMethodInit = env->GetMethodID(jTransaction, "<init>", "(JLjava/lang/String;)V");
    jobject jObjectTransaction = env->NewObject(jTransaction, jMethodInit, jWalletId,
                                                amountToSpend);

    jmethodID jMidSetup = env->GetMethodID(jTransaction, "setupFields", "(I)V");
    jmethodID jMidAddrIdes = env->GetMethodID(jTransaction, "getAddressesIndexes", "()[I");
    jmethodID jMidIds = env->GetMethodID(jTransaction, "getOutIds", "()[I");
    jmethodID jMidHashes = env->GetMethodID(jTransaction, "getHashes", "()[Ljava/lang/String;");
    jmethodID jMidKeys = env->GetMethodID(jTransaction, "getPubKeys", "()[Ljava/lang/String;");
    jmethodID jMidAmounts = env->GetMethodID(jTransaction, "getAmounts", "()[Ljava/lang/String;");

    jintArray addrIds = (jintArray) env->CallObjectMethod(jObjectTransaction, jMidAddrIdes);
    jint *addressIds = env->GetIntArrayElements(addrIds, NULL);
    int length = env->GetArrayLength(addrIds);

    ExtendedKeyPtr rootKey;

    BinaryData seed{seedBuf, len};
    HANDLE_ERROR(make_master_key(&seed, reset_sp(rootKey)));


    __android_log_print(ANDROID_LOG_INFO, "networkid address", "%d", jNetworkId);

    HDAccountPtr hdAccount;
    HANDLE_ERROR(
            make_hd_account(rootKey.get(),
                            BlockchainType{BLOCKCHAIN_BITCOIN, (size_t) jNetworkId},
                            0,
                            jWalletIndex, reset_sp(hdAccount)));

    AccountPtr baseAccount;
    HANDLE_ERROR(make_hd_leaf_account(hdAccount.get(), ADDRESS_EXTERNAL, 0, reset_sp(baseAccount)));

    TransactionPtr transaction;
    HANDLE_ERROR(make_transaction(baseAccount.get(), reset_sp(transaction)));

    BigInt sum(0);

    const char *donationAmountStr = env->GetStringUTFChars(jDonation, nullptr);
    const char *feePerByteStr = env->GetStringUTFChars(jFeePerByte, nullptr);

    const char *destinationAddressStr = env->GetStringUTFChars(jDestinationAddress, nullptr);
    const char *destinationAmountStr = env->GetStringUTFChars(amountToSpend, nullptr);
    const char *changeAddressStr = env->GetStringUTFChars(jChangeAddress, nullptr);
    const char *donationAddressStr = env->GetStringUTFChars(jDonationAddress, nullptr);

    BigInt destinationAmount(destinationAmountStr);
    const BigInt feePerByte(feePerByteStr);
    const BigInt donationAmount(donationAmountStr);
    size_t outputsCount = 0;
    BigInt total_fee;

    try {

        for (int i = 0; i < length; i++) {
            //SET ADDRESS INDEX
            //GET DATA FROM SET ADDRESS
            jint addressId = addressIds[i];

            env->CallVoidMethod(jObjectTransaction, jMidSetup, addressId);

            jintArray outIds = (jintArray) env->CallObjectMethod(jObjectTransaction, jMidIds);
            jobjectArray hashes = (jobjectArray) env->CallObjectMethod(jObjectTransaction,
                                                                       jMidHashes);
            jobjectArray keys = (jobjectArray) env->CallObjectMethod(jObjectTransaction, jMidKeys);
            jobjectArray amounts = (jobjectArray) env->CallObjectMethod(jObjectTransaction,
                                                                        jMidAmounts);

            int stringCount = env->GetArrayLength(hashes);
            jint *outIdArr = env->GetIntArrayElements(outIds, nullptr);

            AccountPtr account;
            HANDLE_ERROR(make_hd_leaf_account(hdAccount.get(), ADDRESS_EXTERNAL, addressId,
                                              reset_sp(account)));
// START SET INPUTS
            for (int k = 0; k < stringCount; k++) {
                jstring jHash = (jstring) (env->GetObjectArrayElement(hashes, k));
                jstring jKey = (jstring) (env->GetObjectArrayElement(keys, k));
                jstring jAmount = (jstring) (env->GetObjectArrayElement(amounts, k));
                const char *hashString = env->GetStringUTFChars(jHash, 0);
                const char *keyString = env->GetStringUTFChars(jKey, 0);
                const char *amountString = env->GetStringUTFChars(jAmount, 0);
                jint outId = outIdArr[k];

                BinaryDataPtr binaryDataTxHash;
                BinaryDataPtr binaryDataPubKey;
                BigIntPtr amount;
                PrivateKey *private_key_ptr;
                HANDLE_ERROR(account_get_key(account.get(), KEY_TYPE_PRIVATE,
                                             reinterpret_cast<Key **>(&private_key_ptr)));

                PrivateKeyPtr private_key(private_key_ptr);
                HANDLE_ERROR(make_binary_data_from_hex(hashString, reset_sp(binaryDataTxHash)));
                HANDLE_ERROR(make_binary_data_from_hex(keyString, reset_sp(binaryDataPubKey)));
                HANDLE_ERROR(make_big_int(amountString, reset_sp(amount)));

                sum += amountString;

                Properties *source = nullptr;
                HANDLE_ERROR(transaction_add_source(transaction.get(), &source));
                HANDLE_ERROR(properties_set_big_int_value(source, "amount", amount.get()));
                HANDLE_ERROR(properties_set_binary_data_value(source, "prev_tx_hash",
                                                              binaryDataTxHash.get()));
                HANDLE_ERROR(properties_set_int32_value(source, "prev_tx_out_index", outId));
                HANDLE_ERROR(properties_set_binary_data_value(source, "prev_tx_out_script_pubkey",
                                                              binaryDataPubKey.get()));
                HANDLE_ERROR(
                        properties_set_private_key_value(source, "private_key", private_key.get()));

//                source.set_property_value("amount", BigInt(amountString));
//                source.set_property_value("prev_tx_hash", *binaryDataTxHash);
//                source.set_property_value("prev_tx_out_index", outId);
//                source.set_property_value("prev_tx_out_script_pubkey", *binaryDataPubKey);
//                source.set_property_value("private_key", *account->get_private_key());

                outputsCount++;

                env->ReleaseStringUTFChars(jHash, hashString);
                env->ReleaseStringUTFChars(jKey, keyString);
                env->ReleaseStringUTFChars(jAmount, amountString);
            }
            env->ReleaseIntArrayElements(outIds, outIdArr, 0);
        }
// END SET INPUTS

// set fee per byte
        {
            Properties *fee = nullptr;
            HANDLE_ERROR(transaction_get_fee(transaction.get(), &fee));
            HANDLE_ERROR(properties_set_big_int_value(fee, "amount_per_byte", &feePerByte));
        }
// set donation
        if (donationAmount != "0") {
            // donation
            Properties *donation = nullptr;
            HANDLE_ERROR(transaction_add_destination(transaction.get(), &donation));
            HANDLE_ERROR(properties_set_big_int_value(donation, "amount", &donationAmount));
            HANDLE_ERROR(properties_set_string_value(donation, "address", donationAddressStr));
        }

// SET DESTINATION AND CHANGE ADDRESS
        Properties *destination = nullptr;
        Properties *change = nullptr;
        HANDLE_ERROR(transaction_add_destination(transaction.get(), &destination));
        HANDLE_ERROR(properties_set_string_value(destination, "address", destinationAddressStr));
        if (sum == destinationAmount) {
            HANDLE_ERROR(properties_set_int32_value(destination, "is_change", 1));
        } else {
            if (!fladPayFee) {
                destinationAmount = destinationAmount - donationAmount;
            }
            // set destination amount
            HANDLE_ERROR(properties_set_big_int_value(destination, "amount", &destinationAmount));

            //set change destination
            HANDLE_ERROR(transaction_add_destination(transaction.get(), &change));
            HANDLE_ERROR(properties_set_int32_value(change, "is_change", 1));
            HANDLE_ERROR(properties_set_string_value(change, "address", changeAddressStr));

            if (!fladPayFee) {
                HANDLE_ERROR(transaction_update(transaction.get()));
                BigIntPtr fee_transaction;
                HANDLE_ERROR(
                        transaction_get_total_fee(transaction.get(), reset_sp(fee_transaction)));
                BigInt noPtrFeeTransaction(*fee_transaction);
                destinationAmount -= noPtrFeeTransaction;
                HANDLE_ERROR(
                        properties_set_big_int_value(destination, "amount", &destinationAmount));
            }
        }
        HANDLE_ERROR(transaction_update(transaction.get()));

        BinaryDataPtr serialized;
        HANDLE_ERROR(transaction_serialize(transaction.get(), reset_sp(serialized)));

        BigIntPtr fee_transaction;
        HANDLE_ERROR(transaction_get_total_fee(transaction.get(), reset_sp(fee_transaction)));
        BigInt noPtrFeeTransaction(*fee_transaction);

        auto env = getEnv();
        auto randomClass = env->FindClass("io/multy/viewmodels/AssetSendViewModel");

        jstring jstrBuf = env->NewStringUTF(noPtrFeeTransaction.get_value().c_str());

        jmethodID mid = env->GetStaticMethodID(randomClass, "setTransactionPrice",
                                               "(Ljava/lang/String;)V");
        env->CallStaticVoidMethod(randomClass, mid, jstrBuf);





// change:
//        Properties *change = nullptr;
//        HANDLE_ERROR(transaction_add_destination(transaction.get(), &change));
//        HANDLE_ERROR(properties_set_int32_value(change, "is_change", 1));
//        HANDLE_ERROR(properties_set_string_value(change, "address", changeAddressStr));
//            Properties &change = transaction->add_destination();
//            change.set_property_value("address", changeAddressStr);
//            change.set_property_value("amount",
//            sum - destinationAmount - donationAmount - total_fee);
//       total_fee = transaction->estimate_total_fee(outputsCount, donationAmount == "0" ? 2 : 3);


//        if (!fladPayFee) {
//            HANDLE_ERROR(transaction_get_total_fee(transaction.get(), reset_sp(fee_transaction)));
//            BigInt noPtrFeeTransaction(*fee_transaction);
//            //HANDLE_ERROR(big_int_to_string(fee_transaction.get(), &asd));
//            // destinationAmount -= noPtrFeeTransaction;
//            BigInt destinationAm(destinationAmount - noPtrFeeTransaction);
//            HANDLE_ERROR(properties_set_big_int_value(destination, "amount", &destinationAm));
//        }
//
//
//        BigInt change_amount;
//        change->get_property_value("amount", &change_amount);
//        ConstCharPtr change_amount_str;
//        big_int_to_string(&change_amount, reset_sp(change_amount_str));


        //let java side know about tx fee

        env->ReleaseStringUTFChars(jDonation, donationAmountStr);
        env->ReleaseStringUTFChars(jFeePerByte, feePerByteStr);
        env->ReleaseStringUTFChars(jDestinationAddress, destinationAddressStr);
        env->ReleaseStringUTFChars(amountToSpend, destinationAmountStr);
        env->ReleaseStringUTFChars(jChangeAddress, changeAddressStr);
        env->ReleaseStringUTFChars(jDonationAddress, donationAddressStr);

        jbyteArray resultArray = env->NewByteArray(serialized.get()->len);
        env->SetByteArrayRegion(resultArray, 0, serialized.get()->len,
                                reinterpret_cast<const jbyte *>(serialized->data));
        return resultArray;

    } catch (std::exception const &e) {
        throw_java_exception_str(env, e.what());
    } catch (...) {
        throw_java_exception_str(env, "something went wrong");
    }
    env->ReleaseIntArrayElements(addrIds, addressIds, 0);
    return jbyteArray();
}

/** Generates a pseudo-random seed from given mnemonic and password.
 * @param mnemonic - space-separated list of mnemonic words.
 * @param password - password, optional, can be null if not set.
 * @param [out]seed - resulting pseudo-random seed.
 */
MULTY_CORE_API Error *make_seed(
        const char *mnemonic, const char *password, BinaryData **seed);
MULTY_CORE_API Error *seed_to_string(const BinaryData *seed, const char **str);

JNIEXPORT jstring JNICALL
Java_io_multy_util_NativeDataHelper_getDictionary(JNIEnv *env, jclass type) {

    const jstring defaultResult{};
    using namespace multy_core::internal;

    ConstCharPtr dict;
    HANDLE_ERROR(mnemonic_get_dictionary(reset_sp(dict)));

    return env->NewStringUTF(dict.get());
}

JNIEXPORT jstring JNICALL
Java_io_multy_util_NativeDataHelper_getLibraryVersion(JNIEnv *env, jclass type) {

    const jstring defaultResult{};
    using namespace multy_core::internal;

    ConstCharPtr version;
    HANDLE_ERROR(make_version_string(reset_sp(version)));

    return env->NewStringUTF(version.get());
}

JNIEXPORT void JNICALL
Java_io_multy_util_NativeDataHelper_isValidAddress(JNIEnv *env, jclass type_, jstring address_,
                                                   jint blockchain, jint netType) {
    using namespace multy_core::internal;

    const char *address = env->GetStringUTFChars(address_, 0);

    ErrorPtr error(
            validate_address(BlockchainType{(Blockchain) blockchain, (size_t) netType},
                             address));
    if (error) {
        env->ReleaseStringUTFChars(address_, address);
        throw_java_exception(env, *error);
        return;
    }

    env->ReleaseStringUTFChars(address_, address);
    return;
}

JNIEXPORT jbyteArray JNICALL
Java_io_multy_util_NativeDataHelper_makeTransactionETH(JNIEnv *env, jclass type, jbyteArray jSeed,
                                                       jint jWalletIndex,
                                                       jint jAddressIndex, jint jChainId,
                                                       jint jNetType, jstring jBalance,
                                                       jstring jAmount, jstring jDestinationAddress,
                                                       jstring jGasLimit, jstring jGasPrice,
                                                       jstring jNonce) {

    using namespace multy_core::internal;
    const jbyteArray defaultResult{};

    size_t len = (size_t) env->GetArrayLength(jSeed);
    unsigned char *seedBuf = new unsigned char[len];
    env->GetByteArrayRegion(jSeed, 0, len, reinterpret_cast<jbyte *>(seedBuf));

    const char *balanceStr = env->GetStringUTFChars(jBalance, nullptr);
    const char *amountStr = env->GetStringUTFChars(jAmount, nullptr);
    const char *destinationAddressStr = env->GetStringUTFChars(jDestinationAddress, nullptr);
    const char *gasLimitStr = env->GetStringUTFChars(jGasLimit, nullptr);
    const char *gasPriceStr = env->GetStringUTFChars(jGasPrice, nullptr);
    const char *nonceStr = env->GetStringUTFChars(jNonce, nullptr);

    try {

        ExtendedKeyPtr rootKey;

        BinaryData seed{seedBuf, len};
        HANDLE_ERROR(make_master_key(&seed, reset_sp(rootKey)));

        HDAccountPtr hdAccount;
        HANDLE_ERROR(make_hd_account(rootKey.get(),
                                     BlockchainType{(Blockchain) jChainId,
                                                    (size_t) jNetType},
                                     0,
                                     jWalletIndex,
                                     reset_sp(hdAccount)));

        AccountPtr account;
        HANDLE_ERROR(
                make_hd_leaf_account(hdAccount.get(), ADDRESS_EXTERNAL, jAddressIndex,
                                     reset_sp(account)));


        TransactionPtr transaction;
        HANDLE_ERROR(make_transaction(account.get(), reset_sp(transaction)));

        {
            Properties *properties = nullptr;
            BigIntPtr nonce;
            HANDLE_ERROR(make_big_int(nonceStr, reset_sp(nonce)));

            HANDLE_ERROR(transaction_get_properties(transaction.get(), &properties));
            HANDLE_ERROR(properties_set_big_int_value(properties, "nonce", nonce.get()));
            HANDLE_ERROR(
                    properties_set_int32_value(properties, "chain_id", ETHEREUM_CHAIN_ID_RINKEBY));
        }

        {
            Properties *source = nullptr;
            HANDLE_ERROR(transaction_add_source(transaction.get(), &source));

            // Address balance
            BigIntPtr balance;
            HANDLE_ERROR(make_big_int(balanceStr, reset_sp(balance)));
            HANDLE_ERROR(properties_set_big_int_value(source, "amount", balance.get()));
        }

        {
            Properties *destination = nullptr;
            HANDLE_ERROR(transaction_add_destination(transaction.get(), &destination));

            BigIntPtr amount;
            HANDLE_ERROR(make_big_int(amountStr, reset_sp(amount)));
            HANDLE_ERROR(properties_set_big_int_value(destination, "amount", amount.get()));

            BinaryDataPtr address;
            HANDLE_ERROR(make_binary_data_from_hex(destinationAddressStr, reset_sp(address)));
            HANDLE_ERROR(properties_set_binary_data_value(destination, "address", address.get()));
        }

        {
            Properties *fee = nullptr;
            HANDLE_ERROR(transaction_get_fee(transaction.get(), &fee));

            BigIntPtr amount_gas_price;
            HANDLE_ERROR(make_big_int(gasPriceStr, reset_sp(amount_gas_price)));
            HANDLE_ERROR(properties_set_big_int_value(fee, "gas_price", amount_gas_price.get()));

            BigIntPtr amount_gas_limit;
            HANDLE_ERROR(make_big_int(gasLimitStr, reset_sp(amount_gas_limit)));
            HANDLE_ERROR(properties_set_big_int_value(fee, "gas_limit", amount_gas_limit.get()));
        }

        BinaryDataPtr serialized;
        HANDLE_ERROR(transaction_serialize(transaction.get(), reset_sp(serialized)));

        env->ReleaseStringUTFChars(jBalance, balanceStr);
        env->ReleaseStringUTFChars(jAmount, amountStr);
        env->ReleaseStringUTFChars(jDestinationAddress, destinationAddressStr);
        env->ReleaseStringUTFChars(jGasLimit, gasLimitStr);
        env->ReleaseStringUTFChars(jGasPrice, gasPriceStr);
        env->ReleaseStringUTFChars(jNonce, nonceStr);

        jbyteArray resultArray = env->NewByteArray(serialized.get()->len);
        env->SetByteArrayRegion(resultArray, 0, serialized.get()->len,
                                reinterpret_cast<const jbyte *>(serialized->data));
        return resultArray;
    } catch (std::exception const &e) {
        throw_java_exception_str(env, e.what());
    } catch (...) {
        throw_java_exception_str(env, "something went wrong");
    }

    return jbyteArray();
}

#ifdef __cplusplus
} // extern "C"
#endif

struct FinallyWrapper {
    ~FinallyWrapper() {
        finally();
    }

    const std::function<void(void)> finally;
};

#define FINALLY(statement) FinallyWrapper finally_wrapper ## __LINE__{[&](){statement;}}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_io_multy_util_NativeDataHelper_digestSha3256(JNIEnv *env, jclass type, jbyteArray s_) {
    using namespace multy_core::internal;
    const jbyteArray defaultResult{};

    jbyte *data = env->GetByteArrayElements(s_, nullptr);
    FINALLY(env->ReleaseByteArrayElements(s_, data, 0));

    std::array<uint8_t, SHA3_256> hash_buffer;
    BinaryData output{hash_buffer.data(), hash_buffer.size()};

    const BinaryData input{reinterpret_cast<unsigned char *>(data),
                           static_cast<size_t>(env->GetArrayLength(s_))};
    HANDLE_ERROR(sha3(&input, &output));

    jbyteArray resultArray = env->NewByteArray(output.len);
    env->SetByteArrayRegion(resultArray, 0, output.len,
                            reinterpret_cast<const jbyte *>(output.data));
    return resultArray;
}