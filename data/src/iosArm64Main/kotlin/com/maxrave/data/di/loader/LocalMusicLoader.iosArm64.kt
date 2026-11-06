package com.maxrave.data.di.loader

import com.maxrave.data.localmusic.LocalMediaScanner
import com.maxrave.data.localmusic.NoLocalMediaScanner
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

actual fun loadLocalMusicModule() {
    loadKoinModules(module { single<LocalMediaScanner> { NoLocalMediaScanner } })
}
