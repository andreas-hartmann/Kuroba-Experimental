package com.github.adamantcheese.chan.core.di;

import com.github.adamantcheese.database.di.DatabaseComponent;
import com.github.adamantcheese.database.repository.MediaServiceLinkExtraContentRepository;
import com.github.adamantcheese.database.repository.SeenPostRepository;

import org.codejargon.feather.Provides;

import javax.inject.Singleton;

public class RoomDatabaseModule {
    private DatabaseComponent databaseComponent;

    public RoomDatabaseModule(DatabaseComponent databaseComponent) {
        this.databaseComponent = databaseComponent;
    }

    @Provides
    @Singleton
    public MediaServiceLinkExtraContentRepository provideMediaServiceLinkExtraContentRepository() {
        return databaseComponent.getMediaServiceLinkExtraContentRepository();
    }

    @Provides
    @Singleton
    public SeenPostRepository provideSeenPostRepository() {
        return databaseComponent.getSeenPostRepository();
    }
}
