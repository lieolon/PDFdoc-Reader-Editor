package app.pdfdoc.di

import android.content.Context
import androidx.room.Room
import app.pdfdoc.core.DefaultDispatcher
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.db.AppDatabase
import app.pdfdoc.data.db.DocumentDao
import app.pdfdoc.data.pdf.PdfBoxPageOperations
import app.pdfdoc.data.repo.DocumentRepositoryImpl
import app.pdfdoc.domain.pdf.PageOperations
import app.pdfdoc.domain.repo.DocumentRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "pdfdoc.db").build()

    @Provides
    fun documentDao(db: AppDatabase): DocumentDao = db.documentDao()

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun documentRepository(impl: DocumentRepositoryImpl): DocumentRepository

    @Binds
    abstract fun pageOperations(impl: PdfBoxPageOperations): PageOperations
}
