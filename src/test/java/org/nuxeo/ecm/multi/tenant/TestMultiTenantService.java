/*
 * (C) Copyright 2006-2012 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Thomas Roger <troger@nuxeo.com>
 */

package org.nuxeo.ecm.multi.tenant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.nuxeo.ecm.multi.tenant.Constants.TENANTS_DIRECTORY;
import static org.nuxeo.ecm.multi.tenant.Constants.TENANT_ADMINISTRATORS_PROPERTY;
import static org.nuxeo.ecm.multi.tenant.Constants.TENANT_CONFIG_FACET;
import static org.nuxeo.ecm.multi.tenant.Constants.TENANT_ID_PROPERTY;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.NuxeoGroup;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.api.security.impl.ACLImpl;
import org.nuxeo.ecm.core.api.security.impl.ACPImpl;
import org.nuxeo.ecm.core.test.RepositorySettings;
import org.nuxeo.ecm.core.test.TransactionalFeature;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.core.trash.TrashService;
import org.nuxeo.ecm.directory.OperationNotAllowedException;
import org.nuxeo.ecm.directory.Session;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.ecm.platform.test.PlatformFeature;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.ecm.platform.usermanager.exceptions.UserAlreadyExistsException;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LocalDeploy;

/**
 * @author <a href="mailto:troger@nuxeo.com">Thomas Roger</a>
 * @since 5.6
 */
@RunWith(FeaturesRunner.class)
@Features({ TransactionalFeature.class, PlatformFeature.class })
@RepositoryConfig(cleanup = Granularity.METHOD)
@Deploy({ "org.nuxeo.ecm.multi.tenant", "org.nuxeo.ecm.platform.login", "org.nuxeo.ecm.platform.web.common" })
@LocalDeploy({ "org.nuxeo.ecm.platform.test:test-usermanagerimpl/userservice-config.xml",
        "org.nuxeo.ecm.multi.tenant:multi-tenant-test-contrib.xml" })
public class TestMultiTenantService {

    @Inject
    protected RepositorySettings settings;

    @Inject
    protected CoreSession session;

    @Inject
    protected MultiTenantService multiTenantService;

    @Inject
    protected DirectoryService directoryService;

    @Inject
    protected UserManager userManager;

    @Inject
    protected TrashService trashService;

    @After
    public void deleteAllUsersAndGroups() {
        if (userManager.getPrincipal("bender") != null) {
            userManager.deleteUser("bender");
        }
        if (userManager.getPrincipal("fry") != null) {
            userManager.deleteUser("fry");
        }
        if (userManager.getPrincipal("leela") != null) {
            userManager.deleteUser("leela");
        }
        try (Session dir = directoryService.open(TENANTS_DIRECTORY)) {
            DocumentModelList docs = dir.getEntries();
            for (DocumentModel doc : docs) {
                dir.deleteEntry(doc.getId());
            }
        }
        multiTenantService.disableTenantIsolation(session);
        List<DocumentModel> groups = userManager.searchGroups(null);
        for (DocumentModel group : groups) {
            userManager.deleteGroup(group);
        }
    }

    @Test
    public void serviceRegistration() {
        assertNotNull(multiTenantService);
    }

    @Test
    public void shouldDisableTenantIsolation() {
        // make sure the tenant isolation is disabled
        multiTenantService.disableTenantIsolation(session);
        assertFalse(multiTenantService.isTenantIsolationEnabled(session));

        multiTenantService.enableTenantIsolation(session);
        assertTrue(multiTenantService.isTenantIsolationEnabled(session));

        DocumentModel domain = session.getDocument(new PathRef("/default-domain"));
        assertNotNull(domain);
        assertTrue(domain.hasFacet(TENANT_CONFIG_FACET));
        ACL acl = domain.getACP().getOrCreateACL();
        assertNotNull(acl);

        multiTenantService.disableTenantIsolation(session);
        assertFalse(multiTenantService.isTenantIsolationEnabled(session));

        domain = session.getDocument(new PathRef("/default-domain"));
        assertNotNull(domain);
        assertFalse(domain.hasFacet(TENANT_CONFIG_FACET));

        try (Session session = directoryService.open(TENANTS_DIRECTORY)) {
            DocumentModelList docs = session.getEntries();
            assertEquals(0, docs.size());
        }
    }

