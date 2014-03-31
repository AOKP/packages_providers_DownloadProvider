/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.providers.downloads;

import static android.app.DownloadManager.Request.VISIBILITY_VISIBLE;
import static android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED;
import static android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION;
import static android.provider.Downloads.Impl.STATUS_RUNNING;
import static com.android.providers.downloads.Constants.TAG;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Downloads;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.util.LongSparseLongArray;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

import javax.annotation.concurrent.GuardedBy;

/**
 * Update {@link NotificationManager} to reflect current {@link DownloadInfo}
 * states. Collapses similar downloads into a single notification, and builds
 * {@link PendingIntent} that launch towards {@link DownloadReceiver}.
 */
public class DownloadNotifier {

    private static final int TYPE_ACTIVE = 1;
    private static final int TYPE_WAITING = 2;
    private static final int TYPE_PAUSED = 3;
    private static final int TYPE_COMPLETE = 4;

    private final Context mContext;
    private final NotificationManager mNotifManager;

    /**
     * Currently active notifications, mapped from clustering tag to timestamp
     * when first shown.
     *
     * @see #buildNotificationTag(DownloadInfo)
     */
    @GuardedBy("mActiveNotifs")
    private final HashMap<String, Long> mActiveNotifs = Maps.newHashMap();

    /**
     * Current speed of active downloads, mapped from {@link DownloadInfo#mId}
     * to speed in bytes per second.
     */
    @GuardedBy("mDownloadSpeed")
    private final LongSparseLongArray mDownloadSpeed = new LongSparseLongArray();

    /**
     * Last time speed was reproted, mapped from {@link DownloadInfo#mId} to
     * {@link SystemClock#elapsedRealtime()}.
     */
    @GuardedBy("mDownloadSpeed")
    private final LongSparseLongArray mDownloadTouch = new LongSparseLongArray();

    public DownloadNotifier(Context context) {
        mContext = context;
        mNotifManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
    }

    public void cancelAll() {
        mNotifManager.cancelAll();
    }

    /**
     * Notify the current speed of an active download, used for calculating
     * estimated remaining time.
     */
    public void notifyDownloadSpeed(long id, long bytesPerSecond) {
        synchronized (mDownloadSpeed) {
            if (bytesPerSecond != 0) {
                mDownloadSpeed.put(id, bytesPerSecond);
                mDownloadTouch.put(id, SystemClock.elapsedRealtime());
            } else {
                mDownloadSpeed.delete(id);
                mDownloadTouch.delete(id);
            }
        }
    }

    /**
     * Update {@link NotificationManager} to reflect the given set of
     * {@link DownloadInfo}, adding, collapsing, and removing as needed.
     */
    public void updateWith(Collection<DownloadInfo> downloads) {
        synchronized (mActiveNotifs) {
            updateWithLocked(downloads);
        }
    }

