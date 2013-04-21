// Copyright (c) 2013 Turbulenz Limited
// See LICENSE for full license text.

package com.turbulenz.turbulenz;

import java.util.ArrayList;

import org.json.JSONException;
import org.json.JSONObject;

import android.util.Log;
import android.app.Activity;
import android.app.PendingIntent;
import android.os.Handler;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;

import com.android.vending.billing.IInAppBillingService;

public class payment
{
    public static abstract class CallbackHandler
    {
        abstract public void post(Runnable r);
    };

    // ------------------------------------------------------------------
    //
    // Billing response codes
    public static final int BILLING_RESPONSE_RESULT_OK = 0;
    public static final int BILLING_RESPONSE_RESULT_USER_CANCELED = 1;
    public static final int BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE = 3;
    public static final int BILLING_RESPONSE_RESULT_ITEM_UNAVAILABLE = 4;
    public static final int BILLING_RESPONSE_RESULT_DEVELOPER_ERROR = 5;
    public static final int BILLING_RESPONSE_RESULT_ERROR = 6;
    public static final int BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED = 7;
    public static final int BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED = 8;

    // Keys for the responses from InAppBillingService
    public static final String RESPONSE_CODE = "RESPONSE_CODE";
    public static final String RESPONSE_GET_SKU_DETAILS_LIST = "DETAILS_LIST";
    public static final String RESPONSE_BUY_INTENT = "BUY_INTENT";
    public static final String RESPONSE_INAPP_PURCHASE_DATA =
        "INAPP_PURCHASE_DATA";
    public static final String RESPONSE_INAPP_SIGNATURE =
        "INAPP_DATA_SIGNATURE";
    public static final String RESPONSE_INAPP_ITEM_LIST =
        "INAPP_PURCHASE_ITEM_LIST";
    public static final String RESPONSE_INAPP_PURCHASE_DATA_LIST =
        "INAPP_PURCHASE_DATA_LIST";
    public static final String RESPONSE_INAPP_SIGNATURE_LIST =
        "INAPP_DATA_SIGNATURE_LIST";
    public static final String INAPP_CONTINUATION_TOKEN =
        "INAPP_CONTINUATION_TOKEN";

    public static final String ITEM_TYPE_INAPP = "inapp";
    //
    // ------------------------------------------------------------------

    static Activity             mActivity = null;

    // The request code to use for purchase intents (so that the main
    // activity can identify the responses and forward them back to
    // this class)
    static int                  mPurchaseRequestCode = 0;

    static ServiceConnection    mServiceConnection = null;
    static IInAppBillingService mService = null;

    // A handler to use when the payment ready state changes
    static CallbackHandler      mCallbackHandler = null;
    static CallbackHandler      mReadyHandler = null;
    static long                 mReadyContext = 0;
    static boolean              mReady = false;

    // If not null, indicates that a purchase is already in progress
    static CallbackHandler      mPurchaseHandler = null;
    static long                 mPurchaseContext = 0;

    //
    static private void _log(String msg)
    {
        Log.i("turbulenz-payment: ", msg);
    }

    //
    static private void _error(String msg)
    {
        Log.e("turbulenz-payment: ", msg);
    }

    public static boolean initialize(Activity activity, int purchaseRequestCode)
    {
        mActivity = activity;
        mPurchaseRequestCode = purchaseRequestCode;

        // Just listens for connection / disconnection
        mServiceConnection = new ServiceConnection() {
                @Override
                public void onServiceDisconnected(ComponentName name) {
                    _log("service disconnected :(");
                    mService = null;

                    mReady = false;
                    callOnReadyStatus();
                }

                @Override
                public void onServiceConnected(ComponentName name,
                                               IBinder service) {
                    _log("service connected :)");
                    mService = IInAppBillingService.Stub.asInterface(service);

                    String packageName = mActivity.getPackageName();
                    _log("checking for billing.3 in " + packageName + "...");
                    try {
                        int response =
                            mService.isBillingSupported(3, packageName,
                                                        ITEM_TYPE_INAPP);
                        if (BILLING_RESPONSE_RESULT_OK == response) {
                            mReady = true;
                        } else {
                            _log("billing v3 not supported for this package");
                        }
                    } catch (RemoteException e) {
                        _log("remoteexception:");
                        e.printStackTrace();
                    }

                    callOnReadyStatus();
                }
            };
        _log("binding service ...");
        boolean bound = activity.bindService
            (new Intent
             ("com.android.vending.billing.InAppBillingService.BIND"),
             mServiceConnection, Context.BIND_AUTO_CREATE);
        _log("back from bindService: bound: " + Boolean.toString(bound));

        return mReady;
    }

