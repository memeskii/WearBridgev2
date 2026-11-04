package com.wearos.ancsbridge.weather

import android.content.Context
import android.util.Log
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Wear OS Tile providing an offline, glanceable display of the latest
 * weather data synced from iPhone over BLE Channel 0x03.
 */
class WeatherTileService : TileService() {

    companion object {
        private const val TAG = "WeatherTileService"
        private const val RESOURCES_VERSION = "1"

        /**
         * Triggers a tile refresh request to the system.
         */
        fun requestUpdate(context: Context) {
            try {
                getUpdater(context).requestUpdate(WeatherTileService::class.java)
                Log.d(TAG, "Tile update requested successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to request tile update: ${e.message}")
            }
        }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .build()
        return ImmediateFuture(resources)
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val weather = WeatherRepository.getInstance(this).getLatestWeather()
        val layout = buildTileLayout(weather)

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(layout)
                    .build()
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(30 * 60 * 1000L) // 30 mins
            .build()

        return ImmediateFuture(tile)
    }

    private fun buildTileLayout(weather: WeatherData?): LayoutElementBuilders.Layout {
        val columnBuilder = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        if (weather != null) {
            // City Name
            columnBuilder.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(weather.cityName)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(14f))
                            .setColor(ColorBuilders.argb(0xFF9E9E9E.toInt()))
                            .build()
                    )
                    .build()
            )

            // Current Temp (Big)
            val tempStr = "%.0f°".format(weather.temp)
            columnBuilder.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(tempStr)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(40f))
                            .setColor(ColorBuilders.argb(0xFFFFFFFF.toInt()))
                            .build()
                    )
                    .build()
            )

            // Condition Description
            columnBuilder.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(weather.conditionDescription())
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(13f))
                            .setColor(ColorBuilders.argb(0xFF4FC3F7.toInt()))
                            .build()
                    )
                    .build()
            )

            // High / Low
            val highLowStr = "H: %.0f°  L: %.0f°".format(weather.high, weather.low)
            columnBuilder.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(highLowStr)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(11f))
                            .setColor(ColorBuilders.argb(0xFFB0BEC5.toInt()))
                            .build()
                    )
                    .build()
            )
        } else {
            // No synced weather yet placeholder
            columnBuilder.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("WearBridge Weather")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(14f))
                            .setColor(ColorBuilders.argb(0xFFB0BEC5.toInt()))
                            .build()
                    )
                    .build()
            )
            columnBuilder.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("--°")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(36f))
                            .setColor(ColorBuilders.argb(0xFF757575.toInt()))
                            .build()
                    )
                    .build()
            )
            columnBuilder.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("Syncing with iPhone...")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(12f))
                            .setColor(ColorBuilders.argb(0xFF9E9E9E.toInt()))
                            .build()
                    )
                    .build()
            )
        }

        return LayoutElementBuilders.Layout.Builder()
            .setRoot(columnBuilder.build())
            .build()
    }

    /**
     * Lightweight ListenableFuture implementation for synchronous return.
     */
    private class ImmediateFuture<T>(private val value: T) : ListenableFuture<T> {
        override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
        override fun isDone() = true
        override fun get(): T = value
        override fun get(timeout: Long, unit: TimeUnit): T = value
    }
}