    @Test
    public void shouldEnableTenantIsolationForNewDomain() {
        multiTenantService.enableTenantIsolation(session);

        DocumentModel newDomain = session.createDocumentModel("/", "newDomain", "Domain");
        newDomain = session.createDocument(newDomain);
        session.save();
        assertTrue(newDomain.hasFacet(TENANT_CONFIG_FACET));
        assertEquals(newDomain.getName(), newDomain.getPropertyValue(TENANT_ID_PROPERTY));

        try (Session session = directoryService.open(TENANTS_DIRECTORY)) {
            DocumentModelList docs = session.getEntries();
            assertEquals(2, docs.size());
            // order from directory is not fixed
            if (docs.get(0).getPropertyValue("tenant:id").equals("newDomain")) {
                // swap
                Collections.reverse(docs);
            }
            DocumentModel doc = docs.get(1);
            assertEquals(newDomain.getName(), doc.getPropertyValue("tenant:id"));
            assertEquals(newDomain.getTitle(), doc.getPropertyValue("tenant:label"));
        }
    }

    @Test
    public void shouldEnableTenantIsolation() {
        multiTenantService.enableTenantIsolation(session);

        assertTrue(multiTenantService.isTenantIsolationEnabled(session));
        DocumentModel domain = session.getDocument(new PathRef("/default-domain"));
        assertNotNull(domain);
        assertTrue(domain.hasFacet(TENANT_CONFIG_FACET));
        assertEquals("default-domain", domain.getPropertyValue(TENANT_ID_PROPERTY));

        ACP acp = domain.getACP();
        ACL acl = acp.getOrCreateACL();
        assertNotNull(acl);

        try (Session session = directoryService.open(TENANTS_DIRECTORY)) {
            DocumentModelList docs = session.getEntries();
            assertEquals(1, docs.size());
            DocumentModel doc = docs.get(0);
            assertEquals(domain.getName(), doc.getPropertyValue("tenant:id"));
            assertEquals(domain.getTitle(), doc.getPropertyValue("tenant:label"));
        }
    }

    @Test
    public void shouldSkipTrashedTenantOnEnable() {
        assertFalse(multiTenantService.isTenantIsolationEnabled(session));

        // create and delete a domain
        DocumentModel newDomain = session.createDocumentModel("/", "newDomain", "Domain");
        newDomain = session.createDocument(newDomain);
        session.save();

        // trash the domain, which incidentally changes its name
        trashService.trashDocuments(Collections.singletonList(newDomain));

        multiTenantService.enableTenantIsolation(session);

        newDomain = session.getDocument(newDomain.getRef());
        assertFalse(newDomain.hasFacet(TENANT_CONFIG_FACET));

        try (Session dirSession = directoryService.open(TENANTS_DIRECTORY)) {
            DocumentModelList docs = dirSession.getEntries();
            assertEquals(1, docs.size());
        }
    }

    @Test
    public void shouldDisableTenantOnTrash() {
        multiTenantService.enableTenantIsolation(session);

        DocumentModel newDomain = session.createDocumentModel("/", "newDomain", "Domain");
        newDomain = session.createDocument(newDomain);
        session.save();
        assertTrue(newDomain.hasFacet(TENANT_CONFIG_FACET));
        assertEquals(newDomain.getName(), newDomain.getPropertyValue(TENANT_ID_PROPERTY));

        try (Session dirSession = directoryService.open(TENANTS_DIRECTORY)) {
            DocumentModelList docs = dirSession.getEntries();
            assertEquals(2, docs.size());
        }

        // trash the domain, which incidentally changes its name
        trashService.trashDocuments(Collections.singletonList(newDomain));

        // not considered a tenant anymore
        newDomain = session.getDocument(newDomain.getRef());
        assertFalse(newDomain.hasFacet(TENANT_CONFIG_FACET));

        try (Session dirSession = directoryService.open(TENANTS_DIRECTORY)) {
            DocumentModelList docs = dirSession.getEntries();
            assertEquals(1, docs.size());
        }
    }