    public static boolean initialize(Activity activity, int purchaseRequestCode,
                                     CallbackHandler handler)
    {
        mCallbackHandler = handler;
        boolean ret = initialize(activity, purchaseRequestCode);
        return ret;
    }

    // Either returns a known handler for the native thread, or
    // creates one.
    static CallbackHandler getCallbackHandler()
    {
        if (null == mCallbackHandler) {
            mCallbackHandler = new CallbackHandler() {
                    Handler h = new Handler();
                    @Override public void post(Runnable r) {
                        h.post(r);
                    }
                };
        }

        return mCallbackHandler;
    }

    //
    public static void shutdown()
    {
        _log("shutting down ...");
        mReady = false;
        if (null != mServiceConnection) {
            _log("unbinding service");
            mActivity.unbindService(mServiceConnection);
            mServiceConnection = null;
            mService = null;
            _log("service unbound");
        }
        mActivity = null;
        _log("done shutting down.");

        mCallbackHandler = null;
    }

    // Workaround to bug where sometimes response codes come as Long
    // instead of Integer
    static int getResponseCodeFromBundle(Bundle b)
    {
        Object o = b.get(RESPONSE_CODE);
        if (o == null) {
            _log("response code is null, assuming OK"); // known issue
            return BILLING_RESPONSE_RESULT_OK;
        }
        else if (o instanceof Integer) {
            return ((Integer)o).intValue();
        }
        else if (o instanceof Long) {
            return (int)((Long)o).longValue();
        }
        else {
            _log("!! Unexpected type for bundle response code." +
                 o.getClass().getName());

            throw new RuntimeException("Unexpected type for bundle response code: "
                                       + o.getClass().getName());
        }
    }

    // Workaround to bug where sometimes response codes come as Long
    // instead of Integer
    static int getResponseCodeFromIntent(Intent i)
    {
        Object o = i.getExtras().get(RESPONSE_CODE);
        if (o == null) {
            _log("Intent with no response code, assuming OK (known issue)");
            return BILLING_RESPONSE_RESULT_OK;
        }
        else if (o instanceof Integer) {
            return ((Integer)o).intValue();
        }
        else if (o instanceof Long) {
            return (int)((Long)o).longValue();
        }
        else {
            _log("Unexpected type for intent response code.");
            _log(o.getClass().getName());
            throw new RuntimeException("Unexpected intent response code type: "
                                       + o.getClass().getName());
        }
    }

    // ------------------------------------------------------------------
    // doPurchase
    // ------------------------------------------------------------------

    // Return a purchase result via the handler

    static void sendPurchaseFailure(final String msg)
    {
        if (null == mPurchaseHandler) {
            _log("sendPurchaseFailure: !! purchase handler no longer set");
            return;
        }

        final long purchaseContext = mPurchaseContext;
        mPurchaseHandler.post(new Runnable() {
                @Override public void run() {
                    _log("sendPurchaseFailure (runnable): context: " +
                         purchaseContext + ", msg: " + msg);
                    nativeOnPurchaseFailed(purchaseContext, msg);
                    _log("sendPurchaseFailure (runnable): back from native");
                }
            });

        _log("sendPurchaseFailure: posted runnable, nulling out handler");
        mPurchaseHandler = null;
        mPurchaseContext = 0;
    }

