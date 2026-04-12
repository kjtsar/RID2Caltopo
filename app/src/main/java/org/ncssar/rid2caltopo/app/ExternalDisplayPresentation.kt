package org.ncssar.rid2caltopo.app

import StreamsViewModel
import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.ncssar.rid2caltopo.data.ExternalDisplayConfig
import org.ncssar.rid2caltopo.ui.theme.RID2CaltopoTheme
import org.ncssar.rid2caltopo.video.StreamsScreen

class ExternalDisplayPresentation(
    outerContext: Context,
    display: Display,
    private val streamsViewModel: StreamsViewModel,
    private val config: ExternalDisplayConfig
) : Presentation(outerContext, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!config.allowInteraction) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                RID2CaltopoTheme {
                    StreamsScreen(
                        viewModel = streamsViewModel,
                        onBack = {},
                        showNavigation = false,
                        externalContentMode = config.contentMode
                    )
                }
            }
        }
        setContentView(composeView)
    }
}
