/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settings.dashboard;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.ArrayMap;
import android.util.Log;
import com.android.settings.SettingsActivity;
import com.android.settingslib.drawer.DashboardCategory;
import com.android.settingslib.drawer.Tile;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class SummaryLoader {
    private static final boolean DEBUG = DashboardSummary.DEBUG;
    private static final String TAG = "SummaryLoader";

    public static final String SUMMARY_PROVIDER_FACTORY = "SUMMARY_PROVIDER_FACTORY";

    private final Activity mActivity;
    private final ArrayMap<SummaryProvider, Tile> mSummaryMap = new ArrayMap<>();
    private final List<Tile> mTiles = new ArrayList<>();

    private final Worker mWorker;
    private final Handler mHandler;
    private final HandlerThread mWorkerThread;

    private DashboardAdapter mAdapter;

    public SummaryLoader(Activity activity, List<DashboardCategory> categories) {
        mHandler = new Handler();
        mWorkerThread = new HandlerThread("SummaryLoader");
        mWorkerThread.start();
        mWorker = new Worker(mWorkerThread.getLooper());
        mActivity = activity;
        for (int i = 0; i < categories.size(); i++) {
            List<Tile> tiles = categories.get(i).tiles;
            for (int j = 0; j < tiles.size(); j++) {
                Tile tile = tiles.get(j);
                mWorker.obtainMessage(Worker.MSG_GET_PROVIDER, tile).sendToTarget();
            }
        }
    }

    public void release() {
        mWorkerThread.quitSafely();
    }

    public void setAdapter(DashboardAdapter adapter) {
        mAdapter = adapter;
    }

    public void setSummary(SummaryProvider provider, final CharSequence summary) {
        final Tile tile = mSummaryMap.get(provider);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                tile.summary = summary;
                mAdapter.notifyChanged(tile);
            }
        });
    }

    public void setListening(boolean listening) {
        mWorker.obtainMessage(Worker.MSG_SET_LISTENING, listening ? 1 : 0, 0).sendToTarget();
    }

    private SummaryProvider getSummaryProvider(Tile tile) {
        if (!mActivity.getPackageName().equals(tile.intent.getComponent().getPackageName())) {
            // Not within Settings, can't load Summary directly.
            // TODO: Load summary indirectly.
            return null;
        }
        Bundle metaData = getMetaData(tile);
        if (metaData == null) {
            if (DEBUG) Log.d(TAG, "No metadata specified for " + tile.intent.getComponent());
            return null;
        }
        String clsName = metaData.getString(SettingsActivity.META_DATA_KEY_FRAGMENT_CLASS);
        if (clsName == null) {
            if (DEBUG) Log.d(TAG, "No fragment specified for " + tile.intent.getComponent());
            return null;
        }
        try {
            Class<?> cls = Class.forName(clsName);
            Field field = cls.getField(SUMMARY_PROVIDER_FACTORY);
            SummaryProviderFactory factory = (SummaryProviderFactory) field.get(null);
            return factory.createSummaryProvider(mActivity, this);
        } catch (ClassNotFoundException e) {
            if (DEBUG) Log.d(TAG, "Couldn't find " + clsName, e);
        } catch (NoSuchFieldException e) {
            if (DEBUG) Log.d(TAG, "Couldn't find " + SUMMARY_PROVIDER_FACTORY, e);
        } catch (ClassCastException e) {
            if (DEBUG) Log.d(TAG, "Couldn't cast " + SUMMARY_PROVIDER_FACTORY, e);
        } catch (IllegalAccessException e) {
            if (DEBUG) Log.d(TAG, "Couldn't get " + SUMMARY_PROVIDER_FACTORY, e);
        }
        return null;
    }

    private Bundle getMetaData(Tile tile) {
        return tile.metaData;
    }

    public interface SummaryProvider {
        void setListening(boolean listening);
    }

    public interface SummaryProviderFactory {
        SummaryProvider createSummaryProvider(Activity activity, SummaryLoader summaryLoader);
    }

    private class Worker extends Handler {
        private static final int MSG_GET_PROVIDER = 1;
        private static final int MSG_SET_LISTENING = 2;

        public Worker(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_GET_PROVIDER:
                    Tile tile = (Tile) msg.obj;
                    SummaryProvider provider = getSummaryProvider(tile);
                    if (provider != null) {
                        if (DEBUG) Log.d(TAG, "Creating " + tile);
                        mSummaryMap.put(provider, tile);
                    }
                    break;
                case MSG_SET_LISTENING:
                    boolean listening = msg.arg1 != 0;
                    if (DEBUG) Log.d(TAG, "Listening " + listening);
                    for (SummaryProvider p : mSummaryMap.keySet()) {
                        p.setListening(listening);
                    }
                    break;
            }
        }
    }
}