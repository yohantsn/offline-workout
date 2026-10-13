package com.offlineworkout

import android.app.Application
import com.offlineworkout.data.AppContainer

// T3.1: composition root do processo. Possui um único AppContainer criado por
// processo (lazy). Não cria UI, ViewModels nem escopos de coroutines.
class OfflineWorkoutApplication : Application() {

    private val containerLazy = lazy { AppContainer(this) }
    val container: AppContainer by containerLazy

    // Chamado apenas em ambiente de teste/emulador; o SO pode matar o processo
    // sem avisar. Não força a criação do container só para fechá-lo; close() é
    // idempotente.
    override fun onTerminate() {
        super.onTerminate()
        if (containerLazy.isInitialized()) {
            container.close()
        }
    }
}