    private void updateWithLocked(Collection<DownloadInfo> downloads) {
        final Resources res = mContext.getResources();

        // Cluster downloads together
        final Multimap<String, DownloadInfo> clustered = ArrayListMultimap.create();
        for (DownloadInfo info : downloads) {
            final String tag = buildNotificationTag(info);
            if (tag != null) {
                clustered.put(tag, info);
            }
        }

        // Build notification for each cluster
        for (String tag : clustered.keySet()) {
            final int type = getNotificationTagType(tag);
            final Collection<DownloadInfo> cluster = clustered.get(tag);

            final Notification.Builder builder = new Notification.Builder(mContext);

            // Use time when cluster was first shown to avoid shuffling
            final long firstShown;
            if (mActiveNotifs.containsKey(tag)) {
                firstShown = mActiveNotifs.get(tag);
            } else {
                firstShown = System.currentTimeMillis();
                mActiveNotifs.put(tag, firstShown);
            }
            builder.setWhen(firstShown);

            // Check error status about downloads. If error exists, will
            // update icon and content title/content text in notification.
            boolean hasErrorStatus = false;
            for (DownloadInfo info : cluster) {
                 if (isErrorStatus(info.mStatus)) {
                     hasErrorStatus = true;
                     break;
                 }
            }

            // Show relevant icon
            if (type == TYPE_ACTIVE) {
                if (hasErrorStatus) {
                    builder.setSmallIcon(android.R.drawable.stat_sys_warning);
                } else {
                    builder.setSmallIcon(android.R.drawable.stat_sys_download);
                }
            } else if (type == TYPE_WAITING) {
                builder.setSmallIcon(android.R.drawable.stat_sys_warning);
             } else if (type == TYPE_PAUSED) {
                builder.setSmallIcon(com.android.internal.R.drawable.ic_media_pause);
            } else if (type == TYPE_COMPLETE) {
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done);
            }

            // Build action intents
            if (type == TYPE_ACTIVE || type == TYPE_WAITING || type == TYPE_PAUSED) {
                // build a synthetic uri for intent identification purposes
                final Uri uri = new Uri.Builder().scheme("active-dl").appendPath(tag).build();
                final Intent intent = new Intent(Constants.ACTION_LIST,
                        uri, mContext, DownloadReceiver.class);
                intent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                        getDownloadIds(cluster));
                builder.setContentIntent(PendingIntent.getBroadcast(mContext,
                        0, intent, PendingIntent.FLAG_UPDATE_CURRENT));
                builder.setOngoing(true);

            } else if (type == TYPE_COMPLETE) {
                final DownloadInfo info = cluster.iterator().next();
                final Uri uri = ContentUris.withAppendedId(
                        Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, info.mId);
                builder.setAutoCancel(true);

                final String action;
                if (hasErrorStatus) {
                    action = Constants.ACTION_LIST;
                } else {
                    if (info.mDestination != Downloads.Impl.DESTINATION_SYSTEMCACHE_PARTITION) {
                        action = Constants.ACTION_OPEN;
                    } else {
                        action = Constants.ACTION_LIST;
                    }
                }

                final Intent intent = new Intent(action, uri, mContext, DownloadReceiver.class);
                intent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                        getDownloadIds(cluster));
                builder.setContentIntent(PendingIntent.getBroadcast(mContext,
                        0, intent, PendingIntent.FLAG_UPDATE_CURRENT));

