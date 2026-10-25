package dev.sarvenaz.biosignalbridge

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.sarvenaz.biosignalbridge.capture.BleCapture
import dev.sarvenaz.biosignalbridge.capture.SignalTransport
import dev.sarvenaz.biosignalbridge.storage.BridgeDatabase
import dev.sarvenaz.biosignalbridge.storage.BridgePreferences
import dev.sarvenaz.biosignalbridge.storage.SessionRepository
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun capture(@ApplicationContext context: Context) = BleCapture(context)
    @Provides @Singleton fun transport(capture: BleCapture): SignalTransport = capture
    @Provides @Singleton @ApplicationScope fun applicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Provides @Singleton fun database(@ApplicationContext context: Context) = BridgeDatabase.open(context)
    @Provides @Singleton fun repository(database: BridgeDatabase) = SessionRepository(database)
    @Provides @Singleton fun preferences(@ApplicationContext context: Context) = BridgePreferences(context)
}
