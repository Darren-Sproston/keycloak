/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.federation.infinispan;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.infinispan.client.hotrod.RemoteCache;
import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputUpdater;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.cache.CachedUserModel;
import org.keycloak.models.cache.OnUserCache;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserLookupProvider;
import org.keycloak.storage.user.UserQueryProvider;
import org.keycloak.storage.user.UserRegistrationProvider;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class JDGUserStorageProvider implements UserStorageProvider,
        UserLookupProvider,
        UserRegistrationProvider,
        UserQueryProvider,
        CredentialInputUpdater,
        CredentialInputValidator,
        OnUserCache {

    private final KeycloakSession session;
    private final ComponentModel component;
    protected final JDGUserStorageTransaction transaction;


    private static final Logger logger = Logger.getLogger(JDGUserStorageProvider.class);
    public static final String ID_CACHE_KEY = "id.";
    //public static final String USERNAME_CACHE_KEY = "username.";
    public static final String EMAIL_CACHE_KEY = "email.";
    public static final String FED_CACHE_KEY = "fed.";

    public static final String PASSWORD_CACHE_KEY = "JDGUser.password";

    public JDGUserStorageProvider(KeycloakSession session, ComponentModel component, RemoteCache remoteCache) {
        this.session = session;
        this.component = component;
        this.transaction = new JDGUserStorageTransaction(remoteCache);

        session.getTransactionManager().enlistAfterCompletion(this.transaction);
    }


    @Override
    public void preRemove(RealmModel realm) {

    }

    @Override
    public void preRemove(RealmModel realm, GroupModel group) {

    }

    @Override
    public void preRemove(RealmModel realm, RoleModel role) {

    }

    @Override
    public void close() {
    }

    // id is something like "f:123321-f4546-e884-ffef:john@email.cz"
    @Override
    public UserModel getUserById(String id, RealmModel realm) {
        logger.info("getUserById: " + id);
        String persistenceId = StorageId.externalId(id);
        return getUserByPersistenceId(persistenceId, realm);
    }

    // persistenceId is something like "john@email.cz"
    protected UserModel getUserByPersistenceId(String persistenceId, RealmModel realm) {
        String cacheId = getByIdCacheKey(persistenceId);
        JDGUserEntity entity = (JDGUserEntity) transaction.get(cacheId);
        if (entity == null) {
            logger.info("could not find user by id: " + persistenceId);
            return null;
        }
        return new JDGUserAdapter(session, realm, component, this, entity);
    }

    protected String getByIdCacheKey(String id) {
        return ID_CACHE_KEY + id;
    }

//    private String getByUsernameCacheKey(String username) {
//        return USERNAME_CACHE_KEY + username;
//    }

    protected String getByEmailCacheKey(String email) {
        return EMAIL_CACHE_KEY + email;
    }

    @Override
    public UserModel getUserByUsername(String username, RealmModel realm) {
        logger.info("getUserByUsername: " + username);
        // ID is username
        return getUserByPersistenceId(username, realm);
    }

    @Override
    public UserModel getUserByEmail(String email, RealmModel realm) {
        // In most cases for this impl, ID will be email. Just in case that email changed in account mgmt, it may be different
        logger.info("getUserByEmail: " + email);
        UserModel user = getUserByPersistenceId(email, realm);
        if (user != null) {
            return user;
        }

        logger.infof("not found user by email '%s' . Trying fallback", email);
        String cacheKey = getByEmailCacheKey(email);
        String id = (String) transaction.get(cacheKey);
        if (id == null) {
            return null;
        }
        return getUserByPersistenceId(id, realm);
    }

    @Override
    public UserModel addUser(RealmModel realm, String username) {
        if (getUserByPersistenceId(username, realm) != null) {
            throw new ModelDuplicateException("User '" + username + "' already exists");
        }

        String cacheKey = getByIdCacheKey(username);
        JDGUserEntity entity = new JDGUserEntity();
        entity.setId(username);
        entity.setUsername(username);

        transaction.create(cacheKey, entity);
        logger.info("added user: " + username);
        return new JDGUserAdapter(session, realm, component, this, entity);
    }

    @Override
    public boolean removeUser(RealmModel realm, UserModel user) {
        String persistenceId = StorageId.externalId(user.getId());

        String idCacheKey = getByIdCacheKey(persistenceId);
        transaction.remove(idCacheKey);

        if (user.getEmail() != null) {
            String emailCacheKey = getByEmailCacheKey(user.getEmail());
            transaction.remove(emailCacheKey);
        }

        return true;
    }

    @Override
    public void onCache(RealmModel realm, CachedUserModel user, UserModel delegate) {
        String password = ((JDGUserAdapter)delegate).getPassword();
        if (password != null) {
            user.getCachedWith().put(PASSWORD_CACHE_KEY, password);
        }
        // TODO:mposolda totp
    }

    @Override
    public boolean supportsCredentialType(String credentialType) {
        return CredentialModel.PASSWORD.equals(credentialType);
        // TODO:mposolda totp
    }

    @Override
    public boolean updateCredential(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType()) || !(input instanceof UserCredentialModel)) return false;

        UserCredentialModel cred = (UserCredentialModel)input;
        JDGUserAdapter adapter = getUserAdapter(user);
        adapter.setPassword(cred.getValue());

        // TODO:mposolda totp

        return true;
    }

    public JDGUserAdapter getUserAdapter(UserModel user) {
        JDGUserAdapter adapter = null;
        if (user instanceof CachedUserModel) {
            adapter = (JDGUserAdapter)((CachedUserModel)user).getDelegateForUpdate();
        } else {
            adapter = (JDGUserAdapter)user;
        }
        return adapter;
    }

    @Override
    public void disableCredentialType(RealmModel realm, UserModel user, String credentialType) {
        if (!supportsCredentialType(credentialType)) return;

        getUserAdapter(user).setPassword(null);

        // TODO:mposolda totp
    }

    @Override
    public Set<String> getDisableableCredentialTypes(RealmModel realm, UserModel user) {
        if (getUserAdapter(user).getPassword() != null) {
            Set<String> set = new HashSet<>();
            set.add(CredentialModel.PASSWORD);
            return set;
        } else {
            return Collections.emptySet();
        }

        // TODO:mposolda totp
    }

    @Override
    public boolean isConfiguredFor(RealmModel realm, UserModel user, String credentialType) {
        return supportsCredentialType(credentialType) && getPassword(user) != null;

        // TODO:mposolda totp
    }

    @Override
    public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType()) || !(input instanceof UserCredentialModel)) return false;
        UserCredentialModel cred = (UserCredentialModel)input;
        String password = getPassword(user);
        return password != null && password.equals(cred.getValue());

        // TODO:mposolda totp
    }

    public String getPassword(UserModel user) {
        String password = null;
        if (user instanceof CachedUserModel) {
            password = (String)((CachedUserModel)user).getCachedWith().get(PASSWORD_CACHE_KEY);
        } else if (user instanceof JDGUserAdapter) {
            password = ((JDGUserAdapter)user).getPassword();
        }
        return password;
    }

    @Override
    public int getUsersCount(RealmModel realm) {
        return loadAllUsers(realm).size();
    }

    @Override
    public List<UserModel> getUsers(RealmModel realm) {
        return loadAllUsers(realm);
    }

    @Override
    public List<UserModel> getUsers(RealmModel realm, int firstResult, int maxResults) {
        List<UserModel> users = loadAllUsers(realm);
        return paginateUsers(users.stream(), firstResult, maxResults);
    }

    private List<UserModel> paginateUsers(Stream<UserModel> stream, int firstResult, int maxResults) {
        if (firstResult > -1) {
            stream = stream.skip(firstResult);
        }
        if (maxResults > -1) {
            stream = stream.limit(maxResults);
        }

        return stream.collect(Collectors.toList());
    }

    private List<UserModel> users = null;

    private List<UserModel> loadAllUsers(RealmModel realm) {
        if (users == null) {
            // TODO: Performance killer...
            this.users = (List<UserModel>) transaction.remoteCache.getBulk().values().stream().filter((Object value) -> {

                return value instanceof JDGUserEntity;

            }).map((Object obj) -> {

                JDGUserEntity entity = (JDGUserEntity) obj;
                return new JDGUserAdapter(session, realm, component, JDGUserStorageProvider.this, entity);

            }).collect(Collectors.toList());
        }

        return users;
    }

    @Override
    public List<UserModel> searchForUser(String search, RealmModel realm) {
        return searchForUser(search, realm, -1, -1);
    }

    @Override
    public List<UserModel> searchForUser(String search, RealmModel realm, int firstResult, int maxResults) {
        List<UserModel> users = loadAllUsers(realm);

        Stream<UserModel> stream = users.stream().filter((UserModel user) -> {

            boolean contains = user.getUsername().contains(search);
            contains = contains || (user.getEmail() != null && user.getEmail().contains(search));
            contains = contains || (getFullName(user).contains(search));

            return contains;

        });

        return paginateUsers(stream, firstResult, maxResults);
    }

    private String getFullName(UserModel user) {
        if (user.getFirstName() == null && user.getLastName() == null) return "";

        if (user.getFirstName() == null) return user.getLastName();
        if (user.getLastName() == null) return user.getFirstName();
        return user.getFirstName() + " " + user.getLastName();
    }

    @Override
    public List<UserModel> searchForUser(Map<String, String> params, RealmModel realm) {
        return Collections.EMPTY_LIST;
    }

    @Override
    public List<UserModel> searchForUser(Map<String, String> params, RealmModel realm, int firstResult, int maxResults) {
        return Collections.EMPTY_LIST;
    }

    @Override
    public List<UserModel> getGroupMembers(RealmModel realm, GroupModel group, int firstResult, int maxResults) {
        return Collections.EMPTY_LIST;
    }

    @Override
    public List<UserModel> getGroupMembers(RealmModel realm, GroupModel group) {
        return Collections.EMPTY_LIST;
    }

    @Override
    public List<UserModel> getRoleMembers(RealmModel realm, RoleModel role, int firstResult, int maxResults) {
        return Collections.EMPTY_LIST;
    }

    @Override
    public List<UserModel> getRoleMembers(RealmModel realm, RoleModel role) {
        return Collections.EMPTY_LIST;
    }

    @Override
    public List<UserModel> searchForUserByUserAttribute(String attrName, String attrValue, RealmModel realm) {
        return Collections.EMPTY_LIST;
    }
}