                final Intent hideIntent = new Intent(Constants.ACTION_HIDE,
                        uri, mContext, DownloadReceiver.class);
                builder.setDeleteIntent(PendingIntent.getBroadcast(mContext, 0, hideIntent, 0));
            }

            // Calculate and show progress
            String remainingText = null;
            String durationText = null;
            String percentText = null;
            String speedText = null;
            if (type == TYPE_ACTIVE) {
                long current = 0;
                long total = 0;
                long speed = 0;
                synchronized (mDownloadSpeed) {
                    for (DownloadInfo info : cluster) {
                        if (info.mTotalBytes != -1) {
                            current += info.mCurrentBytes;
                            total += info.mTotalBytes;
                            speed += mDownloadSpeed.get(info.mId);
                        }
                    }
                }

                if (total > 0) {
                    final int percent = (int) ((current * 100) / total);
                    percentText = res.getString(R.string.download_percent, percent);

                    if (speed > 0) {
                        // use Formatter interface for determining speed unit
                        speedText = res.getString(R.string.download_speed,
                                Formatter.formatFileSize(mContext, speed));

                        final long remainingMillis = ((total - current) * 1000) / speed;
                        if (remainingMillis >= DateUtils.HOUR_IN_MILLIS) {
                            final int hours = (int) ((remainingMillis + 1800000)
                                    / DateUtils.HOUR_IN_MILLIS);
                            durationText = res.getQuantityString(
                                    R.plurals.duration_hours, hours, hours);
                        } else if (remainingMillis >= DateUtils.MINUTE_IN_MILLIS) {
                            final int minutes = (int) ((remainingMillis + 30000)
                                    / DateUtils.MINUTE_IN_MILLIS);
                            durationText = res.getQuantityString(
                                    R.plurals.duration_minutes, minutes, minutes);
                        } else {
                            final int seconds = (int) ((remainingMillis + 500)
                                    / DateUtils.SECOND_IN_MILLIS);
                            durationText = res.getQuantityString(
                                    R.plurals.duration_seconds, seconds, seconds);
                        }
                        remainingText = res.getString(R.string.download_remaining, durationText);
                    }

                    builder.setProgress(100, percent, false);
                } else {
                    builder.setProgress(100, 0, true);
                }
            }

            // Build titles and description
            final Notification notif;
            String contentText = null;
            if (cluster.size() == 1) {
                final Notification.InboxStyle inboxStyle = new Notification.InboxStyle(builder);
                final DownloadInfo info = cluster.iterator().next();
                final Uri uris = ContentUris.withAppendedId(
                       Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, info.mId);

                builder.setContentTitle(getDownloadTitle(res, info));
                builder.setContentText(remainingText);

                if (type == TYPE_ACTIVE) {
                    if (!TextUtils.isEmpty(info.mDescription)) {
                        inboxStyle.addLine(info.mDescription);
                    } else {
                        inboxStyle.addLine(res.getString(R.string.download_running));
                    }

                    if (TextUtils.isEmpty(speedText)) {
                        inboxStyle.setSummaryText(remainingText);
                    } else {
                        inboxStyle.setSummaryText(speedText + ", " + remainingText);
                    }

                    builder.setContentInfo(percentText);
                final Intent stopIntent = new Intent(Constants.ACTION_NOTIFICATION_STOP,
                        uris, mContext, DownloadReceiver.class);
                stopIntent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                           getDownloadIds(cluster));

                final Intent pauseIntent = new Intent(Constants.ACTION_NOTIFICATION_PAUSE,
                        uris, mContext, DownloadReceiver.class);
                pauseIntent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                           getDownloadIds(cluster));

                final Intent resumeIntent = new Intent(Constants.ACTION_NOTIFICATION_RESUME,
                        uris, mContext, DownloadReceiver.class);
                resumeIntent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                           getDownloadIds(cluster));

                final Intent retryIntent = new Intent(Constants.ACTION_NOTIFICATION_RETRY,
                        uris, mContext, DownloadReceiver.class);
                retryIntent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                           getDownloadIds(cluster));

                if (!TextUtils.isEmpty(info.mDescription)) {
                    inboxStyle.addLine(info.mDescription);
                } else if (!TextUtils.isEmpty(info.mPackage)) {
                    final PackageManager pm = mContext.getApplicationContext().getPackageManager();
                    ApplicationInfo ai;
                    try {
                        ai = pm.getApplicationInfo(info.mPackage, 0);
                    } catch (final PackageManager.NameNotFoundException e) {
                        ai = null;
                    }
                    final String packageName = (String) (ai != null ? pm.getApplicationLabel(ai) : "(unknown)");
                    if (!TextUtils.isEmpty(packageName)) {
                        inboxStyle.addLine(packageName);
                    }
                }

                if (type == TYPE_ACTIVE) {
                    if (hasErrorStatus) {
                        contentText = res.getString(R.string.notification_download_failed);
                    } else if (TextUtils.isEmpty(speedText)
                               && TextUtils.isEmpty(remainingText)) {
                        contentText = res.getString(R.string.download_running);
                    } else if (!TextUtils.isEmpty(remainingText) && TextUtils.isEmpty(speedText)) {
                        contentText = remainingText;
                    } else if (TextUtils.isEmpty(remainingText) && !TextUtils.isEmpty(speedText)) {
                        contentText = speedText;
                    } else {
                        contentText = speedText + ", " + remainingText;
                    }

                    if (hasErrorStatus) {
                        builder.addAction(com.android.internal.R.drawable.ic_media_play,
                            res.getString(R.string.download_retry),
                        PendingIntent.getBroadcast(mContext,
                        0, retryIntent, PendingIntent.FLAG_UPDATE_CURRENT));
                    } else {
                        builder.addAction(com.android.internal.R.drawable.ic_media_pause,
                            res.getString(R.string.download_pause),
                        PendingIntent.getBroadcast(mContext,
                        0, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT));
                    }
                    builder.addAction(com.android.internal.R.drawable.ic_media_stop,
                            res.getString(R.string.download_stop),
                        PendingIntent.getBroadcast(mContext,
                        0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT));
                } else if (type == TYPE_WAITING) {

                    builder.setContentText(
                            res.getString(R.string.notification_need_wifi_for_size));

                } else if (type == TYPE_COMPLETE) {
                    if (Downloads.Impl.isStatusError(info.mStatus)) {
                        builder.setContentText(
                                res.getText(R.string.notification_download_failed));
                        inboxStyle.setSummaryText(
                                res.getText(R.string.notification_download_failed));

                    contentText = res.getString(R.string.notification_need_wifi_for_size);
                    builder.addAction(com.android.internal.R.drawable.ic_media_stop,
                            res.getString(R.string.download_stop),
                        PendingIntent.getBroadcast(mContext,
                        0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT));
                } else if (type == TYPE_PAUSED) {
                    contentText = res.getString(R.string.notification_paused_in_background);
                    builder
                       .addAction(com.android.internal.R.drawable.ic_media_play,
                            res.getString(R.string.download_resume),
                        PendingIntent.getBroadcast(mContext,
                        0, resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT))
                       .addAction(com.android.internal.R.drawable.ic_media_stop,
                            res.getString(R.string.download_stop),
                        PendingIntent.getBroadcast(mContext,
                        0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT));
                } else if (type == TYPE_COMPLETE) {
                    if (hasErrorStatus) {
                        contentText = res.getString(R.string.notification_download_failed);
                        builder
                          .addAction(com.android.internal.R.drawable.ic_media_play,
                            res.getString(R.string.download_retry),
                        PendingIntent.getBroadcast(mContext,
                        0, retryIntent, PendingIntent.FLAG_UPDATE_CURRENT))
                          .addAction(com.android.internal.R.drawable.ic_media_stop,
                            res.getString(R.string.download_stop),
                        PendingIntent.getBroadcast(mContext,
                        0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT));

                    } else if (Downloads.Impl.isStatusSuccess(info.mStatus)) {
                        builder.setContentText(
                                res.getText(R.string.notification_download_complete));
                        inboxStyle.setSummaryText(
                                res.getText(R.string.notification_download_complete));
                    }
                }


                inboxStyle.setSummaryText(contentText);
                builder.setContentText(contentText);
                builder.setContentInfo(percentText);
                notif = inboxStyle.build();

            } else {
                final Notification.InboxStyle inboxStyle = new Notification.InboxStyle(builder);

                final Uri uris = new Uri.Builder().scheme("active-dl").appendPath(tag).build();

                for (DownloadInfo info : cluster) {
                    inboxStyle.addLine(getDownloadTitle(res, info));
                }

                final Intent stopAllIntent = new Intent(Constants.ACTION_NOTIFICATION_STOP_ALL,
                        uris, mContext, DownloadReceiver.class);
                stopAllIntent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                           getDownloadIds(cluster));

                final Intent pauseAllIntent = new Intent(Constants.ACTION_NOTIFICATION_PAUSE_ALL,
                        uris, mContext, DownloadReceiver.class);
                pauseAllIntent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                           getDownloadIds(cluster));

                final Intent resumeAllIntent = new Intent(Constants.ACTION_NOTIFICATION_RESUME_ALL,
                        uris, mContext, DownloadReceiver.class);
                resumeAllIntent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                           getDownloadIds(cluster));

                final Intent retryAllIntent = new Intent(Constants.ACTION_NOTIFICATION_RETRY_ALL,
                        uris, mContext, DownloadReceiver.class);
                retryAllIntent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                           getDownloadIds(cluster));

                if (type == TYPE_ACTIVE) {
                    if (hasErrorStatus) {
                        builder.setContentTitle(res.getString(R.string.notification_download_failed));
                    } else {
                        builder.setContentTitle(res.getQuantityString(
                            R.plurals.notif_summary_active, cluster.size(), cluster.size()));

                    builder.setContentText(remainingText);
                    builder.setContentInfo(percentText);

                    if (TextUtils.isEmpty(speedText)) {
                        inboxStyle.setSummaryText(remainingText);
                    } else {
                        inboxStyle.setSummaryText(speedText + ", " + remainingText);
                    }

                    }
                    if (TextUtils.isEmpty(speedText) && TextUtils.isEmpty(remainingText)) {
                        contentText = res.getString(R.string.download_running);
                    } else if (!TextUtils.isEmpty(remainingText) && TextUtils.isEmpty(speedText)) {
                        contentText = remainingText;
                    } else if (TextUtils.isEmpty(remainingText) && !TextUtils.isEmpty(speedText)) {
                        contentText = speedText;
                    } else {
                        contentText = speedText + ", " + remainingText;
                    }
                    if (hasErrorStatus) {
                        builder.addAction(com.android.internal.R.drawable.ic_media_play,
                            res.getString(R.string.download_retry_all),
                        PendingIntent.getBroadcast(mContext,
                        0, retryAllIntent, PendingIntent.FLAG_UPDATE_CURRENT));
                    } else {
                        builder.addAction(com.android.internal.R.drawable.ic_media_pause,
                            res.getString(R.string.download_pause_all),
                        PendingIntent.getBroadcast(mContext,
                        0, pauseAllIntent, PendingIntent.FLAG_UPDATE_CURRENT));
                    }
                    builder.addAction(com.android.internal.R.drawable.ic_media_stop,
                            res.getString(R.string.download_stop),
                        PendingIntent.getBroadcast(mContext,
                        0, stopAllIntent, PendingIntent.FLAG_UPDATE_CURRENT));

                } else if (type == TYPE_WAITING) {
                    builder.setContentTitle(res.getQuantityString(
                            R.plurals.notif_summary_waiting, cluster.size(), cluster.size()));
                    contentText = res.getString(R.string.notification_need_wifi_for_size);
                    builder.addAction(com.android.internal.R.drawable.ic_media_stop,
                            res.getString(R.string.download_stop_all),
                        PendingIntent.getBroadcast(mContext,
                        0, stopAllIntent, PendingIntent.FLAG_UPDATE_CURRENT));
                } else if (type == TYPE_PAUSED) {
                    builder.setContentTitle(res.getQuantityString(
                            R.plurals.notif_summary_waiting, cluster.size(), cluster.size()));
                    contentText = res.getString(R.string.notification_paused_in_background);
                    builder
                       .addAction(com.android.internal.R.drawable.ic_media_play,
                            res.getString(R.string.download_resume_all),
                        PendingIntent.getBroadcast(mContext,
                        0, resumeAllIntent, PendingIntent.FLAG_UPDATE_CURRENT))
                       .addAction(com.android.internal.R.drawable.ic_media_stop,
                            res.getString(R.string.download_stop_all),
                        PendingIntent.getBroadcast(mContext,
                        0, stopAllIntent, PendingIntent.FLAG_UPDATE_CURRENT));
                } else if (type == TYPE_COMPLETE) {
                    if (hasErrorStatus) {
                        contentText = res.getString(R.string.notification_download_failed);
                        builder
                          .addAction(com.android.internal.R.drawable.ic_media_play,
                            res.getString(R.string.download_retry_all),
                        PendingIntent.getBroadcast(mContext,
                        0, retryAllIntent, PendingIntent.FLAG_UPDATE_CURRENT))
                          .addAction(com.android.internal.R.drawable.ic_media_stop,
                            res.getString(R.string.download_stop_all),
                        PendingIntent.getBroadcast(mContext,
                        0, stopAllIntent, PendingIntent.FLAG_UPDATE_CURRENT));
                    }
                }

                inboxStyle.setSummaryText(contentText);
                builder.setContentText(contentText);
                builder.setContentInfo(percentText);
                notif = inboxStyle.build();
            }

            mNotifManager.notify(tag, 0, notif);
        }

        // Remove stale tags that weren't renewed
        final Iterator<String> it = mActiveNotifs.keySet().iterator();
        while (it.hasNext()) {
            final String tag = it.next();
            if (!clustered.containsKey(tag)) {
                mNotifManager.cancel(tag, 0);
                it.remove();
            }
        }
    }

    private static CharSequence getDownloadTitle(Resources res, DownloadInfo info) {
        if (!TextUtils.isEmpty(info.mTitle)) {
            return info.mTitle;
        } else {
            return res.getString(R.string.download_unknown_title);
        }
    }

    private long[] getDownloadIds(Collection<DownloadInfo> infos) {
        final long[] ids = new long[infos.size()];
        int i = 0;
        for (DownloadInfo info : infos) {
            ids[i++] = info.mId;
        }
        return ids;
    }

    public void dumpSpeeds() {
        synchronized (mDownloadSpeed) {
            for (int i = 0; i < mDownloadSpeed.size(); i++) {
                final long id = mDownloadSpeed.keyAt(i);
                final long delta = SystemClock.elapsedRealtime() - mDownloadTouch.get(id);
                Log.d(TAG, "Download " + id + " speed " + mDownloadSpeed.valueAt(i) + "bps, "
                        + delta + "ms ago");
            }
        }
    }

    /**
     * Build tag used for collapsing several {@link DownloadInfo} into a single
     * {@link Notification}.
     */
    private static String buildNotificationTag(DownloadInfo info) {
        if (info.mStatus == Downloads.Impl.STATUS_QUEUED_FOR_WIFI) {
            return TYPE_WAITING + ":" + info.mPackage;
        } else if (info.mStatus == Downloads.Impl.STATUS_PAUSED_BY_MANUAL) {
            return TYPE_PAUSED + ":" + info.mPackage;
        } else if (isActiveAndVisible(info)) {
            return TYPE_ACTIVE + ":" + info.mPackage;
        } else if (isCompleteAndVisible(info)) {
            // Complete downloads always have unique notifs
            return TYPE_COMPLETE + ":" + info.mId;
        } else {
            return null;
        }
    }

    /**
     * Return the cluster type of the given tag, as created by
     * {@link #buildNotificationTag(DownloadInfo)}.
     */
    private static int getNotificationTagType(String tag) {
        return Integer.parseInt(tag.substring(0, tag.indexOf(':')));
    }

    private static boolean isActiveAndVisible(DownloadInfo download) {
        return Downloads.Impl.isStatusInformational(download.mStatus) &&
                (download.mVisibility == VISIBILITY_VISIBLE
                || download.mVisibility == VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
    }

    private static boolean isCompleteAndVisible(DownloadInfo download) {
        return Downloads.Impl.isStatusCompleted(download.mStatus) &&
                (download.mVisibility == VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                || download.mVisibility == VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION);
    }

    private boolean isErrorStatus(int status) {
        boolean isErrorStatus = Downloads.Impl.isStatusError(status)
               || Downloads.Impl.isStatusClientError(status)
               || Downloads.Impl.isStatusServerError(status)
               || status == Downloads.Impl.STATUS_INSUFFICIENT_SPACE_ERROR
               || status == Downloads.Impl.STATUS_DEVICE_NOT_FOUND_ERROR
               || status == Downloads.Impl.STATUS_WAITING_FOR_NETWORK;
        return isErrorStatus;
    }
}
