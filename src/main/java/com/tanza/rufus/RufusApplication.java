package com.tanza.rufus;

import com.github.toastshaman.dropwizard.auth.jwt.CachingJwtAuthenticator;
import com.github.toastshaman.dropwizard.auth.jwt.JwtAuthFilter;
import com.tanza.rufus.auth.BasicAuthenticator;
import com.tanza.rufus.auth.JwtAuthenticator;
import com.tanza.rufus.auth.TokenGenerator;
import com.tanza.rufus.core.User;
import com.tanza.rufus.db.ArticleDao;
import com.tanza.rufus.db.UserDao;
import com.tanza.rufus.feed.FeedParser;
import com.tanza.rufus.feed.FeedProcessorImpl;
import com.tanza.rufus.resources.ArticleResource;
import com.tanza.rufus.resources.UserResource;

import io.dropwizard.Application;
import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.jdbi.DBIFactory;
import io.dropwizard.migrations.MigrationsBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

import io.dropwizard.views.ViewBundle;

import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.HmacKey;
import org.skife.jdbi.v2.DBI;

/**
 * Created by jtanza.
 */
public class RufusApplication extends Application<RufusConfiguration> {
    private static final String DB_SOURCE = "postgresql";
    //TODO
    private static final byte[] VERIFICATION_KEY = "super_secret_key_change_me_asap".getBytes();

    public static void main(String[] args) throws Exception {
        new RufusApplication().run(args);
    }

    @Override
    public void initialize(Bootstrap<RufusConfiguration> bootstrap) {
        bootstrap.addBundle(new AssetsBundle("/app", "/", "index.html"));
        bootstrap.addBundle(new ViewBundle<>());
        bootstrap.addBundle(new MigrationsBundle<RufusConfiguration>() {
            @Override
            public DataSourceFactory getDataSourceFactory(RufusConfiguration conf) {
                return conf.getDataSourceFactory();
            }
        });
    }

    @Override
    public void run(RufusConfiguration conf, Environment env) throws Exception {
        final DBIFactory factory = new DBIFactory();
        final DBI jdbi = factory.build(env, conf.getDataSourceFactory(), DB_SOURCE);

        final UserDao userDao = jdbi.onDemand(UserDao.class);
        final ArticleDao articleDao = jdbi.onDemand(ArticleDao.class);

        final FeedProcessorImpl processor = FeedProcessorImpl.newInstance(articleDao);
        final FeedParser parser = new FeedParser(articleDao, processor);

        final JwtConsumer jwtConsumer = new JwtConsumerBuilder()
            .setAllowedClockSkewInSeconds(30)
            .setRequireExpirationTime()
            .setRequireSubject()
            .setVerificationKey(new HmacKey(VERIFICATION_KEY))
            .setRelaxVerificationKeyValidation() //TODO potentially remove
            .build();

        final CachingJwtAuthenticator<User> cachingJwtAuthenticator = new CachingJwtAuthenticator<>(
            env.metrics(),
            new JwtAuthenticator(userDao),
            conf.getAuthenticationCachePolicy()
        );

        //resources
        env.jersey().register(new ArticleResource(userDao, articleDao, processor, parser));
        env.jersey().register(new UserResource(new BasicAuthenticator(userDao), new TokenGenerator(VERIFICATION_KEY), userDao));

        //route source
        env.jersey().setUrlPattern("/api/*");

        //auth
        env.jersey().register(new AuthValueFactoryProvider.Binder<>(User.class));
        env.jersey().register(new AuthDynamicFeature(
            new JwtAuthFilter.Builder<User>()
                .setJwtConsumer(jwtConsumer)
                .setRealm("realm")
                .setPrefix("bearer")
                .setAuthenticator(cachingJwtAuthenticator)
                .buildAuthFilter()
        ));
    }
}