    @Test
    public void shouldGiveManageEverythingRightForTenantManager() throws LoginException {
        multiTenantService.enableTenantIsolation(session);

        DocumentModel domain = session.getDocument(new PathRef("/default-domain"));
        assertNotNull(domain);
        assertTrue(domain.hasFacet(TENANT_CONFIG_FACET));
        assertEquals(domain.getName(), domain.getPropertyValue(TENANT_ID_PROPERTY));

        NuxeoPrincipal bender = createUser("bender", false, domain.getName());
        LoginContext loginContext = Framework.loginAsUser("bender");
        try (CoreSession benderSession = openSession()) {
            assertTrue(benderSession.hasPermission(domain.getRef(), SecurityConstants.READ));
            assertFalse(benderSession.hasPermission(domain.getRef(), SecurityConstants.EVERYTHING));
        }
        loginContext.logout();

        domain.setPropertyValue(TENANT_ADMINISTRATORS_PROPERTY, (Serializable) Arrays.asList("bender"));
        session.saveDocument(domain);
        session.save();

        bender = userManager.getPrincipal(bender.getName());
        loginContext = Framework.loginAsUser("bender");
        try (CoreSession benderSession = openSession()) {
            benderSession.save();
            assertTrue(benderSession.hasPermission(domain.getRef(), SecurityConstants.READ));
            assertTrue(benderSession.hasPermission(domain.getRef(), SecurityConstants.EVERYTHING));
        }
        loginContext.logout();
    }

    @Test
    public void tenantManagerShouldCreateGroupsForTenant() throws LoginException {
        multiTenantService.enableTenantIsolation(session);

        DocumentModel domain = session.getDocument(new PathRef("/default-domain"));

        createUser("fry", true, domain.getName());
        LoginContext loginContext = Framework.loginAsUser("fry");

        NuxeoGroup nuxeoGroup = createGroup("testGroup");
        assertEquals("tenant_" + domain.getName() + "_testGroup", nuxeoGroup.getName());

        List<DocumentModel> groups = userManager.searchGroups(null);
        assertEquals(1, groups.size());
        DocumentModel group = groups.get(0);
        assertEquals("tenant_" + domain.getName() + "_testGroup", group.getPropertyValue("group:groupname"));
        assertEquals(domain.getName(), group.getPropertyValue("group:tenantId"));

        loginContext.logout();

        // other user not belonging to the tenant cannot see the group
        createUser("leela", false, "nonExistingTenant");
        loginContext = Framework.loginAsUser("leela");

        groups = userManager.searchGroups(null);
        assertEquals(0, groups.size());

        loginContext.logout();
    }

    @Test
    public void shouldGiveWriteRightOnTenant() throws LoginException {
        multiTenantService.enableTenantIsolation(session);

        DocumentModel domain = session.getDocument(new PathRef("/default-domain"));
        domain.setPropertyValue(TENANT_ADMINISTRATORS_PROPERTY, (Serializable) Arrays.asList("fry"));
        session.saveDocument(domain);
        session.save();

        NuxeoPrincipal fry = createUser("fry", true, domain.getName());
        LoginContext loginContext = Framework.loginAsUser("fry");

        NuxeoGroup nuxeoGroup = createGroup("supermembers");
        assertEquals("tenant_" + domain.getName() + "_supermembers", nuxeoGroup.getName());

        try (CoreSession frySession = openSession()) {
            // add the Read ACL
            DocumentModel doc = frySession.getDocument(domain.getRef());
            ACP acp = doc.getACP();
            ACL acl = acp.getOrCreateACL();
            acl.add(0, new ACE(nuxeoGroup.getName(), "Write", true));
            doc.setACP(acp, true);
            frySession.saveDocument(doc);
            frySession.save();
        }
        loginContext.logout();

        // bender is part of the supermembers group
        NuxeoPrincipal bender = createUser("bender", false, domain.getName());
        bender.setGroups(Arrays.asList(nuxeoGroup.getName()));
        userManager.updateUser(bender.getModel());
        bender = createUser("bender", false, domain.getName());
        loginContext = Framework.loginAsUser("bender");
        try (CoreSession benderSession = openSession()) {
            assertTrue(benderSession.hasPermission(domain.getRef(), "Write"));
        }
        loginContext.logout();

        // leela does not have Write permission
        NuxeoPrincipal leela = createUser("leela", false, domain.getName());
        loginContext = Framework.loginAsUser("leela");
        try (CoreSession leelaSession = openSession()) {
            assertTrue(leelaSession.hasPermission(domain.getRef(), "Read"));
            assertFalse(leelaSession.hasPermission(domain.getRef(), "Write"));
        }
        loginContext.logout();
    }

