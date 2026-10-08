package com.hebi.quickcut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hebi.quickcut.ui.QuickCutApp
import com.hebi.quickcut.ui.QuickCutTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuickCutTheme {
                QuickCutApp()
            }
        }
    }
}