    static void sendPurchaseResult(final String sku, final String data,
                                   final String token, final String devPayload,
                                   final String signature)
    {
        if (null == mPurchaseHandler) {
            _log("sendPurchaseResult: !! purchase handler no longer set");
            return;
        }

        final long purchaseContext = mPurchaseContext;
        mPurchaseHandler.post(new Runnable() {
                @Override public void run() {
                    _log("sendPurchaseResult (runnable): context: " +
                         purchaseContext);

                    _log("sendPurchaseResult (h): " +
                         "sku: " + ((null == sku)?("null"):(sku)) +
                         ", data: " + ((null == data)?("null"):(data)) +
                         ", token: " + ((null == token)?("null"):(token)) +
                         ", devPayload: " + ((null == devPayload)?("null"):
                                             (devPayload)) +
                         ", sig: " + ((null == signature)?("null"):(signature)));

                    nativeOnPurchaseComplete(purchaseContext, sku, data, token,
                                             devPayload, signature);
                    _log("sendPurchaseResult (runnable): back from native");
                }
            });

        _log("sendPurchaseResult: posted runnable, nulling out handler");
        mPurchaseHandler = null;
        mPurchaseContext = 0;
    }

    //
    protected static boolean verifyPurchase(String data, String sig)
    {
        // TODO:
        _log("verifyPurchase: !! NO CLIENT SIDE PURCHASE VERIFICATION !!");
        return true;
    }

    // Return value indicates whether or not we handled the Intent,
    // not whether the purchase succeeded.
    public static boolean handleActivityResult(int requestCode, int resultCode,
                                               Intent data)
    {
        _log("onActivityResult: requestCode: " + requestCode +
             " resultCode: " + resultCode);

        if (mPurchaseRequestCode != requestCode) {
            _log("onActivityResult: !! requestCode does not match");
            return false;
        }

        if (Activity.RESULT_CANCELED == resultCode)  {
            _log("onActivityResult: cancelled");
            sendPurchaseFailure(null);
            return true;
        }

        if (Activity.RESULT_OK != resultCode) {
            _log("onActivityResult: unknown result code");
            sendPurchaseFailure("Unknown result code from Google Play");
            return true;
        }

        _log("onActivityResult: resultCode was OK");

        int purchaseResponse = getResponseCodeFromIntent(data);
        if (BILLING_RESPONSE_RESULT_OK != purchaseResponse) {
            _log("onActivityResult: bad purchaseResponse: " + purchaseResponse);
            sendPurchaseFailure("Purchase did not complete");
            return true;
        }

        String purchaseData = data.getStringExtra(RESPONSE_INAPP_PURCHASE_DATA);
        String purchaseSig = data.getStringExtra(RESPONSE_INAPP_SIGNATURE);
        String purchaseGoogleToken;
        String purchaseDevPayload;

        _log("onActivityResult: purchaseResponse: OK" +
             ", purchaseData: " + purchaseData +
             ", purchaseSig: " + purchaseSig);

        if (null == purchaseData || null == purchaseSig) {
            _log("onActivityResult: bad purchase data");
            sendPurchaseFailure("bad purchase data");
            return true;
        }

        if (!verifyPurchase(purchaseData, purchaseSig)) {
            _log("onActivityResult: invalid signature");
            sendPurchaseFailure("invalid signature");
            return true;
        }

        // Extract the sku name from the purchase data

        String sku;
        String googleToken;
        String devPayload;
        try {
            JSONObject o = new JSONObject(purchaseData);
            sku = o.optString("productId");
            googleToken = o.optString("token", o.optString("purchaseToken"));
            devPayload = o.optString("developerPayload");
        } catch(JSONException e) {
            sendPurchaseFailure("failed to get sku data from purchase data");
            return true;
        }

        if (TextUtils.isEmpty(sku)) {
            sendPurchaseFailure("sku name was empty");
            return true;
        }

        _log("onActivityResult: purchase succeeded");
        sendPurchaseResult(sku, purchaseData, googleToken, devPayload,
                           purchaseSig);

        return true;
    }

