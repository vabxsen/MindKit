package com.localai.toolkit.di

import javax.inject.Qualifier

/** Dispatcher for disk, database and model work. Never the main thread. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher for CPU bound work such as image downsampling and hashing. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * A coroutine scope tied to the process lifetime.
 *
 * Used only for work that must outlive a screen, such as flushing a history write that
 * was started as the user navigated away.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
