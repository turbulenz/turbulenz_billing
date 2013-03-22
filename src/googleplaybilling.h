// Copyright (c) 2013 Turbulenz Limited
// See LICENSE for full license text.

#ifndef __TURBULENZ_ANDROID_PAYMENT_H__
#define __TURBULENZ_ANDROID_PAYMENT_H__

#include <string>
#include <vector>
#include <jni.h>

namespace turbulenz
{

class GooglePlayBilling
{
public:
    struct Purchase
    {
        std::string   sku;
        std::string   details;
        std::string   googleToken;
        std::string   clientToken;
        std::string   signature;
    };

    typedef std::vector<Purchase> PurchaseList;

    typedef void (*ReadyStatusCB)(void *ctx, bool ready);

    typedef void (*PurchaseQueryCB)(void *ctx, const PurchaseList &purchases);

    typedef void (*PurchaseSuccessCB)(void *ctx, const Purchase &purchase);

    typedef void (*PurchaseFailureCB)(void *ctx, const char *message);

    GooglePlayBilling(JNIEnv *jniEnv, jclass paymentClass = 0);

    ~GooglePlayBilling();

    // Set a callback to be notified of ready status changes.  Returns
    // the current status.
    bool SetReadyStatusCallback(void *ctx, ReadyStatusCB callback);

    bool QueryPurchases(void *ctx, PurchaseQueryCB callback);

    bool ConfirmPurchase(void *ctx, const char *sku, const char *clientToken,
                         PurchaseSuccessCB success, PurchaseFailureCB failure);

    bool ConsumePurchase(const char *googleToken);

    void          *mReadyStatusContext;
    ReadyStatusCB  mReadyStatusCallback;

protected:

    bool CallJavaMethod(jmethodID method, ...);

    JNIEnv        *mJNIEnv;
    jclass         mPaymentClass;
    jmethodID      mDoCheckReadyMethod;
    jmethodID      mDoPurchaseMethod;
    jmethodID      mDoQueryPurchasesMethod;
    jmethodID      mDoConsumeMethod;

};

} // namespace turbulenz

#endif // __TURBULENZ_ANDROID_PAYMENT_H__
