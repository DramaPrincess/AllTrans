/*
 * Copyright 2017 Akhil Kedia
 * This file is part of AllTrans.
 *
 * AllTrans is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AllTrans is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AllTrans. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package akhil.alltrans;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.concurrent.Semaphore;

import okhttp3.Cache;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionSpec;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

class GetTranslateToken implements Callback {
    private static final Semaphore available = new Semaphore(1, true);
    private static String userCredentials;
    private static long lastExpireTime = 0;
    private static OkHttpClient httpsClient;
    private static OkHttpClient httpClient;
    public GetTranslate getTranslate;

    private static Cache createHttpClientCache() {
        int cacheSize = 1024 * 1024; // 1 MiB
        File cacheDirectory = new File(alltrans.context.getCacheDir(), "AllTransHTTPCache");
        return new Cache(cacheDirectory, cacheSize);
    }

    private static Cache createHttpsClientCache() {
        int cacheSize = 1024 * 1024; // 1 MiB
        File cacheDirectory = new File(alltrans.context.getCacheDir(), "AllTransHTTPsCache");
        return new Cache(cacheDirectory, cacheSize);
    }

    public void doAll() {
        available.acquireUninterruptibly();
        if (httpClient == null) {
            Cache cache = createHttpClientCache();
            httpClient = new OkHttpClient.Builder()
                    .cache(cache)
                    .connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT)).build();
        }
        if (httpsClient == null) {
            Cache cache = createHttpsClientCache();
            httpsClient = new OkHttpClient.Builder()
                    .cache(cache).build();
        }
        available.release();
        if (PreferenceList.EnableYandex)
            doInBackground();
        else {
            available.acquireUninterruptibly();
            long time = System.currentTimeMillis();
            if (time > lastExpireTime) {
                utils.debugLog("In Thread " + Thread.currentThread().getId() + "  Entering get new token for string : " + getTranslate.stringToBeTrans);
                getNewToken();
            } else {
                available.release();
                doInBackground();
            }
        }
    }

    private void getNewToken() {
        try {
            MediaType mediaType = MediaType.parse("application/jwt");
            RequestBody body = RequestBody.create(mediaType, "");

            Request request = new Request.Builder()
                    .url("https://api.cognitive.microsoft.com/sts/v1.0/issueToken")
                    .post(body)
                    .addHeader("Ocp-Apim-Subscription-Key", PreferenceList.SubscriptionKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/jwt")
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build();

            httpsClient.newCall(request).enqueue(this);

        } catch (Exception e) {
            Log.e("AllTrans", "AllTrans: Got error in getting new token as : " + Log.getStackTraceString(e));
        }
    }

    private void doInBackground() {
        try {
            if (PreferenceList.EnableYandex) {
                String baseURL = "https://translate.yandex.net/api/v1.5/tr/translate?";
                String keyURL = "key=" + PreferenceList.SubscriptionKey;
                String textURL = "&text=" + URLEncoder.encode(getTranslate.stringToBeTrans, "UTF-8");
                String languageURL = "&lang=" + PreferenceList.TranslateFromLanguage + "-" + PreferenceList.TranslateToLanguage;
                String fullURL = baseURL + keyURL + textURL + languageURL;

                Request request = new Request.Builder()
                        .url(fullURL)
                        .get()
                        .build();

                utils.debugLog("In Thread " + Thread.currentThread().getId() + "  Enqueuing Request for new translation for : " + getTranslate.stringToBeTrans);
                httpsClient.newCall(request).enqueue(getTranslate);
            } else {
                String baseURL = "http://api.microsofttranslator.com/v2/Http.svc/Translate?text=";
                String languageURL = "&from=" + PreferenceList.TranslateFromLanguage + "&to=" + PreferenceList.TranslateToLanguage;
                String fullURL = baseURL + URLEncoder.encode(getTranslate.stringToBeTrans, "UTF-8") + languageURL;

                Request request = new Request.Builder()
                        .url(fullURL)
                        .get()
                        .addHeader("authorization", "Bearer " + userCredentials)
                        .build();

                utils.debugLog("In Thread " + Thread.currentThread().getId() + "  Enqueuing Request for new translation for : " + getTranslate.stringToBeTrans);
                httpClient.newCall(request).enqueue(getTranslate);
            }
        } catch (java.io.IOException e) {
            Log.e("AllTrans", "AllTrans: Got error in getting translation as : " + Log.getStackTraceString(e));
            if (getTranslate.canCallOriginal) {
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        getTranslate.originalCallable.callOriginalMethod(getTranslate.stringToBeTrans, getTranslate.userData);
                    }
                }, PreferenceList.Delay);
            }
        }
    }

    public void onResponse(Call call, Response response) {
        try {
            String result = response.body().string();
            response.body().close();
            utils.debugLog("Got request result as : " + result);
            userCredentials = result;
            utils.debugLog("In Thread " + Thread.currentThread().getId() + "  Set User Credentials as : " + userCredentials);
            lastExpireTime = System.currentTimeMillis() + 550000;
        } catch (java.io.IOException e) {
            Log.e("AllTrans", "AllTrans: Got error in getting token as : " + Log.getStackTraceString(e));
        } finally {
            available.release();
            doInBackground();
        }
    }

    @Override
    public void onFailure(Call call, IOException e) {
        Log.e("AllTrans", "AllTrans: Got error in getting token as : " + Log.getStackTraceString(e));
        available.release();
        doInBackground();
    }

}
