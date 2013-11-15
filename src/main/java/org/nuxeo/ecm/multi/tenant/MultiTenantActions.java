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

import static org.jboss.seam.ScopeType.STATELESS;
import static org.jboss.seam.annotations.Install.FRAMEWORK;
import static org.nuxeo.ecm.multi.tenant.Constants.TENANT_ID_PROPERTY;

import java.io.Serializable;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Install;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.directory.DirectoryException;
import org.nuxeo.ecm.directory.api.DirectoryService;
import org.nuxeo.ecm.platform.ui.web.api.NavigationContext;
import org.nuxeo.ecm.platform.ui.web.util.ComponentUtils;
import org.nuxeo.ecm.webapp.directory.DirectoryUIActionsBean;
import org.nuxeo.runtime.api.Framework;

import javax.faces.application.FacesMessage;
import javax.faces.component.UIComponent;
import javax.faces.context.FacesContext;
import javax.faces.validator.ValidatorException;

/**
 * @author <a href="mailto:troger@nuxeo.com">Thomas Roger</a>
 * @since 5.6
 */
@Name("multiTenantActions")
@Scope(STATELESS)
@Install(precedence = FRAMEWORK)
public class MultiTenantActions implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String TENANT_ADMINISTRATORS_VALIDATION_ERROR = "label.tenant.administrators.validation.error";

    @In(create = true)
    protected transient CoreSession documentManager;

    @In(create = true)
    protected NavigationContext navigationContext;

    @In(create = true)
    protected DirectoryUIActionsBean directoryUIActions;

    public List<DocumentModel> getTenants() throws ClientException {
        MultiTenantService multiTenantService = Framework.getLocalService(MultiTenantService.class);
        return multiTenantService.getTenants();
    }

    public boolean isTenantIsolationEnabled() throws ClientException {
        MultiTenantService multiTenantService = Framework.getLocalService(MultiTenantService.class);
        return multiTenantService.isTenantIsolationEnabled(documentManager);
    }

    public void enableTenantIsolation() throws ClientException {
        MultiTenantService multiTenantService = Framework.getLocalService(MultiTenantService.class);
        multiTenantService.enableTenantIsolation(documentManager);
    }

    public void disableTenantIsolation() throws ClientException {
        MultiTenantService multiTenantService = Framework.getLocalService(MultiTenantService.class);
        multiTenantService.disableTenantIsolation(documentManager);
    }

    public boolean isReadOnlyDirectory(String directoryName)
            throws ClientException {
        MultiTenantService multiTenantService = Framework.getLocalService(MultiTenantService.class);
        if (multiTenantService.isTenantIsolationEnabled(documentManager)) {
            if (multiTenantService.isTenantAdministrator(documentManager.getPrincipal())) {
                DirectoryService directoryService = Framework.getLocalService(DirectoryService.class);
                return !directoryService.getDirectory(directoryName).isMultiTenant();
            }
        }
        return directoryUIActions.isReadOnly(directoryName);
    }

    @SuppressWarnings("unchecked")
    public void validateTenantAdministrators(FacesContext context,
            UIComponent component, Object value) throws ClientException {
        DocumentModel currentDocument = navigationContext.getCurrentDocument();
        String currentDocumentTenantId = (String) currentDocument.getPropertyValue(TENANT_ID_PROPERTY);
        NuxeoPrincipal currentUser = (NuxeoPrincipal) documentManager.getPrincipal();
        String currentUserTenantId = currentUser.getTenantId();
        if (!StringUtils.isBlank(currentDocumentTenantId)
                && !StringUtils.isBlank(currentUserTenantId)
                && currentUserTenantId.equals(currentDocumentTenantId)) {
            String administratorGroup = MultiTenantHelper.computeTenantAdministratorsGroup(currentDocumentTenantId);
            if (currentUser.isMemberOf(administratorGroup)) {
                List<String> users = (List<String>) value;
                if (!users.contains(currentUser.getName())) {
                    FacesMessage message = new FacesMessage(
                            FacesMessage.SEVERITY_ERROR,
                            ComponentUtils.translate(context,
                                    TENANT_ADMINISTRATORS_VALIDATION_ERROR), null);
                    throw new ValidatorException(message);
                }
            }
        }
    }

}
