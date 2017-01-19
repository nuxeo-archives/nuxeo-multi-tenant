/*
 * (C) Copyright 2013 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     dmetzler
 */
package org.nuxeo.ecm.multi.tenant;

import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.test.annotations.RepositoryInit;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.ecm.platform.usermanager.exceptions.UserAlreadyExistsException;
import org.nuxeo.runtime.api.Framework;

/**
 * @since 5.8
 */
public class MultiTenantRepositoryInit implements RepositoryInit {

    @Override
    public void populate(CoreSession session) {
        MultiTenantService mts = Framework.getLocalService(MultiTenantService.class);
        mts.enableTenantIsolation(session);

        DocumentModel domain = null;
        DocumentModel ws = null;

        for (int i = 0; i < 3; i++) {
            domain = session.createDocumentModel("/", "domain" + i, "Domain");
            domain = session.createDocument(domain);

            ws = session.createDocumentModel(domain.getPathAsString(), "ws" + i, "Workspace");
            ws = session.createDocument(ws);

            createUser("user" + i, domain.getName(), session);
        }

    }

    /**
     * @param session
     * @param string
     * @param name
     */
    protected NuxeoPrincipal createUser(String username, String tenant, CoreSession session) {
        UserManager userManager = Framework.getLocalService(UserManager.class);
        DocumentModel user = userManager.getBareUserModel();
        user.setPropertyValue("user:username", username);
        user.setPropertyValue("user:password", username);
        user.setPropertyValue("user:tenantId", tenant);
        try {
            userManager.createUser(user);
        } catch (UserAlreadyExistsException e) {
            // do nothing
        } finally {
            session.save();
        }
        return userManager.getPrincipal(username);
    }

}
