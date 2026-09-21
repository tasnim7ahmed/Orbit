package com.wearos.ancsbridge.ancs

import com.wearos.ancsbridge.model.AncsNotification

/**
 * Most tests only care about the notification case, so they feed bytes in and expect a
 * notification or nothing. The app-name case has its own tests.
 */
fun DataSourceAssembler.feed(data: ByteArray): AncsNotification? =
    (onDataReceived(data) as? DataSourceResult.Notification)?.notification

fun DataSourceAssembler.feedForAppName(data: ByteArray): DataSourceResult.AppName? =
    onDataReceived(data) as? DataSourceResult.AppName
