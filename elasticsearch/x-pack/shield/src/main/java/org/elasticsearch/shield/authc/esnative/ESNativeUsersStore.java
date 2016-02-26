/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.esnative;

import com.carrotsearch.hppc.ObjectHashSet;
import com.carrotsearch.hppc.ObjectLongHashMap;
import com.carrotsearch.hppc.ObjectLongMap;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectLongCursor;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.LatchedActionListener;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.ClearScrollResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Provider;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.shield.InternalClient;
import org.elasticsearch.shield.ShieldTemplateService;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.action.realm.ClearRealmCacheRequest;
import org.elasticsearch.shield.action.realm.ClearRealmCacheResponse;
import org.elasticsearch.shield.action.user.DeleteUserRequest;
import org.elasticsearch.shield.action.user.PutUserRequest;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.shield.client.SecurityClient;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ESNativeUsersStore is a {@code UserStore} that, instead of reading from a
 * file, reads from an Elasticsearch index instead. This {@code UserStore} in
 * particular implements both a User store and a UserRoles store, which means it
 * is responsible for fetching not only {@code User} objects, but also
 * retrieving the roles for a given username.
 * <p>
 * No caching is done by this class, it is handled at a higher level
 */
public class ESNativeUsersStore extends AbstractComponent implements ClusterStateListener {

    public enum State {
        INITIALIZED,
        STARTING,
        STARTED,
        STOPPING,
        STOPPED,
        FAILED
    }

    // TODO - perhaps separate indices for users/roles instead of types?
    public static final String USER_DOC_TYPE = "user";