    //
    static void uiThreadDoPurchase(String sku, String extraData)
    {
        _log("uiThreadDoPurchase: sku: " + sku);

        try {
            Bundle buyIntentBundle =
                mService.getBuyIntent(3, mActivity.getPackageName(),
                                      sku, ITEM_TYPE_INAPP, extraData);
            int response = getResponseCodeFromBundle(buyIntentBundle);
            if (response != BILLING_RESPONSE_RESULT_OK) {
                _log("uiThreadDoPurchase: Failed to create intent bundle, " +
                     "response: " + response);
                sendPurchaseFailure("failed to create Android buy Intent");
                return;
            }

            PendingIntent pendingIntent =
                buyIntentBundle.getParcelable(RESPONSE_BUY_INTENT);
            _log("uiThreadDoPurchase: launching buy intent for sku: " + sku +
                 ", with request code: " + mPurchaseRequestCode);

            mActivity.startIntentSenderForResult
                (pendingIntent.getIntentSender(),
                 mPurchaseRequestCode, new Intent(),
                 Integer.valueOf(0),  // flagsMask
                 Integer.valueOf(0),  // flagsValues
                 Integer.valueOf(0)); // extraFlags
        }
        catch (SendIntentException e) {
            _log("uiThreadDoPurchase: SendIntentException");
            e.printStackTrace();

            sendPurchaseFailure("failed to send intent to Google Play");
        }
        catch (RemoteException e) {
            _log("uiThreadDoPurchase: RemoteException");
            e.printStackTrace();

            sendPurchaseFailure("RemoteException");
        }
    }

    //
    public static boolean doPurchase(final String sku, final String devPayload,
                                     long context)
    {
        _log("doPurchase: ");
        if (!mReady) {
            _log("doPurchase: not ready.  leaving.");
            return false;
        }
        if (null != mPurchaseHandler) {
            _log("doPurchase: !! purchase handler already set (internal error)");
            return false;
        }

        // Create a handler here so we can make callbacks on the
        // calling thread.

        mPurchaseHandler = getCallbackHandler();
        mPurchaseContext = context;

        mActivity.runOnUiThread(new Runnable() {
                @Override public void run() {
                    uiThreadDoPurchase(sku, devPayload);
                }
            });
        return true;
    }

    // ------------------------------------------------------------------
    // doQueryPurchases
    // ------------------------------------------------------------------

    static void sendPurchaseInfo(CallbackHandler handler, final long context,
                                 final String sku, final String data,
                                 final String token, final String devPayload,
                                 final String sig)
    {
        handler.post(new Runnable() {
                @Override public void run() {
                    nativePurchaseQueryResponse(context, sku, data, token,
                                                devPayload, sig);
                }
            });
    }

    static void sendPurchaseInfoTerminator(CallbackHandler handler, final long context)
    {
        handler.post(new Runnable() {
                @Override public void run() {
                    nativePurchaseQueryResponse(context, "",
                                                null, null, null, null);
                }
            });
    }

    static void sendPurchaseInfoError(CallbackHandler handler, final long context,
                                      final String msg)
    {
        handler.post(new Runnable() {
                @Override public void run() {
                    nativePurchaseQueryResponse(context, null, msg,
                                                null, null, null);
                }
            });
    }

