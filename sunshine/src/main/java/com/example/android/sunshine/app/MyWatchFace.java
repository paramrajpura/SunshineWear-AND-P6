/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener{
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint,mDateTextPaint,mWeatherDataPaint;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset,mXAmbOffset;
        float mYOffset;
        float mXDateOffset;
        float mYDateOffset;

        private final String WEAR_DATA_PATH = MyWatchFace.this.getString(R.string.wear_data_path);
        private GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                .addApi(Wearable.API)
        .addConnectionCallbacks(this)
        .addOnConnectionFailedListener(this)
        .build();
        private String mHighTemp = null, mLowTemp = null;
        private Bitmap mIcon = null;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mYDateOffset = resources.getDimension(R.dimen.digital_y_date_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            float dateTextSize = resources.getDimension(R.dimen.date_text_size);
            mDateTextPaint = new Paint();
            mDateTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDateTextPaint.setTextSize(dateTextSize);

            mCalendar = Calendar.getInstance();

            mWeatherDataPaint = new Paint();
            mWeatherDataPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mWeatherDataPaint.setTextSize(dateTextSize);

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());

                mGoogleApiClient.connect();
                invalidate();
            } else {
                unregisterReceiver();
                if(mGoogleApiClient.isConnected()){
                    Log.v("WearSunshine","disconnected");
                    mGoogleApiClient.disconnect();
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            mXDateOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_date_offset_round : R.dimen.digital_x_date_offset);
            mXAmbOffset = resources.getDimension(isRound
                    ? R.dimen.amb_digital_x_offset_round : R.dimen.amb_digital_x_offset);
            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            int canvasWidth = bounds.width();
            int canvasHeight = bounds.height();
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String text = mAmbient
                    ? String.format("%d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY),
                    mCalendar.get(Calendar.MINUTE))
                    : String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY),
                    mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));

            String month = mCalendar.getDisplayName(Calendar.MONTH,Calendar.SHORT, Locale.US);
            String day = mCalendar.getDisplayName(Calendar.DAY_OF_WEEK,Calendar.SHORT, Locale.US);
            String date = day + "," + month + " " + mCalendar.get(Calendar.DAY_OF_MONTH);

            if (mHighTemp != null && mLowTemp != null){
                String temperature = String.format("%s", mHighTemp + " " +
                        mLowTemp);
                canvas.drawText(temperature, canvasWidth/3+20, (canvasHeight*5)/8+20, mWeatherDataPaint);
            }
            if(mIcon != null) {
                canvas.drawBitmap(mIcon, canvasWidth/10+20, (canvasHeight)/2+10, null);
            }

            canvas.drawText(text, mAmbient? mXAmbOffset:mXOffset, mYOffset, mTextPaint);
            canvas.drawText(date,mXDateOffset,mYDateOffset,mDateTextPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.v("WearSunshine","connected");
            Wearable.DataApi.addListener(mGoogleApiClient, this);

        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.v("WearSunshine","suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.v("WearSunshine","failed"+connectionResult.getErrorMessage());
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.v("WearSunshine","onDataChanged");
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(WEAR_DATA_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        //Long time = dataMap.getLong(getString(R.string.wear_time_key));
                        mHighTemp = dataMap.getString(getString(R.string.wear_high_key));
                        mLowTemp = dataMap.getString(getString(R.string.wear_low_key));
                        Asset iconAsset= dataMap.getAsset(getString(R.string.wear_icon_key));
                        DownloadIconAsyncTask task = new DownloadIconAsyncTask();
                        task.execute(iconAsset);
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }
        }

        private class DownloadIconAsyncTask extends AsyncTask<Asset, Void, Bitmap> {
            @Override
            protected Bitmap doInBackground(Asset... params) {
                return loadBitmapFromAsset(params[0]);
            }

            @Override
            protected void onPostExecute(Bitmap b) {
                mIcon = Bitmap.createScaledBitmap(b,75,75,false);
                invalidate();
            }

            Bitmap loadBitmapFromAsset(Asset asset) {
                if (asset == null) {
                    throw new IllegalArgumentException("Asset must be non-null");
                }
                ConnectionResult result =
                        mGoogleApiClient.blockingConnect(5000, TimeUnit.MILLISECONDS);
                if (!result.isSuccess()) {
                    return null;
                }
                // convert asset into a file descriptor and block until it's ready
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                        mGoogleApiClient, asset).await().getInputStream();

                if (assetInputStream == null) {
                    return null;
                }
                // decode the stream into a bitmap
                return BitmapFactory.decodeStream(assetInputStream);
            }

        }
    }
}