    // this map contains the mapping for username -> version, which is used when polling the index to easily detect of
    // any changes that may have been missed since the last update.
    private final ObjectLongHashMap<String> versionMap = new ObjectLongHashMap<>();
    private final Hasher hasher = Hasher.BCRYPT;
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.INITIALIZED);
    private final Provider<InternalClient> clientProvider;
    private final ThreadPool threadPool;

    private ScheduledFuture<?> versionChecker;
    private Client client;
    private int scrollSize;
    private TimeValue scrollKeepAlive;

    private volatile boolean shieldIndexExists = false;

    @Inject
    public ESNativeUsersStore(Settings settings, Provider<InternalClient> clientProvider, ThreadPool threadPool) {
        super(settings);
        this.clientProvider = clientProvider;
        this.threadPool = threadPool;
    }

    /**
     * Blocking version of {@code getUser} that blocks until the User is returned
     */
    public User getUser(String username) {
        if (state() != State.STARTED) {
            logger.trace("attempted to get user [{}] before service was started", username);
            return null;
        }
        UserAndPassword uap = getUserAndPassword(username);
        return uap == null ? null : uap.user();
    }

    /**
     * Retrieve a single user, calling the listener when retrieved
     */
    public void getUser(String username, final ActionListener<User> listener) {
        if (state() != State.STARTED) {
            logger.trace("attempted to get user [{}] before service was started", username);
            listener.onFailure(new IllegalStateException("user cannot be retrieved as native user service has not been started"));
            return;
        }
        getUserAndPassword(username, new ActionListener<UserAndPassword>() {
            @Override
            public void onResponse(UserAndPassword uap) {
                listener.onResponse(uap == null ? null : uap.user());
            }

            @Override
            public void onFailure(Throwable t) {
                if (t instanceof IndexNotFoundException) {
                    logger.trace("failed to retrieve user", t);
                } else {
                    logger.info("failed to retrieve user", t);
                }

                // We don't invoke the onFailure listener here, instead
                // we call the response with a null user
                listener.onResponse(null);
            }
        });
    }

    /**
     * Retrieve a list of users, if usernames is null or empty, fetch all users
     */
    public void getUsers(String[] usernames, final ActionListener<List<User>> listener) {
        if (state() != State.STARTED) {
            logger.trace("attempted to get users before service was started");
            listener.onFailure(new IllegalStateException("users cannot be retrieved as native user service has not been started"));
            return;
        }
        try {
            final List<User> users = new ArrayList<>();
            QueryBuilder query;
            if (usernames == null || usernames.length == 0) {
                query = QueryBuilders.matchAllQuery();
            } else {
                query = QueryBuilders.boolQuery().filter(QueryBuilders.idsQuery(USER_DOC_TYPE).addIds(usernames));
            }
            SearchRequest request = client.prepareSearch(ShieldTemplateService.SECURITY_INDEX_NAME)
                    .setScroll(scrollKeepAlive)
                    .setTypes(USER_DOC_TYPE)
                    .setQuery(query)
                    .setSize(scrollSize)
                    .setFetchSource(true)
                    .request();
            request.indicesOptions().ignoreUnavailable();

            // This function is MADNESS! But it works, don't think about it too hard...
            client.search(request, new ActionListener<SearchResponse>() {
                @Override
                public void onResponse(SearchResponse resp) {
                    boolean hasHits = resp.getHits().getHits().length > 0;
                    if (hasHits) {
                        for (SearchHit hit : resp.getHits().getHits()) {
                            UserAndPassword u = transformUser(hit.getId(), hit.getSource());
                            if (u != null) {
                                users.add(u.user());
                            }
                        }
                        SearchScrollRequest scrollRequest = client.prepareSearchScroll(resp.getScrollId())
                                .setScroll(scrollKeepAlive).request();
                        client.searchScroll(scrollRequest, this);
                    } else {
                        ClearScrollRequest clearScrollRequest = client.prepareClearScroll().addScrollId(resp.getScrollId()).request();
                        client.clearScroll(clearScrollRequest, new ActionListener<ClearScrollResponse>() {
                            @Override
                            public void onResponse(ClearScrollResponse response) {
                                // cool, it cleared, we don't really care though...
                            }

                            @Override
                            public void onFailure(Throwable t) {
                                // Not really much to do here except for warn about it...
                                logger.warn("failed to clear scroll after retrieving all users", t);
                            }
                        });
                        // Finally, return the list of users
                        listener.onResponse(Collections.unmodifiableList(users));
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    if (t instanceof IndexNotFoundException) {
                        logger.trace("could not retrieve users because security index does not exist");
                    } else {
                        logger.info("failed to retrieve users", t);
                    }
                    // We don't invoke the onFailure listener here, instead
                    // we call the response with an empty list
                    listener.onResponse(Collections.emptyList());
                }
            });
        } catch (Exception e) {
            logger.error("unable to retrieve users", e);
            listener.onFailure(e);
        }
    }

    private UserAndPassword getUserAndPassword(String username) {
        final AtomicReference<UserAndPassword> userRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        getUserAndPassword(username, new LatchedActionListener<>(new ActionListener<UserAndPassword>() {
            @Override
            public void onResponse(UserAndPassword user) {
                userRef.set(user);
            }

            @Override
            public void onFailure(Throwable t) {
                logger.info("failed to retrieve user", t);
            }
        }, latch));
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.info("timed out retrieving user");
            return null;
        }
        return userRef.get();
    }

    private void getUserAndPassword(String user, final ActionListener<UserAndPassword> listener) {
        try {
            GetRequest request = client.prepareGet(ShieldTemplateService.SECURITY_INDEX_NAME, USER_DOC_TYPE, user).request();
            request.indicesOptions().ignoreUnavailable();
            client.get(request, new ActionListener<GetResponse>() {
                @Override
                public void onResponse(GetResponse response) {
                    listener.onResponse(transformUser(response.getId(), response.getSource()));
                }

                @Override
                public void onFailure(Throwable t) {
                    if (t instanceof IndexNotFoundException) {
                        logger.trace("could not retrieve user because security index does not exist", t);
                    } else {
                        logger.info("failed to retrieve user", t);
                    }
                    // We don't invoke the onFailure listener here, instead
                    // we call the response with a null user
                    listener.onResponse(null);
                }
            });
        } catch (IndexNotFoundException infe) {
            logger.trace("could not retrieve user because security index does not exist");
            listener.onResponse(null);
        } catch (Exception e) {
            logger.error("unable to retrieve user", e);
            listener.onFailure(e);
        }
    }

    public void putUser(final PutUserRequest request, final ActionListener<Boolean> listener) {
        if (state() != State.STARTED) {
            listener.onFailure(new IllegalStateException("user cannot be added as native user service has not been started"));
            return;
        }

        try {
            IndexRequest indexRequest = client.prepareIndex(ShieldTemplateService.SECURITY_INDEX_NAME, USER_DOC_TYPE, request.username())
                    // we still index the username for more intuitive searchability (e.g. using queries like "username: joe")
                    .setSource(User.Fields.USERNAME.getPreferredName(), request.username(),
                            User.Fields.PASSWORD.getPreferredName(), String.valueOf(request.passwordHash()),
                            User.Fields.ROLES.getPreferredName(), request.roles(),
                            User.Fields.FULL_NAME.getPreferredName(), request.fullName(),
                            User.Fields.EMAIL.getPreferredName(), request.email(),
                            User.Fields.METADATA.getPreferredName(), request.metadata())
                    .setRefresh(request.refresh())
                    .request();

            client.index(indexRequest, new ActionListener<IndexResponse>() {
                @Override
                public void onResponse(IndexResponse indexResponse) {
                    // if the document was just created, then we don't need to clear cache
                    if (indexResponse.isCreated()) {
                        listener.onResponse(indexResponse.isCreated());
                        return;
                    }

                    clearRealmCache(request.username(), listener, indexResponse.isCreated());
                }

                @Override
                public void onFailure(Throwable e) {
                    listener.onFailure(e);
                }
            });
        } catch (Exception e) {
            logger.error("unable to add user", e);
            listener.onFailure(e);
        }
    }

    public void deleteUser(final DeleteUserRequest deleteUserRequest, final ActionListener<Boolean> listener) {
        if (state() != State.STARTED) {
            listener.onFailure(new IllegalStateException("user cannot be deleted as native user service has not been started"));
            return;
        }

        try {
            DeleteRequest request = client.prepareDelete(ShieldTemplateService.SECURITY_INDEX_NAME,
                    USER_DOC_TYPE, deleteUserRequest.username()).request();
            request.indicesOptions().ignoreUnavailable();
            request.refresh(deleteUserRequest.refresh());
            client.delete(request, new ActionListener<DeleteResponse>() {
                @Override
                public void onResponse(DeleteResponse deleteResponse) {
                    clearRealmCache(deleteUserRequest.username(), listener, deleteResponse.isFound());
                }

                @Override
                public void onFailure(Throwable e) {
                    listener.onFailure(e);
                }
            });
        } catch (Exception e) {
            logger.error("unable to remove user", e);
            listener.onFailure(e);
        }
    }

    public boolean canStart(ClusterState clusterState, boolean master) {
        if (state() != State.INITIALIZED) {
            return false;
        }

        if (clusterState.blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            // wait until the gateway has recovered from disk, otherwise we
            // think may not have the .security index but they it may not have
            // been restored from the cluster state on disk yet
            logger.debug("native users store waiting until gateway has recovered from disk");
            return false;
        }

        if (clusterState.metaData().templates().get(ShieldTemplateService.SECURITY_TEMPLATE_NAME) == null) {
            logger.debug("native users template [{}] does not exist, so service cannot start",
                    ShieldTemplateService.SECURITY_TEMPLATE_NAME);
            return false;
        }

        IndexMetaData metaData = clusterState.metaData().index(ShieldTemplateService.SECURITY_INDEX_NAME);
        if (metaData == null) {
            logger.debug("security index [{}] does not exist, so service can start", ShieldTemplateService.SECURITY_INDEX_NAME);
            return true;
        }

        if (clusterState.routingTable().index(ShieldTemplateService.SECURITY_INDEX_NAME).allPrimaryShardsActive()) {
            logger.debug("security index [{}] all primary shards started, so service can start",
                    ShieldTemplateService.SECURITY_INDEX_NAME);
            return true;
        }
        return false;
    }

    public void start() {
        try {
            if (state.compareAndSet(State.INITIALIZED, State.STARTING)) {
                this.client = clientProvider.get();
                this.scrollSize = settings.getAsInt("shield.authc.native.scroll.size", 1000);
                this.scrollKeepAlive = settings.getAsTime("shield.authc.native.scroll.keep_alive", TimeValue.timeValueSeconds(10L));

                // FIXME only start if a realm is using this
                UserStorePoller poller = new UserStorePoller();
                try {
                    poller.doRun();
                } catch (Exception e) {
                    logger.warn("failed to do initial poll of users", e);
                }
                versionChecker = threadPool.scheduleWithFixedDelay(poller,
                        settings.getAsTime("shield.authc.native.reload.interval", TimeValue.timeValueSeconds(30L)));
                state.set(State.STARTED);
            }
        } catch (Exception e) {
            logger.error("failed to start native user store", e);
            state.set(State.FAILED);
        }
    }

    public void stop() {
        if (state.compareAndSet(State.STARTED, State.STOPPING)) {
            try {
                FutureUtils.cancel(versionChecker);
            } catch (Throwable t) {
                state.set(State.FAILED);
                throw t;
            } finally {
                state.set(State.STOPPED);
            }
        }
    }

    /**
     * This method is used to verify the username and credentials against those stored in the system.
     *
     * @param username username to lookup the user by
     * @param password the plaintext password to verify
     * @return {@link} User object if successful or {@code null} if verification fails
     */
    public User verifyPassword(String username, final SecuredString password) {
        if (state() != State.STARTED) {
            logger.trace("attempted to verify user credentials for [{}] but service was not started", username);
            return null;
        }

        UserAndPassword user = getUserAndPassword(username);
        if (user == null || user.passwordHash() == null) {
            return null;
        }
        if (hasher.verify(password, user.passwordHash())) {
            return user.user();
        }
        return null;
    }

    public void addListener(ChangeListener listener) {
        listeners.add(listener);
    }

    private <Response> void clearRealmCache(String username, ActionListener<Response> listener, Response response) {
        SecurityClient securityClient = new SecurityClient(client);
        ClearRealmCacheRequest request = securityClient.prepareClearRealmCache()
                .usernames(username).request();
        securityClient.clearRealmCache(request, new ActionListener<ClearRealmCacheResponse>() {
            @Override
            public void onResponse(ClearRealmCacheResponse nodes) {
                listener.onResponse(response);
            }

            @Override
            public void onFailure(Throwable e) {
                logger.error("unable to clear realm cache for user [{}]", e, username);
                ElasticsearchException exception = new ElasticsearchException("clearing the cache for [" + username
                        + "] failed. please clear the realm cache manually", e);
                listener.onFailure(exception);
            }
        });
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        final boolean exists = event.state().metaData().indices().get(ShieldTemplateService.SECURITY_INDEX_NAME) != null;
        // make sure all the primaries are active
        if (exists && event.state().routingTable().index(ShieldTemplateService.SECURITY_INDEX_NAME).allPrimaryShardsActive()) {
            logger.debug("security index [{}] all primary shards started, so polling can start",
                    ShieldTemplateService.SECURITY_INDEX_NAME);
            shieldIndexExists = true;
        } else {
            // always set the value - it may have changed...
            shieldIndexExists = false;
        }
    }

    public State state() {
        return state.get();
    }

    // FIXME hack for testing
    public void reset() {
        final State state = state();
        if (state != State.STOPPED && state != State.FAILED) {
            throw new IllegalStateException("can only reset if stopped!!!");
        }
        this.versionMap.clear();
        this.listeners.clear();
        this.client = null;
        this.shieldIndexExists = false;
        this.state.set(State.INITIALIZED);
    }

    @Nullable
    private UserAndPassword transformUser(String username, Map<String, Object> sourceMap) {
        if (sourceMap == null) {
            return null;
        }
        try {
            String password = (String) sourceMap.get(User.Fields.PASSWORD.getPreferredName());
            String[] roles = ((List<String>) sourceMap.get(User.Fields.ROLES.getPreferredName())).toArray(Strings.EMPTY_ARRAY);
            String fullName = (String) sourceMap.get(User.Fields.FULL_NAME.getPreferredName());
            String email = (String) sourceMap.get(User.Fields.EMAIL.getPreferredName());
            Map<String, Object> metadata = (Map<String, Object>) sourceMap.get(User.Fields.METADATA.getPreferredName());
            return new UserAndPassword(new User(username, roles, fullName, email, metadata), password.toCharArray());
        } catch (Exception e) {
            logger.error("error in the format of get response for user", e);
            return null;
        }
    }

    private class UserStorePoller extends AbstractRunnable {

        @Override
        public void doRun() {
            if (isStopped()) {
                return;
            }
            if (shieldIndexExists == false) {
                logger.trace("cannot poll for user changes since security index [{}] does not exist", ShieldTemplateService
                        .SECURITY_INDEX_NAME);
                return;
            }

            // hold a reference to the client since the poller may run after the class is stopped (we don't interrupt it running) and
            // we reset when we test which sets the client to null...
            final Client client = ESNativeUsersStore.this.client;

            logger.trace("starting polling of user index to check for changes");
            // create a copy of all known users
            ObjectHashSet<String> knownUsers = new ObjectHashSet<>(versionMap.keys());
            List<String> changedUsers = new ArrayList<>();

            ObjectLongMap<String> currentUsersMap = collectUsersAndVersions(client);
            Iterator<ObjectLongCursor<String>> iterator = currentUsersMap.iterator();
            while (iterator.hasNext()) {
                ObjectLongCursor<String> cursor = iterator.next();
                String username = cursor.key;
                long version = cursor.value;
                if (knownUsers.contains(username)) {
                    final long lastKnownVersion = versionMap.get(username);
                    if (version != lastKnownVersion) {
                        // version is only changed by this method
                        assert version > lastKnownVersion;
                        versionMap.put(username, version);
                        // there is a chance that the user's cache has already been cleared and we'll clear it again but
                        // this should be ok in most cases as user changes should not be that frequent
                        changedUsers.add(username);
                    }
                    knownUsers.remove(username);
                } else {
                    versionMap.put(username, version);
                }
            }

            // exit before comparing with known users
            if (isStopped()) {
                return;
            }

            // we now have a list of users that were in our version map and have been deleted
            Iterator<ObjectCursor<String>> userIter = knownUsers.iterator();
            while (userIter.hasNext()) {
                String user = userIter.next().value;
                versionMap.remove(user);
                changedUsers.add(user);
            }

            if (changedUsers.isEmpty()) {
                return;
            }

            // make the list unmodifiable to prevent modifications by any listeners
            changedUsers = Collections.unmodifiableList(changedUsers);
            if (logger.isDebugEnabled()) {
                logger.debug("changes detected for users [{}]", changedUsers);
            }

            // call listeners
            Throwable th = null;
            for (ChangeListener listener : listeners) {
                try {
                    listener.onUsersChanged(changedUsers);
                } catch (Throwable t) {
                    th = ExceptionsHelper.useOrSuppress(th, t);
                }
            }

            ExceptionsHelper.reThrowIfNotNull(th);
        }

        @Override
        public void onFailure(Throwable t) {
            logger.error("error occurred while checking the native users for changes", t);
        }

        private ObjectLongMap<String> collectUsersAndVersions(Client client) {
            final ObjectLongMap<String> map = new ObjectLongHashMap<>();
            SearchResponse response = null;
            try {
                SearchRequest request = client.prepareSearch(ShieldTemplateService.SECURITY_INDEX_NAME)
                        .setScroll(scrollKeepAlive)
                        .setQuery(QueryBuilders.typeQuery(USER_DOC_TYPE))
                        .setSize(scrollSize)
                        .setVersion(true)
                        .setFetchSource(true)
                        .request();
                response = client.search(request).actionGet();

                boolean keepScrolling = response.getHits().getHits().length > 0;
                while (keepScrolling) {
                    if (isStopped()) {
                        // instead of throwing an exception we return an empty map so nothing is processed and we exit early
                        return new ObjectLongHashMap<>();
                    }
                    for (SearchHit hit : response.getHits().getHits()) {
                        String username = hit.id();
                        long version = hit.version();
                        map.put(username, version);
                    }
                    SearchScrollRequest scrollRequest =
                            client.prepareSearchScroll(response.getScrollId()).setScroll(scrollKeepAlive).request();
                    response = client.searchScroll(scrollRequest).actionGet();
                    keepScrolling = response.getHits().getHits().length > 0;
                }
            } catch (IndexNotFoundException e) {
                logger.trace("security index does not exist", e);
            } finally {
                if (response != null) {
                    ClearScrollRequest clearScrollRequest = client.prepareClearScroll().addScrollId(response.getScrollId()).request();
                    client.clearScroll(clearScrollRequest).actionGet();
                }
            }
            return map;
        }

        private boolean isStopped() {
            State state = state();
            return state == State.STOPPED || state == State.STOPPING;
        }
    }

    interface ChangeListener {

        void onUsersChanged(List<String> username);
    }
}