    static void threadQueryPurchases(final CallbackHandler handler,
                                     final long context)
    {
        String continueToken = null;
        do {

            Bundle ownedItems;
            try {
                ownedItems = mService.getPurchases
                    (3, mActivity.getPackageName(), ITEM_TYPE_INAPP,
                     continueToken);
            } catch (RemoteException e) {
                _log("threadQueryPurchases: remote exception: " + e);
                e.printStackTrace();
                sendPurchaseInfoError(handler, context, "failed to communicate "
                                      + "with Google Play");
                return;
            }

            int response = getResponseCodeFromBundle(ownedItems);

            if (BILLING_RESPONSE_RESULT_OK != response) {
                _log("doQueryPurchases: !! error retrieving purchased SKUs");
                // TODO: Should we grab something fom saved data here?
                sendPurchaseInfoError(handler, context,
                                      "error retrieving purchase data");
                return;
            }

            if (!ownedItems.containsKey(RESPONSE_INAPP_ITEM_LIST) ||
                !ownedItems.containsKey(RESPONSE_INAPP_PURCHASE_DATA_LIST) ||
                !ownedItems.containsKey(RESPONSE_INAPP_SIGNATURE_LIST)) {

                _log("doQueryPurchases: !! missign fields in response");
                sendPurchaseInfoError(handler, context,
                                      "response missign some fields");
                return;
            }

            ArrayList<String> ownedSkus =
                ownedItems.getStringArrayList(RESPONSE_INAPP_ITEM_LIST);
            ArrayList<String> purchaseData =
                ownedItems.getStringArrayList(RESPONSE_INAPP_PURCHASE_DATA_LIST);
            ArrayList<String> signatureData =
                ownedItems.getStringArrayList(RESPONSE_INAPP_SIGNATURE_LIST);

            _log("doQueryPurchases: listing purchased SKUs:");
            for (int itemIdx = 0 ; itemIdx < purchaseData.size() ; ++itemIdx) {

                final String sku = ownedSkus.get(itemIdx);
                final String data = purchaseData.get(itemIdx);
                final String sig = signatureData.get(itemIdx);

                try {
                    JSONObject o = new JSONObject(data);
                    //o.optString("productId");
                    final String googleToken =
                        o.optString("token", o.optString("purchaseToken"));
                    final String devPayload = o.optString("developerPayload");

                    _log(" - " + sku + ": " + data + " (sig: " + sig + ")");

                    sendPurchaseInfo(handler, context, sku, data, googleToken,
                                     devPayload, sig);

                } catch(JSONException e) {
                    _log("threadQueryPurchases: bad JSON: " + data);
                    sendPurchaseInfoError(handler, context,
                                          "error in purchase data");
                    return;
                }
            }

            continueToken = ownedItems.getString(INAPP_CONTINUATION_TOKEN);
            _log("doQueryPurchases: got continue token: " + continueToken);

        } while(!TextUtils.isEmpty(continueToken));

        sendPurchaseInfoTerminator(handler, context);
    }

    // Call back to native code with the details of each purchase,
    public static boolean doQueryPurchases(final long context)
    {
        if (!mReady) {
            _log("doQueryPurchases: not ready.  leaving.");
            return false;
        }

        _log("doQueryPurchases: ");

        final CallbackHandler handler = getCallbackHandler();

        (new Thread(new Runnable() {
                public void run() {
                    threadQueryPurchases(handler, context);
                }
            })).start();

        _log("doQueryPurchases: launched thread");
        return true;
    }

    // ------------------------------------------------------------------
    // doQueryProduct
    // ------------------------------------------------------------------

    static void sendProductInfoError(final CallbackHandler handler,
                                     final long context, final String sku)
    {
        sendProductInfo(handler, context, sku, null, null, null);
    }

    static void sendProductInfo(final CallbackHandler handler,
                                final long context, final String sku,
                                final String title, final String description,
                                final String price)
    {
        handler.post(new Runnable() {
                @Override public void run() {
                    nativeProductQueryResponse(context, sku, title,
                                               description, price);
                }
            });
    }

    static void threadQueryProduct(final CallbackHandler handler,
                                   final String sku, final long context)
    {
        ArrayList skuList = new ArrayList();
        skuList.add(sku);
        Bundle productQueryBundle = new Bundle();
        productQueryBundle.putStringArrayList("ITEM_ID_LIST", skuList);

        Bundle skuDetails;
        try {
            skuDetails = mService.getSkuDetails
                (3, mActivity.getPackageName(), ITEM_TYPE_INAPP,
                 productQueryBundle);
        } catch (RemoteException e) {
            _log("threadQueryProduct: remote exception: " + e);
            e.printStackTrace();
            sendProductInfoError(handler, context, sku);
            return;
        }

        int response = getResponseCodeFromBundle(skuDetails);
        if (BILLING_RESPONSE_RESULT_OK != response) {
            _log("threadQueryProduct: bad response from getSkuDetails: " +
                 response);
            sendProductInfoError(handler, context, sku);
            return;
        }

        if (!skuDetails.containsKey(RESPONSE_GET_SKU_DETAILS_LIST)) {
            _log("threadQueryProduct: bundle doens't contain list");
            sendProductInfoError(handler, context, sku);
            return;
        }

        ArrayList<String> responseList =
            skuDetails.getStringArrayList(RESPONSE_GET_SKU_DETAILS_LIST);

        if (1 != responseList.size()) {
            _log("threadQueryProduct: repsonse list has unexpected length: " +
                 responseList.size());
            sendProductInfoError(handler, context, sku);
            return;
        }

        String responseString = responseList.get(0);
        try {
            JSONObject o = new JSONObject(responseString);
            final String _sku = o.getString("productId");
            final String title = o.getString("title");
            final String description = o.getString("description");

            // TODO: something with price
            final String price = o.getString("price");

            // TOOD: check _sku == sku

            sendProductInfo(handler, context, sku, title, description, price);
        } catch(JSONException e) {
            _log("threadQueryProduct: failed parsing JSON");
            sendProductInfoError(handler, context, sku);
        }
    }