    @Test
    public void tenantManagerShouldModifyOnlyTenantGroups() throws LoginException {
        multiTenantService.enableTenantIsolation(session);

        NuxeoGroup noTenantGroup = createGroup("noTenantGroup");
        assertEquals("noTenantGroup", noTenantGroup.getName());

        DocumentModel domain = session.getDocument(new PathRef("/default-domain"));

        createUser("fry", true, domain.getName());
        LoginContext loginContext = Framework.loginAsUser("fry");

        NuxeoGroup tenantGroup = createGroup("tenantGroup");
        assertEquals("tenant_" + domain.getName() + "_tenantGroup", tenantGroup.getName());

        // cannot delete
        try {
            userManager.deleteGroup(noTenantGroup.getName());
            fail();
        } catch (OperationNotAllowedException e) {
            // OK
        }

        // cannot modify
        try {
            assertEquals("noTenantGroup", noTenantGroup.getLabel());
            DocumentModel noTenantGroupModel = userManager.getGroupModel(noTenantGroup.getName());
            noTenantGroupModel.setPropertyValue("group:grouplabel", "new label");
            userManager.updateGroup(noTenantGroupModel);
            fail();
        } catch (OperationNotAllowedException e) {
            // OK
        }

        noTenantGroup = userManager.getGroup(noTenantGroup.getName());
        assertEquals("noTenantGroup", noTenantGroup.getLabel());

        // can modify and delete tenant groups
        DocumentModel testGroupModel = userManager.getGroupModel(tenantGroup.getName());
        testGroupModel.setPropertyValue("group:grouplabel", "new label");
        userManager.updateGroup(testGroupModel);
        tenantGroup = userManager.getGroup(tenantGroup.getName());
        assertEquals("new label", tenantGroup.getLabel());

        userManager.deleteGroup(tenantGroup.getName());
        tenantGroup = userManager.getGroup(tenantGroup.getName());
        assertNull(tenantGroup);

        loginContext.logout();
    }

    @Test
    public void shouldRewriteACLs() {
        multiTenantService.enableTenantIsolation(session);

        DocumentModel newDomain = session.createDocumentModel("/", "newDomain", "Domain");
        newDomain = session.createDocument(newDomain);
        session.save();
        assertTrue(newDomain.hasFacet(TENANT_CONFIG_FACET));
        assertEquals(newDomain.getName(), newDomain.getPropertyValue(TENANT_ID_PROPERTY));

        DocumentModel newWS = session.createDocumentModel(newDomain.getPathAsString(), "newFolder", "Workspace");
        newWS = session.createDocument(newWS);
        session.save();

        ACP acp = new ACPImpl();
        ACL local = new ACLImpl("local");

        local.add(new ACE("toto", "Read", true));
        local.add(new ACE("members", "Read", true));
        local.add(new ACE("Everyone", "Read", true));

        acp.addACL(local);

        newWS.setACP(acp, true);
        newWS = session.getDocument(newWS.getRef());

        acp = newWS.getACP();
        local = acp.getACLs()[0];

        List<String> principals = new ArrayList<>();
        for (ACE ace : local) {
            principals.add(ace.getUsername());
        }

        Assert.assertTrue(principals.contains("toto"));
        Assert.assertTrue(principals.contains("tenant-newDomain_tenantMembers"));
        Assert.assertFalse(principals.contains("members"));
        Assert.assertFalse(principals.contains("Everyone"));
    }

    protected CoreSession openSession() {
        return settings.openSession();
    }

    protected String getPowerUsersGroup() throws LoginException {
        LoginContext login = Framework.loginAs("Administrator");
        NuxeoGroup pwrUsrGrp = userManager.getGroup(Constants.POWER_USERS_GROUP);

        if (pwrUsrGrp != null) {
            return pwrUsrGrp.getName();
        }

        DocumentModel powerUsers = userManager.getBareGroupModel();
        powerUsers.setPropertyValue("group:groupname", Constants.POWER_USERS_GROUP);
        powerUsers = userManager.createGroup(powerUsers);
        login.logout();
        return powerUsers.getId();
    }

    protected NuxeoPrincipal createUser(String username, boolean isPowerUser, String tenant) throws
            LoginException {
        DocumentModel user = userManager.getBareUserModel();
        user.setPropertyValue("user:username", username);
        user.setPropertyValue("user:tenantId", tenant);
        if (isPowerUser) {
            String pwrUsrGrp = getPowerUsersGroup();
            user.setPropertyValue("user:groups", new String[] { pwrUsrGrp });
        }
        try {
            userManager.createUser(user);
        } catch (UserAlreadyExistsException e) {
            // do nothing
        } finally {
            session.save();
        }
        return userManager.getPrincipal(username);
    }

    protected NuxeoGroup createGroup(String groupName) {
        DocumentModel group = userManager.getBareGroupModel();
        group.setPropertyValue("group:groupname", groupName);
        String computedGroupName = groupName;
        try {
            computedGroupName = userManager.createGroup(group).getId();
        } finally {
            session.save();
        }
        return userManager.getGroup(computedGroupName);
    }
}
