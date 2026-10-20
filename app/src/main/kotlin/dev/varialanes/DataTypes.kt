package dev.varialanes

import android.content.Context
import android.content.res.Configuration
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Numeric field: number of vehicles behind. Values come from the paired sensor. */
class CarsBehindDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    companion object {
        const val TYPE_ID = "cars-behind"
    }
}

/**
 * Graphical field: top-down lane view of vehicles behind.
 * The Karoo accepts about one view update per second, so it redraws at 1 Hz.
 */
class LaneViewDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val renderer = LaneRenderer()
        val job = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            while (isActive) {
                val frame = if (config.preview) LaneRenderer.demoFrame() else RadarRepository.frame()
                val bitmap = renderer.render(
                    config.viewSize.first, config.viewSize.second, frame,
                    Settings.load(context), isNight(context),
                )
                val views = RemoteViews(context.packageName, R.layout.view_lane).apply {
                    setImageViewBitmap(R.id.lane_image, bitmap)
                }
                emitter.updateView(views)
                delay(1_000)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    companion object {
        const val TYPE_ID = "lane-view"
    }
}
