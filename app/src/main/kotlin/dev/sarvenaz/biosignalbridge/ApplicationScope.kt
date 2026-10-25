package dev.sarvenaz.biosignalbridge

import javax.inject.Qualifier

/** Acquisition lives with the process, while Activities only collect its state. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
