package com.maxrave.data.di.loader

import com.maxrave.data.localmusic.AndroidLocalMediaScanner
import com.maxrave.data.localmusic.LocalMediaScanner
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

actual fun loadLocalMusicModule() {
    loadKoinModules(
        module {
            single<LocalMediaScanner> { AndroidLocalMediaScanner(androidContext()) }
        },
    )
}