    public static boolean doQueryProduct(final String sku, final long context)
    {
        if (!mReady) {
            _log("doQueryProduct: no ready");
            return false;
        }

        _log("doQueryProduct: " + sku);

        final CallbackHandler handler = getCallbackHandler();

        (new Thread(new Runnable() {
                public void run() {
                    threadQueryProduct(handler, sku, context);
                }
            })).start();

        _log("doQueryProduct: launched thread");
        return true;
    }

    // ------------------------------------------------------------------
    // doCheckInitialized
    // ------------------------------------------------------------------

    // Determine whether billing has been initialized in the java side
    public static boolean doCheckInitialized()
    {
        boolean initialized = (null != mActivity);
        _log("doCheckInitialized: " + Boolean.toString(initialized));
        return initialized;
    }

    static void callOnReadyStatus()
    {
        if (null != mReadyHandler) {
            _log("callOnReady: posting call to handler ...");

            final long ctx = mReadyContext;
            mReadyHandler.post(new Runnable() {
                    @Override public void run() {
                        _log("callOnReady (h): mReady = " +
                             Boolean.toString(mReady));
                        nativeOnReadyStatus(ctx, mReady);
                    }
                });
        } else {
            _log("callOnReady: no onready handler");
        }
    }

    // Returns whether the service is ready.  If not (and context is
    // non-zero), then a callback is scheduled for when it becomes
    // ready.
    public static boolean doCheckReady(long context)
    {
        _log("doCheckReady: ctx: " + context + ", ready: " +
             Boolean.toString(mReady));

        if (0 != context) {

            if (null == mReadyHandler) {
                mReadyHandler = getCallbackHandler();
            } else {
                _error("doCheckReady: onreadystatus already enabled with " +
                       "context: " + mReadyContext + ". Replacing with " +
                       context);
            }

            _log("doCheckReady: setting ctx: " + context);
            mReadyContext = context;

            // If mReady is already true, make the callback
            // immediately.

            if (mReady) {
                _log("doCheckReady: already ready ... calling immediately");
                nativeOnReadyStatus(context, mReady);
            }

        } else {
            _log("doCheckReady: disabling onReady callbacks");
            mReadyContext = 0;
            mReadyHandler = null;
        }

        return false;
    }

    // ------------------------------------------------------------------
    // doConsume
    // ------------------------------------------------------------------

    // TODO: Make this async?

    // Consume a sku
    public static boolean doConsume(final String token)
    {
        if (!mReady) {
            _log("doConsume: not ready.  leaving.");
            return false;
        }

        if (null == token || token.equals("")) {
            _log("doConsume: !! null or empty token");
            return false;
        }

        _log("doConsume: token: " + token);
        try {
            int response =
                mService.consumePurchase(3, mActivity.getPackageName(), token);

            if (BILLING_RESPONSE_RESULT_OK == response) {
                _log("doConsume: successfully consumed");
                return true;
            } else {
                _log("doConsume: !! failed to consume.  response: " + response);
            }
        } catch (RemoteException e) {
            _log("doConsume: exception " + e.toString());
        }

        return false;
    }

    //------------------------------------------------------------------

    static native void nativeOnReadyStatus(long context, boolean ready);

    static native void nativeOnPurchaseComplete
        (long context, String sku, String details, String token,
         String devPayload, String sig);

    static native void nativeOnPurchaseFailed
        (long context, String msg);

    static native void nativeProductQueryResponse
        (long context, String sku, String title, String description,
         String price);

    // sku == "", details == null, signature == null means end of purchases
    // sku == null, details != null means error (msg in 'details')
    static native void nativePurchaseQueryResponse
        (long context, String sku, String details, String token,
         String devPayload, String sig);

}
