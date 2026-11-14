package io.github.linuxkernel44.openmulticonverter

import android.app.Application
import io.github.linuxkernel44.openmulticonverter.core.ConversionEngine

class OmcApp : Application() {
    lateinit var engine: ConversionEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = ConversionEngine(this)
    }
}
