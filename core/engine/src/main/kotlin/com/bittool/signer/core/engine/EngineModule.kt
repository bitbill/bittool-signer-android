package com.bittool.signer.core.engine

import android.content.Context
import com.bittool.signer.core.engine.internal.QuickJsSignerEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    /** The app binds `@Named(ENGINE_TEST_OPERATIONS)` to BuildConfig.DEBUG: test-only operations never reach release. */
    const val ENGINE_TEST_OPERATIONS = "engineTestOperations"

    @Provides
    @Singleton
    fun signerEngine(
        @ApplicationContext context: Context,
        @Named(ENGINE_TEST_OPERATIONS) allowTestOperations: Boolean,
    ): SignerEngine = QuickJsSignerEngine(context, allowTestOperations)

    /** Test hook: an engine without Hilt (instrumented tests). */
    fun create(context: Context, allowTestOperations: Boolean = false): SignerEngine =
        QuickJsSignerEngine(context.applicationContext, allowTestOperations)
}
