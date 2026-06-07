package com.example

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import com.example.service.MediaListenerService
import com.example.state.PreferencesManager

class ToggleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_TOGGLE) {
            val prefs = PreferencesManager(context)
            val nextState = !prefs.isListenerEnabled
            
            // Toggle the state centrally
            MediaListenerService.toggleListenerState(context, nextState)
            
            // Update all widgets
            updateAllWidgets(context)
        }
    }

    companion object {
        private const val ACTION_WIDGET_TOGGLE = "com.example.ACTION_WIDGET_TOGGLE"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, ToggleWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (widgetId in allWidgetIds) {
                updateWidget(context, appWidgetManager, widgetId)
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.toggle_widget)
            val prefs = PreferencesManager(context)
            val isEnabled = prefs.isListenerEnabled

            if (isEnabled) {
                // Active State (Purple / On styling)
                views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_background_on)
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_light_on)
                views.setTextViewText(R.id.widget_title, "Dynamic Light")
                views.setTextColor(R.id.widget_title, Color.parseColor("#21005D"))
                views.setTextViewText(R.id.widget_status, "ACTIVE")
                views.setTextColor(R.id.widget_status, Color.parseColor("#6750A4"))
                views.setImageViewResource(R.id.widget_toggle_btn, R.drawable.ic_power_on)
            } else {
                // Inactive State (Slate grey / Off styling)
                views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_background_off)
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_light_off)
                views.setTextViewText(R.id.widget_title, "Dynamic Light")
                views.setTextColor(R.id.widget_title, Color.parseColor("#1D1B20"))
                views.setTextViewText(R.id.widget_status, "INACTIVE")
                views.setTextColor(R.id.widget_status, Color.parseColor("#49454F"))
                views.setImageViewResource(R.id.widget_toggle_btn, R.drawable.ic_power_off)
            }

            // Set up click intent on the whole widget layout
            val intent = Intent(context, ToggleWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_TOGGLE
            }
            
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                pendingIntentFlags
            )

            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
